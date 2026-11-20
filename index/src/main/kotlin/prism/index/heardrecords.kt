package prism.index

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.time.Instant
import prism.platform.KernelDev

/*
 * The changes the index monitor keeps ([ActivityLog]), as records in memory outside the JVM's heap: so that a log of
 * a hundred thousand changes is a few megabytes of bytes, and no hundred thousand objects for the collector to walk.
 *
 * Changes are heard in the order of time, and let go in it too, so the memory is a row of chunks, each written from its
 * start until the next record does not fit, and let go whole once its newest record is too old to be kept. A chunk let
 * go is kept for the next to be written in, so a log that is busy and then quiet does not ask for memory again and
 * again. Where every chunk there may be is full, the oldest is let go however new it is: the log never takes more than
 * it was given.
 *
 * A record is a fixed header and the entry's name (or, from FSEvents, its whole path) after it:
 *
 * | at | what                                                                         |
 * |---:|------------------------------------------------------------------------------|
 * |  0 | when it was heard, in milliseconds since the epoch                           |
 * |  8 | the folder's device, as the kernel encodes it                                |
 * | 16 | the folder's inode                                                           |
 * | 24 | the burst it is of                                                           |
 * | 32 | what was done ([Act]'s ordinal)                                              |
 * | 33 | how the place is said: a folder and a name, a folder alone, or a path        |
 * | 34 | how many bytes the name or path is                                           |
 * | 36 | the name or path, its length rounded up so that the next record starts on 8  |
 */

