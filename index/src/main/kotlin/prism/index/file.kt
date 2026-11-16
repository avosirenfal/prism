package prism.index

import com.github.luben.zstd.Zstd
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32C
import prism.platform.Dev
import prism.platform.KernelDev
import prism.shared.Snapshot

/*
 * The index's file (docs/format.md says it byte by byte): a header, then frames. The first frame is a snapshot of the
 * index, compacted, its blocks written column by column; every frame after it is a batch of changes, appended as the
 * index is flushed. A frame's payload is compressed (zstd) and checked (CRC-32C), so a frame cut short by a
 * crash, or damaged, is found, and it and what follows it are dropped: the index is what the frames before it say.
 * Now and then the file is compacted: written anew, as one snapshot, beside the old, and renamed over it.
 *
 * Reading a file is reading each frame's payload into memory, decompressing it, and making the columns from it: a
 * block's entries are laid out in preorder and written so (their depths, their names' lengths, their names, their
 * kinds, their inodes, their devices, when each was first seen), so reading them back makes the block's arrays
 * directly, with nothing to sort or link.
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
	/** "PRISMIDX": the first eight bytes of every index file. */
	private val MAGIC = "PRISMIDX".encodeToByteArray()

	/**
	 * 4: when each entry was first seen, and nothing of what was picked, which is kept on its own ([UsageFile]).
	 * (3 had no first sightings, and kept picks; 2 had a stat's device numbers alone; 1 was compressed with DEFLATE,
	 * and had no inodes.)
	 */
	private const val VERSION: Short = 4

	/** How hard a snapshot is compressed: written now and then, read at every start, so worth more time than a change. */
	private const val SNAPSHOT_LEVEL = 9

	/** How hard a frame of changes is compressed: written at every flush. */
	private const val CHANGES_LEVEL = 3
	private const val HEADER = 16
	private const val FRAME_HEADER = 16

	private const val SNAPSHOT: Byte = 1
	private const val CHANGES: Byte = 2

	private const val INDEXED: Byte = 1
	private const val PRESENT: Byte = 2
	private const val ABSENT: Byte = 3

	/** The index [file] holds; or that there is none there; or why what is there cannot be read. */
	fun read(file: Path): IndexRead {
		val bytes =
			try {
				Files.readAllBytes(file)
			} catch (missing: NoSuchFileException) {
				return IndexRead.Missing
			} catch (failed: IOException) {
				// There, or in the way, and not to be read (a folder, a file this user may not read, a disk that
				// fails): said, as a file that is no index is, rather than thrown at whoever only wanted an index.
				return IndexRead.Unreadable("$file cannot be read: ${failed.message ?: failed}")
			}

		if (bytes.size < HEADER || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC))
			return IndexRead.Unreadable("$file is not an index")

		val header = ByteBuffer.wrap(bytes, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
		val version = header.getShort(MAGIC.size)

		if (version != VERSION)
			return IndexRead.Unreadable(
				"$file is an index of version $version, and this prism reads version $VERSION. " +
					"The daemon writes it anew as it starts; without one, `prism index --fresh` does",
			)

		// The index as its changes are made to it, frame after frame: one working copy for them all, since a file can
		// hold thousands of frames after its snapshot, and an index made anew for each is the whole of it gone over
		// each time.
		var changing = Index.EMPTY.changing()
		var at = HEADER
		var changes = 0L

		while (true) {
			val frame = frameAt(bytes, at) ?: break

			// A frame whole and sound that still does not read as one (written by something else, or by a bug) is
			// dropped as a torn one is: the index is what the frames before it say. It is read whole before any of
			// it is made, so that none of a frame that does not read is.
			try {
				when (frame.kind) {
					SNAPSHOT -> {
						changing = snapshotOf(In(frame.payload)).changing()
						changes = 0
					}
					else -> {
						changesOf(In(frame.payload)).forEach(changing::make)
						changes += frame.length
					}
				}
			} catch (wrong: IOException) {
				break
			} catch (wrong: IllegalArgumentException) {
				break
			}
			at += frame.length
		}

		return IndexRead.Read(changing.index(), at.toLong(), changes, torn = at < bytes.size)
	}

	/**
	 * [file] made anew, holding [index] compacted, as one snapshot: written beside it, flushed to the disk, and
	 * renamed over it ([Snapshot]), so the file is the old one or the new one, never half of either. Its length.
	 */
	fun write(file: Path, index: Index): Long {
		val compact = index.compacted()

		val header =
			ByteBuffer
				.allocate(
					HEADER,
				).order(ByteOrder.LITTLE_ENDIAN)
				.put(MAGIC)
				.putShort(VERSION)

		val snapshot = frame(SNAPSHOT, Out().also { written(it, compact) }.bytes())

		Snapshot.write(
			file,
			listOf(header.array().let(ByteBuffer::wrap), ByteBuffer.wrap(snapshot)),
		)

		return (HEADER + snapshot.size).toLong()
	}

	/**
	 * [changes] appended to [file] as one frame, after the first [length] bytes (what a read found good; anything
	 * after it, a frame cut short, is cut off first), and flushed to the disk. The file's length after it.
	 *
	 * Nothing writes a file this way any more: an index is written whole ([write]). Versions before this did, and a
	 * file they left is still read; this is here so that reading one is tested against files made as they made them.
	 */
	internal fun append(file: Path, length: Long, changes: List<Change>): Long {
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
		val stored =
			Zstd.compress(
				payload,
				if (kind ==
					SNAPSHOT
				)
					SNAPSHOT_LEVEL
				else
					CHANGES_LEVEL,
			)

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

		val payload = decompressed(bytes, at + FRAME_HEADER, stored, length) ?: return null

		return Frame(kind, payload, FRAME_HEADER + stored)
	}

	/** The [length] bytes [stored] bytes from [from] decompress to; null where they do not (damaged). */
	private fun decompressed(
		bytes: ByteArray,
		from: Int,
		stored: Int,
		length: Int,
	): ByteArray? {
		val out = ByteArray(length)
		val made = Zstd.decompressByteArray(out, 0, length, bytes, from, stored)

		return if (Zstd.isError(made) || made != length.toLong())
			null
		else
			out
	}

	// ---- payloads ------------------------------------------------------------------------------------------------

	/** A snapshot: the roots, and one block a root. */
	private fun written(out: Out, index: Index) {
		out.count(index.roots.size)
		index.roots.sortedBy { it.key }.forEach { out.bytes(it.bytes) }
		out.count(index.placed.size)
		index.placed.forEach { written(out, it.block) }
	}

	private fun snapshotOf(read: In): Index {
		val roots = List(read.count()) { whereOf(read.bytes()) }.toSet()
		val blocks = List(read.count()) { blockOf(read) }

		return Index.of(roots, blocks)
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
			}
		}
	}

	private fun changesOf(read: In): List<Change> =
		List(read.count()) {
			when (val kind = read.byte()) {
				INDEXED -> Change.Indexed(blockOf(read))
				PRESENT -> Change.Present(blockOf(read))
				ABSENT -> Change.Absent(whereOf(read.bytes()))
				else -> throw IOException("a change of kind $kind")
			}
		}

	/**
	 * A block, column by column: its root, its devices, then for each entry in turn its depth (as the step from the
	 * entry before), its name's length, then all the names, all the kinds, every inode (as the step from the entry
	 * before's), all the devices, and when each was first seen (as the step from the entry before's). Columns of like
	 * values side by side are what compresses well: depths step by -1, 0 or 1, kinds are a handful of values, entries
	 * made together have inodes close together, and most entries were seen when their root was first indexed.
	 */
	private fun written(out: Out, block: Block) {
		out.bytes(block.root.bytes)
		out.count(block.devices.size)
		for (device in block.devices) {
			out.count(device.dev.major)
			out.count(device.dev.minor)
			out.count(device.kernel.dev.major)
			out.count(device.kernel.dev.minor)
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
			out.signed(
				block.inodes[entry] - (
					if (entry == 0)
						0
					else
						block.inodes[entry - 1]
				),
			)
		for (entry in 0 until block.size)
			out.count(block.device[entry].toInt() and 0xFFFF)
		for (entry in 0 until block.size)
			out.signed(
				seenOf(block.seen[entry]) -
					if (entry == 0)
						0
					else
						seenOf(block.seen[entry - 1]),
			)
	}

	/** A first sighting's seconds, which [Seen] keeps unsigned in an Int, as the number they are. */
	private fun seenOf(seen: Int): Long = seen.toLong() and 0xFFFFFFFFL

	private fun blockOf(read: In): Block {
		val root = whereOf(read.bytes())

		val devices =
			List(read.count()) {
				Device(
					Dev(read.count(), read.count()),
					KernelDev(Dev(read.count(), read.count())),
					read.bytes()?.decodeToString(),
				)
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
		val inodes = LongArray(size)

		for (entry in 0 until size)
			inodes[entry] = read.signedLong() + (
				if (entry == 0)
					0
				else
					inodes[entry - 1]
			)

		val device = ShortArray(size) { read.count().toShort() }
		val seen = IntArray(size)
		var last = 0L

		for (entry in 0 until size) {
			last += read.signedLong()
			seen[entry] = last.toInt()
		}

		return try {
			Block(root, parent, nameAt, names, kinds, inodes, device, devices, seen)
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

		/** A signed number of 64 bits, zigzagged; a step between two of them wraps as they do, so it is exact. */
		fun signed(value: Long) = unsigned((value shl 1) xor (value shr 63))

		/** Bytes and their length; null as a length of 0 and empty bytes as 1, then the bytes. */
		fun bytes(values: ByteArray?) {
			if (values == null) {
				count(0)

				return
			}
			count(values.size + 1)
			raw(values)
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

		fun signedLong(): Long = unsigned().let { (it ushr 1) xor -(it and 1) }

		fun bytes(): ByteArray? =
			count().let {
				if (it == 0)
					null
				else
					raw(it - 1)
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
