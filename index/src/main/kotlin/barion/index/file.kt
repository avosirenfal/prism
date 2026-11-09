package barion.index

import barion.linux.Dev
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.zip.CRC32C
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * The index's file (docs/format.md says it byte by byte): a header, then frames. The first frame is a snapshot of the
 * index, compacted, its blocks written column by column; every frame after it is a batch of changes, appended as the
 * index is flushed. A frame's payload is compressed (raw DEFLATE) and checked (CRC-32C), so a frame cut short by a
 * crash, or damaged, is found, and it and what follows it are dropped: the index is what the frames before it say.
 * Now and then the file is compacted: written anew, as one snapshot, beside the old, and renamed over it.
 *
 * Reading a file is reading each frame's payload into memory, inflating it, and making the columns from it: a
 * block's entries are laid out in preorder and written so (their depths, their names' lengths, their names, their
 * kinds, their devices), so reading them back makes the block's arrays directly, with nothing to sort or link.
 */

/** What reading an index's file gave. */
sealed interface IndexRead {
	/**
	 * The [index] its frames make. [length] is how much of the file they take, and [changes] how much of it is changes
	 * after the snapshot; where the file is longer ([torn]), the rest is a frame cut short or damaged, which is dropped.
	 */
	data class Read(
		val index: Index,
		val length: Long,
		val changes: Long,
		val torn: Boolean,
	) : IndexRead

	data object Missing : IndexRead

	/** A file that is not an index's, or of a version this does not read. */
	data class Unreadable(
		val why: String,
	) : IndexRead
}

object IndexFile {
	/** "BARIONIX": the first eight bytes of every index file. */
	private val MAGIC = "BARIONIX".encodeToByteArray()
	private const val VERSION: Short = 1
	private const val HEADER = 16
	private const val FRAME_HEADER = 16

	private const val SNAPSHOT: Byte = 1
	private const val CHANGES: Byte = 2

	private const val INDEXED: Byte = 1
	private const val PRESENT: Byte = 2
	private const val ABSENT: Byte = 3
	private const val PICKED: Byte = 4

	/** The index [file] holds. */
	fun read(file: Path): IndexRead {
		val bytes =
			try {
				Files.readAllBytes(file)
			} catch (missing: NoSuchFileException) {
				return IndexRead.Missing
			}

		if (bytes.size < HEADER || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC))
			return IndexRead.Unreadable("$file is not an index")

		val header = ByteBuffer.wrap(bytes, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
		val version = header.getShort(MAGIC.size)

		if (version != VERSION)
			return IndexRead.Unreadable(
				"$file is an index of version $version; this reads version $VERSION",
			)

		var index = Index.EMPTY
		var at = HEADER
		var changes = 0L

		while (true) {
			val frame = frameAt(bytes, at) ?: break

			// A frame whole and sound that still does not read as one (written by something else, or by a bug) is
			// dropped as a torn one is: the index is what the frames before it say.
			index =
				try {
					when (frame.kind) {
						SNAPSHOT -> snapshotOf(In(frame.payload)).also { changes = 0 }
						else -> index.with(changesOf(In(frame.payload))).also { changes += frame.length }
					}
				} catch (wrong: IOException) {
					break
				} catch (wrong: IllegalArgumentException) {
					break
				}
			at += frame.length
		}

		return IndexRead.Read(index, at.toLong(), changes, torn = at < bytes.size)
	}

	/**
	 * [file] made anew, holding [index] compacted, as one snapshot: written beside it, flushed to the disk, and
	 * renamed over it, so the file is the old one or the new one, never half of either. Its length.
	 */
	fun write(file: Path, index: Index): Long {
		val compact = index.compacted()
		val beside = file.resolveSibling("${file.fileName}.new")

		val header =
			ByteBuffer
				.allocate(
					HEADER,
				).order(ByteOrder.LITTLE_ENDIAN)
				.put(MAGIC)
				.putShort(VERSION)

		val snapshot = frame(SNAPSHOT, Out().also { written(it, compact) }.bytes())

		file.parent?.let(Files::createDirectories)
		FileChannel
			.open(
				beside,
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING,
			).use {
				it.write(arrayOf(header.array().let(ByteBuffer::wrap), ByteBuffer.wrap(snapshot)))
				it.force(true)
			}
		try {
			Files.move(beside, file, StandardCopyOption.ATOMIC_MOVE)
		} catch (unsupported: AtomicMoveNotSupportedException) {
			Files.move(beside, file, StandardCopyOption.REPLACE_EXISTING)
		}

		return (HEADER + snapshot.size).toLong()
	}

	/**
	 * [changes] appended to [file] as one frame, after the first [length] bytes (what a read found good; anything
	 * after it, a frame cut short, is cut off first), and flushed to the disk. The file's length after it.
	 */
	fun append(file: Path, length: Long, changes: List<Change>): Long {
		val frame = frame(CHANGES, Out().also { out -> written(out, changes) }.bytes())

		FileChannel.open(file, StandardOpenOption.WRITE).use {
			it.truncate(length)
			it.position(length)
			it.write(ByteBuffer.wrap(frame))
			it.force(false)
		}

		return length + frame.size
	}

