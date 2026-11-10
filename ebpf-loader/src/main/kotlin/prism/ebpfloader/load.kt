package prism.ebpfloader

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import prism.ebpf.Btf
import prism.ebpf.Counters
import prism.ebpf.Hook
import prism.ebpf.Mismatch
import prism.ebpf.Program
import prism.ebpf.Record
import prism.ebpf.RingBuffer
import prism.ebpf.Table
import prism.ebpf.kernelMismatches
import prism.linux.Fd
import prism.linux.Sys

/*
 * A program declared in the DSL, loaded from the object clang compiled of it. The Kotlin declaration is the one
 * statement of what the object holds: its hooks are found in the object by the names the emitter gave them, its
 * maps by theirs, and a ring's records are read by the record's own layout. Every way a load can fail is a value
 * ([Refusal]), and every hook's attachment is one too ([Attachment]), so the daemon knows exactly which hooks it has
 * and can say which it does not, and why.
 */

/** Why a program was not loaded. */
sealed class Refusal(
	val message: String,
) {
	/** The declaration has errors, so nothing was emitted from it to load. */
	class NotLoadable(
		program: Program,
	) : Refusal("${program.name} has errors:\n${program.why()}")

	data object NoLibbpf : Refusal(
		"libbpf.so.1 is not installed, so no BPF object can be loaded",
	)

	/** The object does not hold what the declaration says: it was compiled from another program. */
	class NotThisProgram(
		program: Program,
		missing: List<String>,
	) : Refusal(
			"the object holds no ${missing.joinToString()}: it was not compiled from ${program.name}",
		)

	/** Every hook differs from this kernel, so loading what is left would load nothing. */
	class NothingMatches(
		mismatches: Map<Hook, List<Mismatch>>,
	) : Refusal(
			"no hook matches this kernel:\n" +
				mismatches.entries.joinToString(
					"\n",
				) { (hook, why) -> "$hook: ${why.joinToString("; ")}" },
		)

	class OpenFailed(
		failure: Sys.Failed,
	) : Refusal("libbpf could not read the object: $failure")

	/** The kernel refused the load: [logs] is the verifier's account of each hook it refused, by hook. */
	class LoadFailed(
		val failure: Sys.Failed,
		val logs: Map<Hook, String>,
	) : Refusal(
			"the kernel refused to load the program: $failure" +
				logs.entries.joinToString("") { (hook, log) -> "\n--- $hook\n${log.trimEnd()}" },
		)

	override fun toString(): String = message
}

/** What loading a program gave: the loaded object, or why there is none. */
sealed interface Loading {
	data class Loaded(
		val loaded: LoadedObject,
	) : Loading

	data class Refused(
		val why: Refusal,
	) : Loading
}

/** One hook's attachment: attached, left out because it differs from this kernel, or refused when attached. */
sealed interface Attachment {
	data object Attached : Attachment

	data class Mismatched(
		val mismatches: List<Mismatch>,
	) : Attachment

	data class Refused(
		val failure: Sys.Failed,
	) : Attachment
}

/**
 * A loaded program: its maps, reached by their declarations, and its hooks, each attached or not as [attachments]
 * says. Closing it detaches every hook and frees the maps; nothing of it may be used after.
 */
