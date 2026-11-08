package barion.ebpf

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/*
 * BTF: the kernel's own description of its types, which it publishes at /sys/kernel/btf/vmlinux. The DSL's
 * declarations say what barion believes the kernel holds; this reads what it really holds, so the two can be
 * compared before anything is loaded ([kernelMismatches]) and a renamed or retyped field is a named mismatch
 * rather than a wrong read at run time.
 *
 * Only what the check needs is read: structures and their members, functions and their signatures, and the
 * typedefs a raw tracepoint's arguments are described by. Nothing here loads anything.
 */

/** What kind of type BTF says something is. Only the kinds the check reads are named. */
private object Kind {
	const val INT = 1
	const val POINTER = 2
	const val ARRAY = 3
	const val STRUCT = 4
	const val UNION = 5
	const val ENUM = 6
	const val FORWARD = 7
	const val TYPEDEF = 8
	const val VOLATILE = 9
	const val CONST = 10
	const val RESTRICT = 11
	const val FUNC = 12
	const val FUNC_PROTO = 13
	const val VAR = 14
	const val DATA_SECTION = 15
	const val FLOAT = 16
	const val DECL_TAG = 17
	const val TYPE_TAG = 18
	const val ENUM64 = 19
}

/** One type as BTF describes it. */
class BtfType internal constructor(
	val id: Int,
	val name: String,
	val kind: Int,
	/** A structure's or an integer's size in bytes; meaningless for the kinds that carry a type instead. */
	val sizeBytes: Int,
	/** What it points at, is a name for, or gives back, by id; 0 is `void`. */
	val refers: Int,
	val members: List<BtfMember>,
	/** An integer's bits, for the few kernel fields that are bitfields. */
	val bits: Int,
	val signed: Boolean,
) {
	val isStructOrUnion: Boolean get() = kind == Kind.STRUCT || kind == Kind.UNION
	val isInteger: Boolean get() =
		kind == Kind.INT || kind == Kind.ENUM ||
			kind == Kind.ENUM64

	/** A plain integer, which carries a signedness of its own; an enum does not. */
	val isPlainInteger: Boolean get() = kind == Kind.INT
	val isPointer: Boolean get() = kind == Kind.POINTER

	/** A structure the kernel's BTF only names, because nothing it describes holds one whole. */
	val isForwardDeclaration: Boolean get() = kind == Kind.FORWARD

	override fun toString(): String = "#$id ${name.ifEmpty { "(anonymous)" }}"
}

/** One member of a structure or one parameter of a function. */
class BtfMember internal constructor(
	val name: String,
	val type: Int,
	/** Where it lies, in bits; a structure's members only. */
	val bitOffset: Int,
	/** Its width in bits when it is a bitfield, or 0: a field barion must not read with a plain load. */
	val bitfieldBits: Int,
) {
	val byteOffset: Int get() = bitOffset / 8

	override fun toString(): String = name
}

/**
 * The kernel's types, as BTF describes them. Read from the running kernel ([ofRunningKernel]) or from bytes (a
 * kernel's BTF kept for a test).
 */
