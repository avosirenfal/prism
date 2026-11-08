package barion.index

import barion.linux.AtFlags
import barion.linux.Dev
import barion.linux.DirentBuffer
import barion.linux.Fd
import barion.linux.FileKind
import barion.linux.OpenFlags
import barion.linux.Stat
import barion.linux.Sys
import barion.linux.close
import barion.linux.openat
import barion.linux.statx
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.RecursiveTask
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/*
 * A scan: a directory tree read into an immutable tree of what it holds. How a directory is read is a [Backend]; the
 * traversal is the same for every backend, so two scans of one tree differ only in how they asked the kernel, which
 * is what the benchmark measures (docs/scanning.md).
 *
 * The two backends differ in one way that decides the cost. The JVM gives a directory's names alone (it reads the
 * kernel's `d_type` and drops it), so knowing which entries are directories costs one `stat` per entry. getdents64,
 * read here through FFM, gives each entry's kind and inode with its name, so a scan stats nothing it does not have to.
 */

/** What a directory holds, as one backend reads it. */
sealed interface Listing {
	/** The directory's own device and inode, and its entries. */
	data class Read(
		val dev: Dev,
		val ino: Long,
		val entries: List<Listed>,
	) : Listing

	/** A directory that could not be read: one the user may not read, or one gone since it was listed. */
	data class Unreadable(
		val why: String,
	) : Listing
}

/** One entry as a directory lists it: its name, what it is (null where nothing would say), and its inode. */
data class Listed(
	val name: Name,
	val kind: FileKind?,
	val ino: Long,
)

/**
 * Where a scan is: the bytes the kernel names a directory by, which are what a system call is given. The JVM's own
 * [path] is made from them where the JVM can spell them, and is null where it cannot (a name that is not in the JVM's
 * file name encoding), so a directory the JVM cannot name is still one the scan can read.
 */
class Where internal constructor(
	internal val bytes: ByteArray,
) {
	/** The path as the JVM names it, or null where it cannot. */
	val path: Path? by lazy {
		try {
			Path.of(bytes.decodeToString(throwOnInvalidSequence = true))
		} catch (unspellable: Exception) {
			null
		}
	}

	/**
	 * The bytes as a string that holds each byte as one character (ISO-8859-1, which maps every byte to a character
	 * and back), so two places compare and hash as their bytes do, whatever those bytes are.
	 */
	internal val key: String get() = String(bytes, Charsets.ISO_8859_1)

	/** [name], an entry of this directory. */
	fun child(name: Name): Where {
		val nameBytes = name.bytes()
		val joined =
			if (bytes.lastOrNull() == '/'.code.toByte())
				bytes + nameBytes
			else
				bytes + '/'.code.toByte() + nameBytes

		return Where(joined)
	}

	override fun toString(): String = bytes.decodeToString()

	companion object {
		fun of(path: Path): Where = Where(path.toString().encodeToByteArray())

		/** The key [path] has as a [Where], for comparing one with places a scan reaches. */
		internal fun keyOf(path: Path): String = of(path).key
	}
}

/** How a directory is read. */
sealed interface Backend {
	val name: String

	fun list(where: Where): Listing

	/**
	 * The JVM's own: a directory stream for the names, and a stat of each entry (`unix:mode,ino`, one `lstat`) for
	 * what it is. What a JVM indexer must do, since the JVM keeps a directory entry's kind to itself.
	 */
	data object Jvm : Backend {
		override val name: String get() = "jvm"

		override fun list(where: Where): Listing {
			val path = where.path ?: return Listing.Unreadable("the JVM cannot name $where")

			return try {
				val own = Files.readAttributes(path, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS)

				val entries =
					Files.newDirectoryStream(path).use { stream ->
						stream.map { entry ->
							val attributes =
								Files.readAttributes(
									entry,
									"unix:mode,ino",
									LinkOption.NOFOLLOW_LINKS,
								)

							Listed(
								Name.of(entry.fileName.toString()),
								FileKind.ofMode(attributes["mode"] as Int),
								attributes["ino"] as Long,
							)
						}
					}

				Listing.Read(devOf(own["dev"] as Long), own["ino"] as Long, entries)
			} catch (failed: IOException) {
				Listing.Unreadable(failed.toString())
			}
		}

		/** The JVM's `unix:dev`, which is glibc's encoding of a device number: its `gnu_dev_major` and `gnu_dev_minor`. */
		private fun devOf(dev: Long): Dev {
			val major = ((dev ushr 8) and 0xFFF) or ((dev ushr 32) and 0xFFFFF000L)
			val minor = (dev and 0xFF) or ((dev ushr 12) and 0xFFFFFF00L)

			return Dev(major.toInt(), minor.toInt())
		}
	}

