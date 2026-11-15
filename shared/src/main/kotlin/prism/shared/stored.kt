package prism.shared

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/*
 * A value kept in a file: settings, and what prism keeps of its own doing. Every such file is one of the types here,
 * and there is one way to change one, `update`, which is given the change and does everything else: it takes the
 * file's lock (among this process's threads and among processes), reads the file as it is at that moment, makes the
 * change to that, writes the result whole (WholeFile), and hands it to whoever holds the value. So nothing can write
 * a file without having read it, read it outside the lock it is written under, write half of one, or change it and
 * not say so; and two processes that keep the same file (the prism that is running, and a window run on its own) do
 * not undo each other's changes, though neither is told of them.
 *
 * - [Stored]: the value is held, and handed to its readers as it changes. It is read once, as it is opened.
 * - [OnDisk]: the value is not held: it is read each time it is asked for. For a file that is someone else's as much
 *   as prism's (`mimeapps.list`), and one prism writes for others to read.
 * - [Snapshot] (whole.kt): written whole and never read back, by the one writer that holds what it says.
 *
 * What a file's bytes say is its [Format]'s to know, and nothing else's.
 */

/** What reading a file gave. */
sealed interface Loaded<out T> {
	/** What the file holds. */
	data class Read<T>(
		val value: T,
	) : Parsed<T>

	/** No file. */
	data object Missing : Loaded<Nothing>

	/** A file that does not say what it should (written wrong by hand, damaged, of another version), and [why]. */
	data class Unreadable(
		val why: String,
	) : Parsed<Nothing>
}

/** What a file's bytes say: what a [Format] makes of them. A file that is there says something, or cannot be read. */
sealed interface Parsed<out T> : Loaded<T>

/** What was read, or [none] where there was no file or it could not be read. */
fun <T> Loaded<T>.orElse(none: T): T =
	when (this) {
		is Loaded.Read -> value
		Loaded.Missing, is Loaded.Unreadable -> none
	}

/** How a value is a file's bytes. */
interface Format<T> {
	/** What no file holds: nothing set, nothing kept. */
	val none: T

	/** What [bytes], a file's, say. It reads anything: what does not say a value is [Loaded.Unreadable]. */
	fun read(bytes: ByteArray): Parsed<T>

	/** [value] as a file's bytes: what [read] reads back as it. */
	fun written(value: T): ByteArray

	companion object {
		/** A file's text, as it is: UTF-8, and nothing where there is no file. */
		val TEXT: Format<String> =
			object : Format<String> {
				override val none = ""

				override fun read(bytes: ByteArray): Parsed<String> =
					Loaded.Read(bytes.toString(Charsets.UTF_8))

				override fun written(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
			}

		/**
		 * A value written a line a part ([written]) and [read] from the lines, each ended by a new line, as UTF-8.
		 * Lines are read leniently, by what reads them: one that says nothing is for [read] to leave out.
		 */
		fun <T> lines(
			none: T,
			read: (List<String>) -> T,
			written: (T) -> List<String>,
		): Format<T> =
			object : Format<T> {
				override val none = none

				override fun read(bytes: ByteArray): Parsed<T> =
					Loaded.Read(read(bytes.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }))

				override fun written(value: T): ByteArray =
					written(value).joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8)
			}
	}
}

/**
 * A value that is [file]'s, as its [format] reads and writes it, and is not held: [read] reads the file, each time.
 * It is changed by [update] alone, which keeps its [writers] apart as they can be.
 *
 * A file that cannot be read is kept as it was, beside itself with `.unreadable` after its name, before a change is
 * written over it, so nothing written by hand is lost.
 */
