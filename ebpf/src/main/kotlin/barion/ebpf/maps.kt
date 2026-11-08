package barion.ebpf

/*
 * The maps a program declares: the one way a program and the host share anything, and the one way a program keeps
 * anything between calls. A map carries what it holds in its type, so what is put in it and taken out of it is the
 * record it was declared for and nothing else, and the host decodes it by that record's own layout.
 */

/** The kinds of map barion uses, by the number the kernel knows each by and the name its definition carries. */
enum class MapKind(
	val id: Int,
	val cName: String,
) {
	Hash(1, "BPF_MAP_TYPE_HASH"),

	/** A hash whose least recently used entries the kernel drops when it is full, so it never refuses an insert. */
	LruHash(9, "BPF_MAP_TYPE_LRU_HASH"),

	/** One value per CPU per index: counters a program adds to without an atomic, summed by the host. */
	PerCpuArray(6, "BPF_MAP_TYPE_PERCPU_ARRAY"),

	/** A queue of records a program submits and the host reads in order, with no lock and no copy. */
	RingBuffer(27, "BPF_MAP_TYPE_RINGBUF"),
}

/** What a map's keys or values are: a record of several fields, or one plain value. */
sealed interface Holds {
	val sizeBytes: Int

	/** How C names the type in the map's definition. */
	val cName: String

	data class OneRecord(
		val record: Record<*>,
	) : Holds {
		override val sizeBytes: Int get() = record.layout.sizeBytes
		override val cName: String get() = "struct ${record.cName}"
	}

	data class OneValue(
		val type: Plain,
	) : Holds {
		override val sizeBytes: Int get() = type.sizeBytes
		override val cName: String get() = type.declare("").trim()
	}
}

/** A map as the emitted C and the loader see it, whatever it holds. */
sealed class BpfMap(
	val cName: String,
	val kind: MapKind,
	/** The map's entries, or its bytes for a ring buffer. */
	val maxEntries: Int,
	val keys: Holds?,
	val values: Holds?,
) {
	override fun toString(): String = cName
}

/**
 * A queue of [record]s the program submits and the host reads. [bytes] is the ring's size, which the kernel requires
 * to be a power of two and a multiple of a page; what cannot be reserved because the host has not kept up is a full
 * ring, which the program is made to say what to do about ([barion.ebpf.Body.send]).
 */
class RingBuffer<R : Record<R>> internal constructor(
	cName: String,
	val record: R,
	bytes: Int,
) : BpfMap(cName, MapKind.RingBuffer, bytes, null, null) {
	init {
		require(bytes >= 4096 && bytes.countOneBits() == 1) {
			"a ring buffer is a power of two of at least 4096 bytes, not $bytes"
		}
	}
}

/** A map from a [key] record to a [value] record: what a program remembers, and what the host reads and writes. */
class Table<K : Record<K>, V : Record<V>> internal constructor(
	cName: String,
	val key: K,
	val value: V,
	maxEntries: Int,
	leastRecentlyUsed: Boolean,
) : BpfMap(
		cName,
		if (leastRecentlyUsed)
			MapKind.LruHash
		else
			MapKind.Hash,
		maxEntries,
		Holds.OneRecord(key),
		Holds.OneRecord(value),
	)

/**
 * A counter per CPU for each of [entries], which the host sums. A program adds to its own CPU's, so counting costs no
 * atomic and no contention; the entries are a Kotlin enum, so a counter that does not exist cannot be named.
 */
class Counters<E : Enum<E>> internal constructor(
	cName: String,
	val entries: List<E>,
) : BpfMap(
		cName,
		MapKind.PerCpuArray,
		entries.size,
		Holds.OneValue(U32),
		Holds.OneValue(U64),
	) {
	init {
		require(entries.isNotEmpty()) { "$cName counts nothing" }
	}
}