class Btf private constructor(
	private val types: List<BtfType>,
) {
	private val structs: Map<String, BtfType> =
		types.filter { it.isStructOrUnion && it.name.isNotEmpty() }.associateBy { it.name }
	private val functions: Map<String, BtfType> =
		types.filter { it.kind == Kind.FUNC }.associateBy { it.name }
	private val typedefs: Map<String, BtfType> =
		types.filter { it.kind == Kind.TYPEDEF }.associateBy { it.name }

	val typeCount: Int get() = types.size

	fun byId(id: Int): BtfType? = types.firstOrNull { it.id == id }

	/** The structure the kernel knows by [name], or null where it has none. */
	fun struct(name: String): BtfType? = structs[name]

	/** The function the kernel knows by [name], as the [FUNC_PROTO][Kind.FUNC_PROTO] behind it. */
	fun function(name: String): BtfType? = functions[name]?.let { resolved(it.refers) }

	/**
	 * A raw tracepoint's arguments, as the kernel describes them: the `btf_trace_<name>` typedef's prototype, whose
	 * first parameter is the kernel's own `void *__data` and whose rest are the tracepoint's own.
	 */
	fun tracepoint(name: String): BtfType? =
		typedefs["btf_trace_$name"]
			?.let { resolved(it.refers) }
			// The typedef names a pointer to the function: `typedef void (*btf_trace_sys_enter)(void *, ...)`.
			?.let { if (it.isPointer) resolved(it.refers) else it }

	/** [id] with the names, `const`s and `volatile`s followed through to the type they stand for. */
	fun resolved(id: Int): BtfType? {
		var at = byId(id)
		var steps = 0

		while (at != null && at.kind in THROUGH && steps++ < DEPTH)
			at = byId(at.refers)

		return at
	}

	/**
	 * [struct]'s member named [name], looked for in the anonymous structures and unions it holds as well, since C
	 * reaches those members as if they were the holder's own (the kernel's `dentry.d_name` lies in one), with the bit
	 * offset of the whole chain.
	 */
	fun member(struct: BtfType, name: String): BtfMember? {
		for (held in struct.members) {
			if (held.name == name)
				return held
			if (held.name.isEmpty()) {
				val inner = resolved(held.type) ?: continue

				if (inner.isStructOrUnion)
					member(inner, name)?.let {
						return BtfMember(it.name, it.type, held.bitOffset + it.bitOffset, it.bitfieldBits)
					}
			}
		}

		return null
	}

	companion object {
		/** Where the kernel publishes its own types, when it was built to ([CONFIG_DEBUG_INFO_BTF]). */
		val RUNNING_KERNEL: Path = Path("/sys/kernel/btf/vmlinux")

		private val THROUGH =
			setOf(Kind.TYPEDEF, Kind.CONST, Kind.VOLATILE, Kind.RESTRICT, Kind.TYPE_TAG)
		private const val DEPTH = 32
		private const val MAGIC = 0xEB9F
		private const val HEADER_BYTES = 24

		/** The running kernel's types, or null where it publishes none. */
		fun ofRunningKernel(): Btf? =
			RUNNING_KERNEL
				.takeIf {
					it.exists()
				}?.let { of(it.readBytes()) }

		/** [bytes] of a `.BTF` section read as types. */
		fun of(bytes: ByteArray): Btf {
			val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
			val magic = buffer.getShort(0).toInt() and 0xFFFF

			require(magic == MAGIC) {
				"not BTF: its first bytes are 0x${magic.toString(16)}, not 0x${MAGIC.toString(16)}"
			}

			val headerBytes = buffer.getInt(4)
			val typesAt = headerBytes + buffer.getInt(8)
			val typesBytes = buffer.getInt(12)
			val stringsAt = headerBytes + buffer.getInt(16)

			require(headerBytes >= HEADER_BYTES) {
				"BTF's header is $headerBytes bytes, too few to read"
			}

			val names = Names(buffer, stringsAt)
			val types = ArrayList<BtfType>()
			var at = typesAt
			var id = 1

			while (at < typesAt + typesBytes) {
				val (type, length) = readType(buffer, at, id++, names)

				types += type
				at += length
			}

			return Btf(types)
		}

		/** One type at [at], and the bytes it took. */
		private fun readType(
			buffer: ByteBuffer,
			at: Int,
			id: Int,
			names: Names,
		): Pair<BtfType, Int> {
			val nameOffset = buffer.getInt(at)
			val info = buffer.getInt(at + 4)
			val sizeOrType = buffer.getInt(at + 8)
			val vlen = info and 0xFFFF
			val kind = (info shr 24) and 0x1F
			val kindFlag = (info ushr 31) == 1
			val after = at + 12
			val members = ArrayList<BtfMember>()
			var bits = 0
			var signed = false

			val trailing =
				when (kind) {
					Kind.INT -> {
						val encoding = buffer.getInt(after)

						bits = encoding and 0xFF
						signed = ((encoding shr 24) and 0x1) == 1
						4
					}
					Kind.STRUCT, Kind.UNION -> {
						for (index in 0 until vlen) {
							val memberAt = after + index * 12
							val offset = buffer.getInt(memberAt + 8)

							members +=
								BtfMember(
									names[buffer.getInt(memberAt)],
									buffer.getInt(memberAt + 4),
									// With the flag set the offset word carries the bitfield's width above its offset.
									if (kindFlag)
										offset and 0xFFFFFF
									else
										offset,
									if (kindFlag)
										(offset ushr 24) and 0xFF
									else
										0,
								)
						}
						vlen * 12
					}
					Kind.FUNC_PROTO -> {
						for (index in 0 until vlen) {
							val parameterAt = after + index * 8

							members +=
								BtfMember(names[buffer.getInt(parameterAt)], buffer.getInt(parameterAt + 4), 0, 0)
						}
						vlen * 8
					}
					Kind.ENUM -> vlen * 8
					Kind.ENUM64 -> vlen * 12
					Kind.ARRAY -> 12
					Kind.VAR -> 4
					Kind.DATA_SECTION -> vlen * 12
					Kind.DECL_TAG -> 4
					else -> 0
				}

			val size = if (kind in SIZED) sizeOrType else 0
			val refers = if (kind in SIZED) 0 else sizeOrType

			return BtfType(id, names[nameOffset], kind, size, refers, members, bits, signed) to
				(12 + trailing)
		}

		/** The kinds whose third word is a size rather than a type. */
		private val SIZED =
			setOf(
				Kind.INT,
				Kind.STRUCT,
				Kind.UNION,
				Kind.ENUM,
				Kind.ENUM64,
				Kind.DATA_SECTION,
				Kind.FLOAT,
			)
	}

	/** BTF's strings: one block of NUL-terminated names, which every type names itself by an offset into. */
	private class Names(
		private val buffer: ByteBuffer,
		private val at: Int,
	) {
		operator fun get(offset: Int): String {
			if (offset == 0)
				return ""

			val start = at + offset
			var end = start

			while (buffer.get(end) != 0.toByte())
				end++

			val bytes = ByteArray(end - start)

			buffer.duplicate().position(start).get(bytes)

			return bytes.decodeToString()
		}
	}
}