	/**
	 * getdents64 through FFM: a directory opened relative to nothing, read a buffer at a time, each entry's kind and
	 * inode taken from what the kernel wrote beside its name. Only an entry whose file system does not say what it is
	 * (`DT_UNKNOWN`) is stat'ed. Names are the kernel's bytes, so a name that is not UTF-8 is read as it is.
	 */
	data object Getdents : Backend {
		override val name: String get() = "getdents"

		/** Each thread's buffers, reused from directory to directory. */
		private val scratch = ThreadLocal.withInitial { Scratch(Arena.ofAuto()) }

		private class Scratch(
			arena: Arena,
		) {
			val entries = DirentBuffer(arena)
			val stat: MemorySegment = arena.allocate(256, 8)

			/** A path as a C string: PATH_MAX bytes and its NUL. */
			val path: MemorySegment = arena.allocate(PATH_MAX + 1L)
			val empty: MemorySegment = arena.allocate(1)
		}

		override fun list(where: Where): Listing {
			val scratch = scratch.get()

			if (where.bytes.size > PATH_MAX)
				return Listing.Unreadable("ENAMETOOLONG: ${where.bytes.size} bytes")

			MemorySegment.copy(where.bytes, 0, scratch.path, JAVA_BYTE, 0, where.bytes.size)
			scratch.path.set(JAVA_BYTE, where.bytes.size.toLong(), 0)

			val fd =
				when (val opened = openat(Fd.CWD, scratch.path, OpenFlags.DIRECTORY_FOR_READING)) {
					is Sys.Ok -> opened.value
					is Sys.Failed -> return Listing.Unreadable(opened.toString())
				}

			try {
				val own =
					when (val stat = statx(fd, scratch.empty, AtFlags.EMPTY_PATH, scratch.stat)) {
						is Sys.Ok -> stat.value
						is Sys.Failed -> return Listing.Unreadable(stat.toString())
					}

				return Listing.Read(
					own.dev,
					own.ino,
					entries(fd, scratch) ?: return Listing.Unreadable("getdents64"),
				)
			} finally {
				close(fd)
			}
		}

		/** Every entry of [fd], or null where reading it failed partway. */
		private fun entries(fd: Fd, scratch: Scratch): List<Listed>? {
			val read = ArrayList<Listed>()
			val buffer = scratch.entries

			while (true) {
				val length =
					when (val filled = buffer.fill(fd)) {
						is Sys.Ok -> filled.value
						is Sys.Failed -> return null
					}

				if (length == 0)
					return read

				buffer.forEach(length) { ino, kind, name, nameLength ->
					val known = kind ?: kindOf(fd, buffer.nameSegment(name, nameLength), scratch)

					read += Listed(Name.trusted(buffer.nameBytes(name, nameLength)), known, ino)
				}
			}
		}

		/** What an entry is, where its file system did not say: one statx, relative to its directory. */
		private fun kindOf(dir: Fd, name: MemorySegment, scratch: Scratch): FileKind? =
			when (
				val stat: Sys<Stat> =
					statx(
						dir,
						name,
						AtFlags.SYMLINK_NO_FOLLOW,
						scratch.stat,
					)
			) {
				is Sys.Ok -> stat.value.kind
				is Sys.Failed -> null
			}

		private const val PATH_MAX = 4096
	}

	companion object {
		val ALL: List<Backend> = listOf(Getdents, Jvm)
	}
}

/** One entry of a scanned tree. */
sealed interface Node {
	val name: Name
	val ino: Long

	/** Anything that is not a directory. */
	data class Leaf(
		override val name: Name,
		override val ino: Long,
		val kind: FileKind?,
	) : Node

	data class Folder(
		override val name: Name,
		override val ino: Long,
		val dev: Dev,
		val contents: Contents,
	) : Node

	/** A directory the rules leave out: held by name, so a view can show it, and never read. */
	data class Skipped(
		override val name: Name,
		override val ino: Long,
		val why: Skip,
	) : Node
}

/** Why a directory was not read. */
enum class Skip {
	IgnoredName,
	IgnoredFileSystem,
}

/** What a scanned directory holds. */
sealed interface Contents {
	data class Read(
		val entries: List<Node>,
	) : Contents

	data class Unreadable(
		val why: String,
	) : Contents
}

