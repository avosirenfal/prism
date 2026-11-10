package prism.index

import prism.linux.AtFlags
import prism.linux.Dev
import prism.linux.DirentBuffer
import prism.linux.Fd
import prism.linux.FileKind
import prism.linux.OpenFlags
import prism.linux.Stat
import prism.linux.Sys
import prism.linux.close
import prism.linux.openat
import prism.linux.statx
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

/**
 * One entry as a directory lists it: its name, what it is (null where nothing would say), and its inode; and, for a
 * symbolic link, what it leads to (null for a link that leads nowhere, or round in a loop).
 */
data class Listed(
	val name: Name,
	val kind: FileKind?,
	val ino: Long,
	val target: FileKind? = null,
)

/**
 * A path as the kernel names it: its bytes, which are what a system call is given. The JVM's own [path] is made from
 * them where the JVM can spell them, and is null where it cannot (a name that is not in the JVM's file name encoding),
 * so a directory the JVM cannot name is still one a scan can read and the index can hold. Absolute and normal: it
 * starts with `/`, and has no `.`, `..`, empty or trailing level.
 */
class Where internal constructor(
	internal val bytes: ByteArray,
) {
	init {
		require(bytes.isNotEmpty() && bytes[0] == SLASH) { "a place is an absolute path" }
	}

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
	 * and back), so two places compare and hash as their bytes do, whatever those bytes are, and order as their bytes
	 * do, unsigned.
	 */
	internal val key: String get() = String(bytes, Charsets.ISO_8859_1)

	val isRoot: Boolean get() = bytes.size == 1

	/** The last level's name: empty for the root. */
	internal val name: ByteArray get() =
		bytes.copyOfRange(
			bytes.lastIndexOf(SLASH) + 1,
			bytes.size,
		)

	/** The folder this is in; null for the root. */
	val parent: Where?
		get() =
			when {
				isRoot -> null
				bytes.lastIndexOf(SLASH) == 0 -> ROOT
				else -> Where(bytes.copyOf(bytes.lastIndexOf(SLASH)))
			}

	/** Each level's name, from the top: none for the root. */
	internal val levels: List<ByteArray>
		get() =
			(bytes.indices.filter { bytes[it] == SLASH } + bytes.size)
				.zipWithNext { from, to -> bytes.copyOfRange(from + 1, to) }
				.filter { it.isNotEmpty() }

	/** Whether this is [other] or under it. */
	fun within(other: Where): Boolean =
		other.isRoot ||
			(
				key.startsWith(other.key) &&
					(bytes.size == other.bytes.size || bytes[other.bytes.size] == SLASH)
			)

	/** [name], an entry of this directory. */
	fun child(name: Name): Where = child(name.bytes())

	internal fun child(name: ByteArray): Where =
		if (isRoot)
			Where(bytes + name)
		else
			Where(bytes + SLASH + name)

	override fun equals(other: Any?): Boolean =
		other is Where && bytes.contentEquals(other.bytes)

	override fun hashCode(): Int = bytes.contentHashCode()

	override fun toString(): String = bytes.decodeToString()

	companion object {
		private const val SLASH = '/'.code.toByte()

		val ROOT = Where(byteArrayOf(SLASH))

		/** [path], made absolute and normal. */
		fun of(path: Path): Where =
			Where(
				path
					.toAbsolutePath()
					.normalize()
					.toString()
					.encodeToByteArray(),
			)

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

							val kind = FileKind.ofMode(attributes["mode"] as Int)

							Listed(
								Name.of(entry.fileName.toString()),
								kind,
								attributes["ino"] as Long,
								if (kind == FileKind.Symlink)
									targetOf(entry)
								else
									null,
							)
						}
					}

				Listing.Read(devOf(own["dev"] as Long), own["ino"] as Long, entries)
			} catch (failed: IOException) {
				Listing.Unreadable(failed.toString())
			}
		}

		/** What the link at [link] leads to: a stat that follows it; null where it leads nowhere. */
		private fun targetOf(link: Path): FileKind? =
			try {
				FileKind.ofMode(Files.readAttributes(link, "unix:mode")["mode"] as Int)
			} catch (nowhere: IOException) {
				null
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
	 * (`DT_UNKNOWN`) is stat'ed, and a symbolic link, to learn what it leads to. Names are the kernel's bytes, so a name
	 * that is not UTF-8 is read as it is.
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
					val target =
						if (known == FileKind.Symlink)
							targetOf(fd, buffer.nameSegment(name, nameLength), scratch)
						else
							null

					read += Listed(Name.trusted(buffer.nameBytes(name, nameLength)), known, ino, target)
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

		/**
		 * What the link [name] in [dir] leads to: one statx that follows it, without mounting an automount point it
		 * leads to. Null where it leads nowhere.
		 */
		private fun targetOf(dir: Fd, name: MemorySegment, scratch: Scratch): FileKind? =
			when (val stat = statx(dir, name, AtFlags.NO_AUTOMOUNT, scratch.stat)) {
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

	/** Anything that is not a directory; for a symbolic link, with what it leads to (null: nowhere). */
	data class Leaf(
		override val name: Name,
		override val ino: Long,
		val kind: FileKind?,
		val target: FileKind? = null,
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
	/** One the rules leave out by its name or its path. */
	IgnoredDirectory,

	/** The mount point of a file system the rules leave out. */
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

/**
 * What a scan makes of each directory it reads: a value of the caller's, made from the leaves up, so that what a scan
 * keeps of a tree is whatever its caller keeps and no more. The index keeps a directory's entries packed into arrays;
 * [Scanner.scan] keeps a tree of [Node]s.
 */
interface Gathering<T> {
	/** What [where] gathers, read as [listing]: each of its entries is [found], a folder read with what it gathered. */
	fun read(where: Where, listing: Listing.Read, found: List<Found<T>>): T

	/** What [where] gathers where it could not be read: one the user may not read, or one gone since it was listed. */
	fun unreadable(where: Where, why: String): T
}

/** One entry of a directory read, as a [Gathering] is given it. */
sealed interface Found<out T> {
	val listed: Listed

	/** Anything that is not a directory. */
	data class Leaf(
		override val listed: Listed,
	) : Found<Nothing>

	/** A directory, read, and what it gathered. */
	data class Folder<out T>(
		override val listed: Listed,
		val gathered: T,
	) : Found<T>

	/** A directory the rules leave out, never read. */
	data class Skipped(
		override val listed: Listed,
		val why: Skip,
	) : Found<Nothing>
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

/** A tree of [Node]s, as [Scanner.scan] keeps it. */
private object NodeTree : Gathering<Done> {
	override fun read(where: Where, listing: Listing.Read, found: List<Found<Done>>): Done =
		Done.Listed(
			listing.dev,
			listing.ino,
			Contents.Read(
				found.map {
					nodeOf(it, listing.dev)
				},
			),
		)

	override fun unreadable(where: Where, why: String): Done =
		Done.Unread(Contents.Unreadable(why))

	private fun nodeOf(found: Found<Done>, dev: Dev): Node {
		val (name, kind, ino, target) = found.listed

		return when (found) {
			is Found.Leaf -> Node.Leaf(name, ino, kind, target)
			is Found.Skipped -> Node.Skipped(name, ino, found.why)
			is Found.Folder ->
				when (val done = found.gathered) {
					is Done.Listed -> Node.Folder(name, ino, done.dev, done.contents)
					// Gone, or not readable: still an entry, held as a folder that could not be read.
					is Done.Unread -> Node.Folder(name, ino, dev, done.contents)
				}
		}
	}
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

	/** The tree at [root], as a tree of [Node]s. */
	fun scan(root: Path): Scanned {
		val (done, elapsed) = measureTimedValue { gather(root, NodeTree) }

		return when (done) {
			is Done.Listed -> Scanned(root, done.dev, done.ino, done.contents, backend, elapsed)
			is Done.Unread -> Scanned(root, null, null, done.contents, backend, elapsed)
		}
	}

	/** What [gathering] makes of the tree at [root], a directory. */
	fun <T> gather(root: Path, gathering: Gathering<T>): T =
		gather(Where.of(root), gathering)

	/** What [gathering] makes of the tree at [root], a directory. */
	fun <T> gather(root: Where, gathering: Gathering<T>): T {
		val pool = ForkJoinPool(threads)

		try {
			return pool.invoke(Read(root, gathering))
		} finally {
			pool.shutdown()
		}
	}

	/** Why the directory at [where] is not read, if it is not. */
	private fun skipOf(where: Where): Skip? =
		when {
			rules.ignores(where) -> Skip.IgnoredDirectory
			where.key in ignoredMounts -> Skip.IgnoredFileSystem
			else -> null
		}

	/** One directory read, and its subdirectories read as tasks of their own. */
	private inner class Read<T>(
		private val where: Where,
		private val gathering: Gathering<T>,
	) : RecursiveTask<T>() {
		override fun compute(): T =
			when (val listing = backend.list(where)) {
				is Listing.Unreadable -> gathering.unreadable(where, listing.why)
				is Listing.Read -> gathering.read(where, listing, found(listing))
			}

		private fun found(listing: Listing.Read): List<Found<T>> {
			val folders = listing.entries.filter { it.kind == FileKind.Directory }
			val places = folders.associateWith { where.child(it.name) }
			val skips = places.mapValues { (_, place) -> skipOf(place) }
			val reads =
				places
					.filterKeys { skips[it] == null }
					.mapValues { (_, place) -> Read(place, gathering).fork() }

			return listing.entries.map { entry ->
				val skip = skips[entry]

				when {
					entry.kind != FileKind.Directory -> Found.Leaf(entry)
					skip != null -> Found.Skipped(entry, skip)
					else -> Found.Folder(entry, reads.getValue(entry).join())
				}
			}
		}
	}
}