	// ---- frames --------------------------------------------------------------------------------------------------

	private class Frame(
		val kind: Byte,
		val payload: ByteArray,
		/** The frame's whole length in the file. */
		val length: Int,
	)

	/** A frame: kind, three zeros, the payload's length, the stored length, the stored bytes' CRC-32C, the stored bytes. */
	private fun frame(kind: Byte, payload: ByteArray): ByteArray {
		val stored = deflated(payload)
		val crc = CRC32C().also { it.update(stored) }

		return ByteBuffer
			.allocate(FRAME_HEADER + stored.size)
			.order(ByteOrder.LITTLE_ENDIAN)
			.put(kind)
			.put(ByteArray(3))
			.putInt(payload.size)
			.putInt(stored.size)
			.putInt(crc.value.toInt())
			.put(stored)
			.array()
	}

	/** The frame at [at], or null where there is none whole and sound: the end of the file, or a frame torn. */
	private fun frameAt(bytes: ByteArray, at: Int): Frame? {
		if (bytes.size - at < FRAME_HEADER)
			return null

		val header = ByteBuffer.wrap(bytes, at, FRAME_HEADER).order(ByteOrder.LITTLE_ENDIAN)
		val kind = header.get(at)
		val length = header.getInt(at + 4)
		val stored = header.getInt(at + 8)
		val crc = header.getInt(at + 12)

		if ((kind != SNAPSHOT && kind != CHANGES) || length < 0 || stored < 0 ||
			stored > bytes.size - at - FRAME_HEADER
		)
			return null
		if (CRC32C().also { it.update(bytes, at + FRAME_HEADER, stored) }.value.toInt() != crc)
			return null

		val payload = inflated(bytes, at + FRAME_HEADER, stored, length) ?: return null

		return Frame(kind, payload, FRAME_HEADER + stored)
	}

	private fun deflated(bytes: ByteArray): ByteArray {
		val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)