/** How many of each a tree holds: worked out from the tree, never counted beside it. */
data class Counts(
	val directories: Long,
	val files: Long,
	val symlinks: Long,
	val others: Long,
	val unreadable: Long,
	val skipped: Long,
) {
	val entries: Long get() = directories + files + symlinks + others + skipped

	operator fun plus(other: Counts) =
		Counts(
			directories + other.directories,
			files + other.files,
			symlinks + other.symlinks,
			others + other.others,
			unreadable + other.unreadable,
			skipped + other.skipped,
		)

	companion object {
		val NONE = Counts(0, 0, 0, 0, 0, 0)

		fun of(contents: Contents): Counts =
			when (contents) {
				is Contents.Unreadable -> NONE.copy(unreadable = 1)
				is Contents.Read ->
					contents.entries.fold(
						NONE,
					) { counted, node -> counted + of(node) }
			}

		fun of(node: Node): Counts =
			when (node) {
				is Node.Folder -> NONE.copy(directories = 1) + of(node.contents)
				is Node.Skipped -> NONE.copy(skipped = 1)
				is Node.Leaf ->
					when (node.kind) {
						FileKind.Regular -> NONE.copy(files = 1)
						FileKind.Symlink -> NONE.copy(symlinks = 1)
						else -> NONE.copy(others = 1)
					}
			}
	}
}

/** What reading one directory gave: its own device and inode with what it holds, or why it could not be read. */
private sealed interface Done {
	val contents: Contents

	data class Listed(
		val dev: Dev,
		val ino: Long,
		override val contents: Contents,
	) : Done

	data class Unread(
		override val contents: Contents,
	) : Done
}

/** A directory tree as a scan read it, and how long the scan took. */
class Scanned(
	val root: Path,
	val dev: Dev?,
	val ino: Long?,
	val contents: Contents,
	val backend: Backend,
	val elapsed: Duration,
) {
	val counts: Counts by lazy { Counts.of(contents) }
}

/**
 * Reads a directory tree with [backend], in parallel over [threads], leaving out what [rules] leave out. Each
 * directory is a task of its own, so the threads share the tree as they find it, as fd-find's do.
 */
class Scanner(
	val backend: Backend,
	val rules: IgnoreRules = IgnoreRules.DEFAULT,
	val threads: Int = Runtime.getRuntime().availableProcessors(),
	/** Mount points not to go into: the file systems [rules] leave out, as they are mounted now. */
	ignoredMounts: Set<Path> = Mounts.ignored(rules),
) {
	private val ignoredMounts: Set<String> = ignoredMounts.map(Where::keyOf).toSet()

	init {
		require(threads > 0) { "a scan runs on at least one thread" }
	}

	fun scan(root: Path): Scanned {
		val pool = ForkJoinPool(threads)

		try {
			val (listing, elapsed) =
				measureTimedValue {
					pool.invoke(Read(Where.of(root.toAbsolutePath().normalize())))
				}

			return when (listing) {
				is Done.Listed ->
					Scanned(
						root,
						listing.dev,
						listing.ino,
						listing.contents,
						backend,
						elapsed,
					)
				is Done.Unread -> Scanned(root, null, null, listing.contents, backend, elapsed)
			}
		} finally {
			pool.shutdown()
		}
	}

	/** One directory read, and its subdirectories read as tasks of their own. */
	private inner class Read(
		private val where: Where,
	) : RecursiveTask<Done>() {
		override fun compute(): Done =
			when (val listing = backend.list(where)) {
				is Listing.Unreadable -> Done.Unread(Contents.Unreadable(listing.why))
				is Listing.Read ->
					Done.Listed(
						listing.dev,
						listing.ino,
						Contents.Read(nodes(listing)),
					)
			}

		private fun nodes(listing: Listing.Read): List<Node> {
			val folders =
				listing.entries
					.filter { it.kind == FileKind.Directory && skip(it) == null }
					.associateWith { Read(where.child(it.name)).fork() }

			return listing.entries.map { entry ->
				val skip = skip(entry)
				val task = folders[entry]

				when {
					entry.kind != FileKind.Directory -> Node.Leaf(entry.name, entry.ino, entry.kind)
					skip != null -> Node.Skipped(entry.name, entry.ino, skip)
					else ->
						when (val done = task!!.join()) {
							is Done.Listed -> Node.Folder(entry.name, entry.ino, done.dev, done.contents)
							// Gone, or not readable: still an entry, held as a folder that could not be read.
							is Done.Unread -> Node.Folder(entry.name, entry.ino, listing.dev, done.contents)
						}
				}
			}
		}

		private fun skip(entry: Listed): Skip? =
			when {
				entry.kind != FileKind.Directory -> null
				rules.ignores(entry.name) -> Skip.IgnoredName
				where.child(entry.name).key in ignoredMounts -> Skip.IgnoredFileSystem
				else -> null
			}
	}
}