class OnDisk<T>(
	val file: Path,
	private val format: Format<T>,
	private val writers: Writers = Writers.Prisms,
) {
	/** What the file holds now. */
	fun read(): Loaded<T> =
		try {
			format.read(Files.readAllBytes(file))
		} catch (missing: NoSuchFileException) {
			Loaded.Missing
		} catch (failed: IOException) {
			Loaded.Unreadable(failed.message ?: failed.toString())
		}

	/**
	 * The file made to hold what [change] makes of what it holds now (of [Format.none], where it holds nothing that
	 * can be read): read, changed and written as one step, under the file's lock. What it then holds. Where the
	 * change fails, or the write does, the file is as it was.
	 */
	@Throws(IOException::class)
	fun update(change: T.() -> T): T = changed({ format.none }, change) {}

	/**
	 * [update], changing [otherwise] where the file holds nothing that can be read, and telling [then] what was
	 * written before the lock is let go: so that whoever holds the value is told of changes in the order they were
	 * written in.
	 */
	internal fun changed(otherwise: () -> T, change: T.() -> T, then: (T) -> Unit): T =
		WholeFile.locked(file, writers) {
			val was =
				when (val read = read()) {
					is Loaded.Read -> read.value
					Loaded.Missing -> otherwise()
					is Loaded.Unreadable -> otherwise().also { setAside() }
				}
			val next = was.change()

			WholeFile.replace(file, format.written(next))
			then(next)
			next
		}

	/** The file as it is, copied beside itself: it cannot be read, and is about to be written over. */
	private fun setAside() {
		try {
			Files.copy(
				file,
				file.resolveSibling("${file.fileName}.unreadable"),
				StandardCopyOption.REPLACE_EXISTING,
			)
		} catch (gone: IOException) {
			Unit
		}
	}
}

/**
 * A value kept in a file and held: what it is now is [value], which every reader collects, and it is changed by
 * [update] alone, which writes it and hands every reader the result. The file is read once, as this is made ([of]),
 * and after that only by [update], as part of writing a change; a process has one of these for a file, and nothing
 * else in it reads the file.
 *
 * One with no file ([inMemory]) is the same thing, kept nowhere: what a test is given, and a window run with nothing
 * to keep.
 */
class Stored<T> private constructor(
	private val disk: OnDisk<T>?,
	first: T,
	problem: String?,
	private val unwritten: (IOException) -> Unit,
) {
	private val state = MutableStateFlow(first)

	/** What it is now, and each thing it becomes. */
	val value: StateFlow<T> = state.asStateFlow()

	/** The file it is kept in; none, for one kept in memory alone. */
	val file: Path? get() = disk?.file

	/**
	 * Why the file could not be read, where it could not when it was last read: the value is then what it was
	 * before that ([Format.none], as this is made). Null where it could be read, or there was no file.
	 */
	@Volatile
	var problem: String? = problem
		private set

	/**
	 * The value made what [change] makes of it, kept, and handed to every reader, once: what it then is. With a
	 * file, the change is made to what the file holds now, which is this process's value unless another process has
	 * written to it since (or the file is gone, or cannot be read: then to what is held here).
	 *
	 * Where the file cannot be written, [unwritten] is told: where that throws (as it does unless this was made
	 * with another), nothing is changed; where it returns, the change is made to what is held here, and readers are
	 * handed it, so that what cannot be kept is still so until prism ends.
	 */
	fun update(change: T.() -> T): T {
		val disk = disk ?: return state.updateAndGet { it.change() }

		return try {
			disk.changed({ state.value }, change) {
				problem = null
				state.value = it
			}
		} catch (failed: IOException) {
			unwritten(failed)
			state.updateAndGet { it.change() }
		}
	}

	companion object {
		/**
		 * What [file] holds, as [format] reads it, held from now on and kept there as it changes. A file that is not
		 * there, or cannot be read, is [Format.none] (and, unreadable, a [problem]).
		 */
		fun <T> of(
			file: Path,
			format: Format<T>,
			unwritten: (IOException) -> Unit = { throw it },
		): Stored<T> {
			val disk = OnDisk(file, format)
			val read = disk.read()

			return Stored(
				disk,
				read.orElse(format.none),
				(read as? Loaded.Unreadable)?.why,
				unwritten,
			)
		}

		/** [first], held and kept nowhere. */
		fun <T> inMemory(first: T): Stored<T> = Stored(null, first, null) {}
	}
}