/** The changes kept, in at most [maxBytes] of memory. Not safe to share: its log reaches it under its lock alone. */
internal class HeardRecords(
	maxBytes: Long,
) {
	/** A chunk of memory written from its start: [used] bytes of it are records, the newest heard at [newest]. */
	private class Chunk(
		val segment: MemorySegment,
	) {
		var used = 0L
		var newest = Long.MIN_VALUE
	}

	/** The chunks being kept, oldest first; the last is the one written to. */
	private val chunks = ArrayDeque<Chunk>()

	/** The memory of chunks let go, kept to be written in again. */
	private val spare = ArrayDeque<MemorySegment>()

	/** How many chunks there may be at most. */
	private val most = (maxBytes / CHUNK).coerceAtLeast(2)

	/** A change in [folder] to its entry [name]d (or to the folder, with no name), of the burst [burst]. */
	fun inFolder(
		at: Long,
		burst: Long,
		act: Act,
		folder: FileId,
		name: ByteArray?,
		lost: (Long) -> Unit,
	) = write(
		at,
		burst,
		act,
		if (name == null)
			FOLDER
		else
			NAMED,
		folder.dev.encoded.toLong(),
		folder.ino,
		name ?: NOTHING,
		lost,
	)

	/** A change at [path], of the burst [burst]. */
	fun atPath(
		at: Long,
		burst: Long,
		act: Act,
		path: String,
		lost: (Long) -> Unit,
	) = write(at, burst, act, PATH, 0, 0, path.encodeToByteArray(), lost)

	/**
	 * A record written where it fits: in the chunk being written, or the next. Where there is no room for another
	 * chunk, the oldest is let go first, and [lost] is told the burst of each of its records.
	 */
	private fun write(
		at: Long,
		burst: Long,
		act: Act,
		spot: Byte,
		dev: Long,
		ino: Long,
		text: ByteArray,
		lost: (Long) -> Unit,
	) {
		val length = minOf(text.size, MAX_TEXT)
		val size = sizeOf(length)
		val chunk =
			chunks.lastOrNull()?.takeIf { it.used + size <= CHUNK } ?: begun(lost)
		val segment = chunk.segment
		val offset = chunk.used

		segment.set(LONG, offset + AT, at)
		segment.set(LONG, offset + DEV, dev)
		segment.set(LONG, offset + INO, ino)
		segment.set(LONG, offset + BURST, burst)
		segment.set(BYTE, offset + ACT, act.ordinal.toByte())
		segment.set(BYTE, offset + SPOT, spot)
		segment.set(SHORT, offset + LENGTH, length.toShort())
		MemorySegment.copy(text, 0, segment, BYTE, offset + TEXT, length)
		chunk.used += size
		chunk.newest = at
	}

	/** A chunk begun to be written: from memory let go before, where there is any; the oldest let go, where it is due. */
	private fun begun(lost: (Long) -> Unit): Chunk {
		if (chunks.size >= most) {
			val oldest = chunks.removeFirst()

			records(oldest.segment, oldest.used) { lost(burstAt(oldest.segment, it)) }
			spare.addLast(oldest.segment)
		}

		return Chunk(
			spare.removeLastOrNull() ?: Arena.ofAuto().allocate(CHUNK, 8),
		).also(chunks::addLast)
	}

	/** Every chunk whose newest record was heard before [since] let go, its memory kept for a while to be used again. */
	fun dropOlderThan(since: Long) {
		while (chunks.firstOrNull()?.let { it.newest < since } == true) {
			val gone = chunks.removeFirst()

			if (spare.size < SPARE)
				spare.addLast(gone.segment)
		}
	}

	/** The changes kept of the burst [id], in the order they were heard. */
	fun heardIn(id: Long): List<Heard> =
		buildList {
			for (chunk in chunks)
				records(chunk.segment, chunk.used) {
					if (burstAt(chunk.segment, it) == id)
						add(heardAt(chunk.segment, it))
				}
		}

	/** Every record copied into memory of the copy's own, one after another, as the chunks hold them. */
	fun copied(): Copy {
		val size = chunks.sumOf { it.used }
		val copy =
			if (size == 0L)
				MemorySegment.NULL
			else
				Arena.ofAuto().allocate(size, 8)
		var at = 0L

		for (chunk in chunks) {
			MemorySegment.copy(chunk.segment, 0, copy, at, chunk.used)
			at += chunk.used
		}

		return Copy(copy, size)
	}

	/** The records as they were when copied: [used] bytes of [segment], which nothing writes again. */
	class Copy(
		private val segment: MemorySegment,
		private val used: Long,
	) {
		fun heardIn(id: Long): List<Heard> =
			buildList {
				records(segment, used) {
					if (burstAt(segment, it) == id)
						add(heardAt(segment, it))
				}
			}

		/** The bursts that have a change whose place [where] takes. */
		fun burstsWhere(where: (Spot) -> Boolean): Set<Long> =
			buildSet {
				records(segment, used) {
					val burst = burstAt(segment, it)

					if (burst !in this && where(spotAt(segment, it)))
						add(burst)
				}
			}
	}

	private companion object {
		/** How large a chunk is. */
		const val CHUNK = 64L shl 10

		/** How many chunks let go are kept to be written in again. */
		const val SPARE = 2

		/** The longest name or path kept; one longer is kept to this. */
		const val MAX_TEXT = 4096

		val LONG: ValueLayout.OfLong = ValueLayout.JAVA_LONG_UNALIGNED
		val SHORT: ValueLayout.OfShort = ValueLayout.JAVA_SHORT_UNALIGNED
		val BYTE: ValueLayout.OfByte = ValueLayout.JAVA_BYTE

		const val AT = 0L
		const val DEV = 8L
		const val INO = 16L
		const val BURST = 24L
		const val ACT = 32L
		const val SPOT = 33L
		const val LENGTH = 34L
		const val TEXT = 36L

		/** How a record says its place: a folder and an entry's name in it, the folder alone, or a whole path. */
		const val NAMED: Byte = 0
		const val FOLDER: Byte = 1
		const val PATH: Byte = 2

		val NOTHING = ByteArray(0)

		/** How large a record of a name or path [length] bytes long is, rounded up for the next to start on 8. */
		fun sizeOf(length: Int): Long = (TEXT + length + 7) and 7L.inv()

		/** [visit] given the offset of each record in the first [used] bytes of [segment], in order. */
		inline fun records(segment: MemorySegment, used: Long, visit: (Long) -> Unit) {
			var offset = 0L

			while (offset < used) {
				visit(offset)
				offset += sizeOf(segment.get(SHORT, offset + LENGTH).toInt())
			}
		}

		fun burstAt(segment: MemorySegment, offset: Long): Long =
			segment.get(
				LONG,
				offset + BURST,
			)

		fun textAt(segment: MemorySegment, offset: Long): String {
			val length = segment.get(SHORT, offset + LENGTH).toInt()
			val bytes = ByteArray(length)

			MemorySegment.copy(segment, BYTE, offset + TEXT, bytes, 0, length)

			return bytes.decodeToString()
		}

		fun spotAt(segment: MemorySegment, offset: Long): Spot {
			val folder =
				FileId(
					KernelDev.ofEncoded(segment.get(LONG, offset + DEV).toInt()),
					segment.get(LONG, offset + INO),
				)

			return when (segment.get(BYTE, offset + SPOT)) {
				NAMED -> Spot.InFolder(folder, textAt(segment, offset))
				FOLDER -> Spot.InFolder(folder, null)
				else -> Spot.AtPath(textAt(segment, offset))
			}
		}

		fun heardAt(segment: MemorySegment, offset: Long): Heard =
			Heard(
				Instant.ofEpochMilli(segment.get(LONG, offset + AT)),
				Act.entries[segment.get(BYTE, offset + ACT).toInt()],
				spotAt(segment, offset),
			)
	}
}