		try {
			deflater.setInput(bytes)
			deflater.finish()

			val out = ByteBuilder(maxOf(64, bytes.size / 4))
			val chunk = ByteArray(64 * 1024)

			while (!deflater.finished())
				out.add(chunk, 0, deflater.deflate(chunk))

			return out.toArray()
		} finally {
			deflater.end()
		}
	}

	private fun inflated(bytes: ByteArray, from: Int, stored: Int, length: Int): ByteArray? {
		val inflater = Inflater(true)

		try {
			inflater.setInput(bytes, from, stored)

			val out = ByteArray(length)
			var at = 0

			while (at < length) {
				val read = inflater.inflate(out, at, length - at)

				if (read == 0 && (inflater.finished() || inflater.needsInput()))
					return null
				at += read
			}

			return out
		} catch (damaged: DataFormatException) {
			return null
		} finally {
			inflater.end()
		}
	}

	// ---- payloads ------------------------------------------------------------------------------------------------

	/** A snapshot: the roots, what was picked when, and one block a root. */
	private fun written(out: Out, index: Index) {
		out.count(index.roots.size)
		index.roots.sortedBy { it.key }.forEach { out.bytes(it.bytes) }
		out.count(index.picked.size)
		for ((where, at) in index.picked) {
			out.bytes(where.bytes)
			out.instant(at)
		}
		out.count(index.placed.size)
		index.placed.forEach { written(out, it.block) }
	}

	private fun snapshotOf(read: In): Index {
		val roots = List(read.count()) { whereOf(read.bytes()) }.toSet()
		val picked = List(read.count()) { whereOf(read.bytes()) to read.instant() }.toMap()
		val blocks = List(read.count()) { blockOf(read) }

		return Index.of(roots, blocks, picked)
	}

	private fun written(out: Out, changes: List<Change>) {
		out.count(changes.size)
		for (change in changes) {
			when (change) {
				is Change.Indexed -> {
					out.byte(INDEXED)
					written(out, change.tree)
				}
				is Change.Present -> {
					out.byte(PRESENT)
					written(out, change.tree)
				}
				is Change.Absent -> {
					out.byte(ABSENT)
					out.bytes(change.where.bytes)
				}
				is Change.Picked -> {
					out.byte(PICKED)
					out.bytes(change.where.bytes)
					out.instant(change.at)
				}
			}
		}
	}

	private fun changesOf(read: In): List<Change> =
		List(read.count()) {
			when (val kind = read.byte()) {
				INDEXED -> Change.Indexed(blockOf(read))
				PRESENT -> Change.Present(blockOf(read))
				ABSENT -> Change.Absent(whereOf(read.bytes()))
				PICKED -> Change.Picked(whereOf(read.bytes()), read.instant())
				else -> throw IOException("a change of kind $kind")
			}
		}

	/**
	 * A block, column by column: its root, its devices, then for each entry in turn its depth (as the step from the
	 * entry before), its name's length, then all the names, all the kinds, all the devices. Columns of like values
	 * side by side are what compresses well: depths step by -1, 0 or 1, kinds are a handful of values.
	 */
	private fun written(out: Out, block: Block) {
		out.bytes(block.root.bytes)
		out.count(block.devices.size)
		for (device in block.devices) {
			out.count(device.dev.major)
			out.count(device.dev.minor)
			out.bytes(device.type?.encodeToByteArray())
		}
		out.count(block.size)

		val depth = IntArray(block.size)

		for (entry in 1 until block.size) {
			depth[entry] = depth[block.parent[entry]] + 1
			out.signed(depth[entry] - depth[entry - 1])
		}
		for (entry in 0 until block.size)
			out.count(block.nameLength(entry))
		out.raw(block.names)
		out.raw(block.kinds)
		for (entry in 0 until block.size)
			out.count(block.device[entry].toInt() and 0xFFFF)
	}

	private fun blockOf(read: In): Block {
		val root = whereOf(read.bytes())
		val devices =
			List(read.count()) {
				Device(Dev(read.count(), read.count()), read.bytes()?.decodeToString())
			}
		val size = read.count()
		val parent = IntArray(size)
		val open = IntBuilder()

		// Each entry's parent is the nearest entry before it one level up, which is on the stack of open folders.
		parent[0] = -1
		open.add(0)
		for (entry in 1 until size) {
			val depth = open.size - 1 + read.signed()

			if (depth < 1 || depth > open.size)
				throw IOException("entry $entry is $depth deep, under ${open.size} open folders")
			open.truncate(depth)
			parent[entry] = open[depth - 1]
			open.add(entry)
		}

		val nameAt = IntArray(size + 1)

		for (entry in 0 until size)
			nameAt[entry + 1] = nameAt[entry] + read.count()

		val names = read.raw(nameAt[size])
		val kinds = read.raw(size)
		val device = ShortArray(size) { read.count().toShort() }

		return try {
			Block(root, parent, nameAt, names, kinds, device, devices)
		} catch (wrong: IllegalArgumentException) {
			throw IOException("a block that is not one: ${wrong.message}", wrong)
		}
	}

	/** What a payload is written into: counts and steps as LEB128 varints, the rest as it is. */
	private class Out {
		private val bytes = ByteBuilder(1 shl 16)

		fun bytes(): ByteArray = bytes.toArray()

		fun byte(value: Byte) = bytes.add(value)

		fun raw(values: ByteArray) = bytes.add(values)

		fun count(value: Int) {
			require(value >= 0) { "a count is never negative" }
			unsigned(value.toLong())
		}

		/** A signed number, zigzagged so that a small one, either way, takes a byte. */
		fun signed(value: Int) =
			unsigned(((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL)

		/** Bytes and their length; null as a length of 0 and empty bytes as 1, then the bytes. */
		fun bytes(values: ByteArray?) {
			if (values == null) {
				count(0)

				return
			}
			count(values.size + 1)
			raw(values)
		}

		fun instant(at: Instant) {
			unsigned((at.epochSecond shl 1) xor (at.epochSecond shr 63))
			count(at.nano)
		}

		private fun unsigned(value: Long) {
			var rest = value

			while (rest and 0x7FL.inv() != 0L) {
				bytes.add(((rest and 0x7F) or 0x80).toByte())
				rest = rest ushr 7
			}
			bytes.add(rest.toByte())
		}
	}

	/** Reads what [Out] wrote; a payload that ends too soon is an [IOException]. */
	private class In(
		private val bytes: ByteArray,
	) {
		private var at = 0

		fun byte(): Byte = bytes.getOrNull(at++) ?: throw IOException("a payload ends too soon")

		fun raw(length: Int): ByteArray {
			if (length < 0 || length > bytes.size - at)
				throw IOException("a payload ends too soon")

			return bytes.copyOfRange(at, at + length).also { at += length }
		}

		fun count(): Int =
			unsigned().let {
				if (it >
					Int.MAX_VALUE
				)
					throw IOException("a count of $it")
				else
					it.toInt()
			}

		fun signed(): Int = unsigned().toInt().let { (it ushr 1) xor -(it and 1) }

		fun bytes(): ByteArray? =
			count().let {
				if (it == 0)
					null
				else
					raw(it - 1)
			}

		fun instant(): Instant {
			val zigzag = unsigned()

			return Instant.ofEpochSecond((zigzag ushr 1) xor -(zigzag and 1), count().toLong())
		}

		private fun unsigned(): Long {
			var value = 0L
			var shift = 0

			while (true) {
				val byte = byte().toLong()

				value = value or ((byte and 0x7F) shl shift)
				if (byte and 0x80 == 0L)
					return value
				shift += 7
				if (shift > 63)
					throw IOException("a number too long")
			}
		}
	}

	/** A root's or a place's path, which must be there. */
	private fun whereOf(bytes: ByteArray?): Where =
		Where(bytes ?: throw IOException("a place without a path"))
}
