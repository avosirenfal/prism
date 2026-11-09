package barion.watcher

import barion.ebpf.Hook
import barion.ebpfloader.Attachment
import barion.ebpfloader.LoadedObject
import barion.ebpfloader.Loading
import barion.ebpfloader.RecordView
import barion.ebpfloader.Refusal
import barion.ebpfloader.RingReader
import barion.ebpfloader.load
import barion.linux.Dev
import barion.linux.FileKind
import barion.linux.Sys
import barion.linux.Wait
import barion.linux.map
import barion.probe.Change
import barion.probe.FsChange
import barion.probe.FsWatch
import barion.probe.Lost

/*
 * The watcher: the probe's object loaded from this module's resources (where the build put it), attached, and its ring
 * read into [Observed] changes, which hold nothing of the ring's memory. What is here is only what the kernel said;
 * what an index makes of it is the daemon's.
 */

/** One change the kernel made to a directory's entries, as the watcher saw it. */
class Observed(
	val change: Change,
	/** The device, as the kernel names the file system: its superblock's. */
	val dev: Dev,
	/** The directory the entry is in, or was in before a move. */
	val parent: Long,
	/** Where a move took the entry; null for anything that is not a move. */
	val movedTo: Long?,
	/** The entry's inode; null for an entry that is gone. */
	val ino: Long?,
	/** The entry's name as the directory holds it: bytes, which need not be UTF-8. After a move, its new name. */
	name: ByteArray,
	/** What the entry is, where it is still there to say. */
	val kind: FileKind?,
	val tgid: Int,
	val atNs: Long,
) {
	private val name = name.copyOf()

	/** The entry's name, copied. */
	fun name(): ByteArray = name.copyOf()

	override fun equals(other: Any?): Boolean =
		other is Observed &&
			change == other.change &&
			dev == other.dev &&
			parent == other.parent &&
			movedTo == other.movedTo &&
			ino == other.ino &&
			name.contentEquals(other.name) &&
			kind == other.kind &&
			tgid == other.tgid &&
			atNs == other.atNs

	override fun hashCode(): Int =
		(dev.hashCode() * 31 + parent.hashCode()) * 31 + name.contentHashCode()

	override fun toString(): String =
		"$change $dev $parent${movedTo?.let { " -> $it" }.orEmpty()}/${name.decodeToString()}"
}

/** What starting the watcher gave: a watch, or why there is none. */
sealed interface Watching {
	data class Started(
		val watch: Watch,
	) : Watching

	data class Refused(
		val why: Refusal,
	) : Watching

	/** The build put no compiled watcher in the resources: a build without clang's BPF target. */
	data object NotBuilt : Watching

	/** Loaded and attached, but its ring could not be mapped to be read. */
	data class RingUnreadable(
		val failure: Sys.Failed,
	) : Watching
}

/** The watcher, running: its hooks as the kernel took them, and its changes as they come. */
class Watch internal constructor(
	private val loaded: LoadedObject,
	private val reader: RingReader<FsChange>,
) : AutoCloseable {
	/** Each hook, attached or not and why. */
	val hooks: Map<Hook, Attachment> get() = loaded.attachments

	/**
	 * The changes the kernel has made since the last read, after waiting as [wait] says for there to be any, each
	 * given to [seen]. How many records were read, including any [seen] was not given because they named nothing.
	 */
	fun await(wait: Wait, seen: (Observed) -> Unit): Sys<Int> =
		reader.await(wait) { record -> observed(record)?.let(seen) }

	/**
	 * The changes the watcher could not send because the ring was full. When this grows, the index cannot know what
	 * it missed, and reads again the directories it holds.
	 */
	fun lost(): Sys<Long> =
		loaded.counted(FsWatch.maps.lost).map {
			it.getValue(Lost.RingFull)
		}

	override fun close() {
		reader.close()
		loaded.close()
	}

	private fun observed(record: RecordView<FsChange>): Observed? {
		val name = record.text(FsChange.name).takeIf { it.isNotEmpty() } ?: return null
		val change = record.entry(FsChange.change, Change.entries) ?: return null
		val ino = record.number(FsChange.ino)
		val mode = record.number(FsChange.mode).toInt()

		return Observed(
			change = change,
			dev = Dev.ofKernel(record.number(FsChange.dev).toInt()),
			parent = record.number(FsChange.parentIno),
			movedTo = record.number(FsChange.toParentIno).takeIf { it != 0L },
			ino = ino.takeIf { it != 0L },
			name = name,
			kind = if (mode == 0) null else FileKind.ofMode(mode),
			tgid = record.number(FsChange.tgid).toInt(),
			atNs = record.number(FsChange.atNs),
		)
	}
}

/** The watcher's compiled object, as the build put it in the resources, or null where it did not. */
fun watcherObject(): ByteArray? =
	Watch::class.java.getResourceAsStream("/barion/probe/fswatch.bpf.o")?.use {
		it.readBytes()
	}

/** The watcher loaded, attached and ready to read. */
fun startWatching(): Watching {
	val bytes = watcherObject() ?: return Watching.NotBuilt

	return when (val loading = load(FsWatch.program, bytes)) {
		is Loading.Refused -> Watching.Refused(loading.why)
		is Loading.Loaded ->
			when (val reader = loading.loaded.reader(FsWatch.maps.changes)) {
				is Sys.Ok -> Watching.Started(Watch(loading.loaded, reader.value))
				is Sys.Failed -> {
					loading.loaded.close()

					Watching.RingUnreadable(reader)
				}
			}
	}
}
