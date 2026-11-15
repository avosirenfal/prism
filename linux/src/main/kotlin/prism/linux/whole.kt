package prism.linux

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/*
 * A file that is rewritten whole: settings, and what prism keeps of its own doing. Whatever happens while one is
 * written (prism killed, the power gone), the file is what it was or what it was to be, never part of either: it is
 * written under another name in the same folder, flushed to the disk, and renamed over the old one, which rename(2)
 * does as one step. Every such file is written here, so that every one of them is written so.
 *
 * Two processes may keep the same file (a window run on its own, beside the prism that is running). Neither hears
 * of the other's changes; what keeps one from undoing the other's is that a change is made to the file as it is at
 * that moment ([WholeFile.locked]: read, changed and written with no other writer between), not to what the process
 * last knew of it.
 */
object WholeFile {
	/** One writer at a time in this process: a file lock is the process's, and two of its threads would share it. */
	private val here = Any()

	/**
	 * [file] made to hold what [write] puts in the channel it is given, and nothing else, in one step: where [write]
	 * fails, or the disk does, [file] is as it was, and nothing is left beside it. Its folder is made where it is
	 * not there.
	 */
	fun replace(file: Path, write: (FileChannel) -> Unit) {
		val folder = file.toAbsolutePath().parent

		Files.createDirectories(folder)

		val beside = made(folder, file.fileName.toString())

		try {
			beside.second.use {
				write(it)
				it.force(true)
			}
			try {
				Files.move(beside.first, file, StandardCopyOption.ATOMIC_MOVE)
			} catch (unsupported: AtomicMoveNotSupportedException) {
				// No file system prism is known to keep a file on; one that cannot rename in a step still renames.
				Files.move(beside.first, file, StandardCopyOption.REPLACE_EXISTING)
			}
		} finally {
			// Renamed, it is no longer there to remove.
			Files.deleteIfExists(beside.first)
		}
		flushed(folder)
	}

	/** [file] made to hold [bytes]. */
	fun replace(file: Path, bytes: ByteArray) = replace(file, listOf(ByteBuffer.wrap(bytes)))

	/** [file] made to hold [parts], one after another. */
	fun replace(file: Path, parts: List<ByteBuffer>) =
		replace(file) { channel ->
			for (part in parts)
				while (part.hasRemaining())
					channel.write(part)
		}

	/** [file] made to hold [text], as UTF-8. */
	fun replace(file: Path, text: String) = replace(file, text.toByteArray(Charsets.UTF_8))

	/** [file] made to hold [lines], each ended by a new line, as UTF-8. */
	fun replace(file: Path, lines: Iterable<String>) =
		replace(file, lines.joinToString("") { "$it\n" })

	/**
	 * [body] done as the one writer of [file] there is, among this process's threads and every process that keeps
	 * the file through here: what it reads of the file is what it writes over. The lock is a file of its own beside
	 * [file] (`.lock` after its name), since [file] itself is replaced by each write.
	 */
	fun <T> locked(file: Path, body: () -> T): T =
		synchronized(here) {
			val folder = file.toAbsolutePath().parent

			Files.createDirectories(folder)
			FileChannel
				.open(
					folder.resolve("${file.fileName}.lock"),
					StandardOpenOption.CREATE,
					StandardOpenOption.WRITE,
				).use { channel -> channel.lock().use { body() } }
		}

	/**
	 * A new file in [folder] to write what [name] is to hold into, open: named after it, hidden, and with letters no
	 * other writer has (made only if no file has the name: another writer's is never written into).
	 */
	private tailrec fun made(folder: Path, name: String): Pair<Path, FileChannel> {
		val beside = folder.resolve(".$name.${"%016x".format(Random.nextLong())}.new")

		val channel =
			try {
				FileChannel.open(beside, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
			} catch (taken: FileAlreadyExistsException) {
				null
			}

		return if (channel != null)
			beside to channel
		else
			made(folder, name)
	}

	/**
	 * [folder]'s own record flushed to the disk, so that the rename is kept as well as the file: where a folder
	 * cannot be opened to be flushed, the file is still whole, and which of the two it is after a power cut is the
	 * file system's to say.
	 */
	private fun flushed(folder: Path) {
		try {
			FileChannel.open(folder, StandardOpenOption.READ).use { it.force(true) }
		} catch (unsupported: IOException) {
			Unit
		}
	}
}