class LoadedObject internal constructor(
	val program: Program,
	private val libbpf: LibBpf,
	private val handle: ObjectHandle,
	private val arena: Arena,
	val attachments: Map<Hook, Attachment>,
	private val links: List<LinkHandle>,
) : AutoCloseable {
	/** The hooks that are attached: what the program will see. */
	val attached: List<Hook> get() =
		attachments
			.filterValues {
				it == Attachment.Attached
			}.keys
			.toList()

	/** A reader of [ring], one of this program's own. */
	fun <R : Record<R>> reader(ring: RingBuffer<R>): Sys<RingReader<R>> =
		RingReader.open(ring, Fd(fd(ring.cName)), arena)

	/** [table]'s entry under the key [key] writes set to the value [value] writes, whether or not it held one. */
	fun <K : Record<K>, V : Record<V>> put(
		table: Table<K, V>,
		key: (RecordWriter<K>) -> Unit,
		value: (RecordWriter<V>) -> Unit,
	): Sys<Unit> =
		Arena.ofConfined().use { scratch ->
			libbpf.update(
				fd(table.cName),
				table.key.written(scratch, key),
				table.value.written(scratch, value),
			)
		}

	/** [table]'s entry under the key [key] writes removed; ENOENT where it had none. */
	fun <K : Record<K>, V : Record<V>> remove(
		table: Table<K, V>,
		key: (RecordWriter<K>) -> Unit,
	): Sys<Unit> =
		Arena.ofConfined().use { scratch ->
			libbpf.delete(fd(table.cName), table.key.written(scratch, key))
		}

	/** Each of [counters]' counters, summed over every CPU. */
	fun <E : Enum<E>> counted(counters: Counters<E>): Sys<Map<E, Long>> =
		Arena.ofConfined().use { scratch ->
			val cpus = libbpf.possibleCpus()
			val key = scratch.allocate(JAVA_INT)
			// A per-CPU map gives each CPU's value, each in a slot of eight bytes.
			val values = scratch.allocate(JAVA_LONG, cpus.toLong())
			val sums = LinkedHashMap<E, Long>()

			for (entry in counters.entries) {
				key.set(JAVA_INT, 0, entry.ordinal)

				when (val looked = libbpf.lookup(fd(counters.cName), key, values)) {
					is Sys.Failed -> return@use looked
					is Sys.Ok ->
						sums[entry] =
							(0 until cpus).sumOf { values.getAtIndex(JAVA_LONG, it.toLong()) }
				}
			}

			Sys.Ok(sums.toMap())
		}

	private fun fd(name: String): Int {
		require(
			program.maps.any { it.cName == name },
		) { "$name is not a map of ${program.name}" }

		val map =
			libbpf.map(handle, name, arena) ?: error("${program.name}'s object has no map $name")

		return libbpf.descriptor(map)
	}

	override fun close() {
		links.forEach(libbpf::detach)
		libbpf.close(handle)
		arena.close()
	}
}

/** How much of each hook's verifier log is kept: enough for the verifier's account of where it stopped. */
private const val LOG_BYTES = 1L shl 20

/**
 * [program] loaded from [objectBytes], the object clang compiled of its emitted C, and attached. Each hook is first
 * checked against [btf] (the running kernel's, where it publishes one): a hook that differs is left out, and the
 * rest are loaded, so a kernel that moved one function costs the daemon that hook and not the others.
 */
fun load(
	program: Program,
	objectBytes: ByteArray,
	btf: Btf? = Btf.ofRunningKernel(),
): Loading {
	if (!program.isLoadable)
		return Loading.Refused(Refusal.NotLoadable(program))

	val libbpf = LibBpf.installed ?: return Loading.Refused(Refusal.NoLibbpf)

	val mismatched =
		if (btf == null)
			emptyMap()
		else
			program.hooks
				.associateWith {
					kernelMismatches(
						it,
						btf,
					)
				}.filterValues { it.isNotEmpty() }

	if (mismatched.size == program.hooks.size)
		return Loading.Refused(Refusal.NothingMatches(mismatched))

	val arena = Arena.ofShared()

	// libbpf reads the object from this memory for as long as the object is open, so it lives in the object's arena.
	val bytes =
		arena
			.allocate(
				objectBytes.size.toLong(),
			).copyFrom(MemorySegment.ofArray(objectBytes))

	val handle =
		when (val opened = libbpf.open(bytes)) {
			is Sys.Ok -> opened.value
			is Sys.Failed -> {
				arena.close()

				return Loading.Refused(Refusal.OpenFailed(opened))
			}
		}

	fun refused(why: Refusal): Loading {
		libbpf.close(handle)
		arena.close()

		return Loading.Refused(why)
	}

	val programs = program.hooks.associateWith { libbpf.program(handle, it.cName, arena) }
	val missing = programs.filterValues { it == null }.keys.map { it.cName }

	if (missing.isNotEmpty())
		return refused(Refusal.NotThisProgram(program, missing))

	val logs = HashMap<Hook, MemorySegment>()

	for ((hook, found) in programs) {
		val programHandle = found!!

		if (hook in mismatched)
			libbpf.skip(programHandle)
		else
			logs[hook] = arena.allocate(LOG_BYTES).also { libbpf.logInto(programHandle, it) }
	}

	when (val loaded = libbpf.load(handle)) {
		is Sys.Failed -> {
			val said =
				logs
					.mapValues { (_, log) ->
						log.getString(0)
					}.filterValues { it.isNotBlank() }

			return refused(Refusal.LoadFailed(loaded, said))
		}
		is Sys.Ok -> Unit
	}

	val links = ArrayList<LinkHandle>()

	val attachments =
		program.hooks.associateWith { hook ->
			mismatched[hook]?.let { Attachment.Mismatched(it) }
				?: when (val linked = libbpf.attach(programs.getValue(hook)!!)) {
					is Sys.Ok -> {
						links += linked.value

						Attachment.Attached
					}
					is Sys.Failed -> Attachment.Refused(linked)
				}
		}

	return Loading.Loaded(
		LoadedObject(program, libbpf, handle, arena, attachments, links.toList()),
	)
}
