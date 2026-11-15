package prism.index

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import prism.linux.WholeFile

/**
 * A file system's tree, kept while the file system is not mounted: which it is, where it was mounted, when it was
 * last seen there, and how many entries of it are kept.
 */
data class Kept(
	val volume: VolumeId,
	val at: Where,
	val since: Instant,
	val entries: Int,
)

/**
 * The trees of file systems that are not mounted now, and which file system is mounted at each place the index holds
 * one. An index is of what is on the disk, and a file system that is unmounted is not: its entries leave the index,
 * and are found by no search. They are kept here instead of thrown away, each as the block the index held, with when
 * each entry was first seen, so that the file system mounted again (in the same place or another) is in the index at
 * once as it was, and only read again to see what changed while it was away.
 *
 * Kept in a folder beside the index's file, a tree a file (in the index's own format) with a few lines beside it that
 * say whose it is; or in memory alone, for an index with no file. What is kept is never part of the index's file, so
 * the index's file is what it always was. A tree that cannot be written or read is one that was not kept: the file
 * system is read from the disk when it is mounted, as it would have been.
 */
class Away private constructor(
	private val folder: Path?,
) {
	private val trees = HashMap<VolumeId, Pair<Kept, Block>>()

	/** The file systems whose trees are kept. */
	@get:Synchronized
	val kept: List<Kept>
		get() =
			folder?.let { stored(it).map { (_, kept) -> kept } }
				?: trees.values.map { it.first }

	/**
	 * [tree], which is [volume]'s as the index held it where it was mounted, kept from [at] on, in place of any
	 * kept of it before.
	 */
	@Synchronized
	fun keep(volume: VolumeId, tree: Block, at: Instant) {
		val kept = Kept(volume, tree.root, at, tree.size)

		if (folder == null) {
			trees[volume] = kept to tree

			return
		}
		try {
			Files.createDirectories(folder)
			IndexFile.write(treeOf(folder, volume), Index.of(setOf(tree.root), listOf(tree)))
			WholeFile.replace(
				noteOf(folder, volume),
				listOf(
					volume.text,
					hex(tree.root.bytes),
					at.epochSecond.toString(),
					tree.size.toString(),
				),
			)
		} catch (unwritten: IOException) {
			forget(volume)
		}
	}

	/**
	 * [volume]'s tree, rooted at [at], where it is mounted now, and no longer kept; null where none is kept of it.
	 */
	@Synchronized
	fun take(volume: VolumeId, at: Where): Block? {
		if (folder != null && !treeOf(folder, volume).exists())
			return null

		val tree =
			if (folder == null)
				trees[volume]?.second
			else
				(IndexFile.read(treeOf(folder, volume)) as? IndexRead.Read)
					?.index
					?.let { held -> held.roots.singleOrNull()?.let { held.tree(it) } }

		forget(volume)

		return tree?.let { Index.of(setOf(it.root), listOf(it)).tree(it.root, at) }
	}

	/** The trees kept since before [before], let go: which they were. */
	@Synchronized
	fun expire(before: Instant): List<Kept> =
		kept.filter { it.since.isBefore(before) }.onEach { forget(it.volume) }

	/** What was last noted, for an index with no file to note it beside. */
	private var noted: Map<Where, VolumeId> = emptyMap()

	/**
	 * Which file system is mounted at each place the index holds one, as it was last noted ([note]): what says whose
	 * tree is at a place once its file system is no longer there to ask.
	 */
	@Synchronized
	fun mounted(): Map<Where, VolumeId> {
		val file = (folder ?: return noted).resolve(MOUNTED)

		return try {
			file
				.takeIf { it.exists() }
				?.readLines()
				.orEmpty()
				.mapNotNull { line ->
					line.split('\t').takeIf { it.size == 2 }?.let { (place, volume) ->
						Where(unhex(place)) to VolumeId(volume)
					}
				}.toMap()
		} catch (unread: IOException) {
			emptyMap()
		}
	}

	/** [mounted] noted as which file system is at each place now. */
	@Synchronized
	fun note(mounted: Map<Where, VolumeId>) {
		noted = mounted
		if (folder == null)
			return
		try {
			WholeFile.replace(
				folder.resolve(MOUNTED),
				mounted.map { (place, volume) -> "${hex(place.bytes)}\t${volume.text}" },
			)
		} catch (unwritten: IOException) {
			Unit
		}
	}

	private fun forget(volume: VolumeId) {
		trees.remove(volume)
		folder?.let {
			treeOf(it, volume).deleteIfExists()
			noteOf(it, volume).deleteIfExists()
		}
	}

	companion object {
		private const val MOUNTED = "mounted"
		private const val TREE = ".tree"
		private const val NOTE = ".volume"

		/** What is kept beside the index's file [index]; in memory alone, for an index with none. */
		fun beside(index: Path?): Away =
			Away(index?.let { it.resolveSibling("${it.name}.away") })

		/** A file's name for [volume]: its id is anything at all, and a name is not. */
		private fun nameOf(volume: VolumeId): String =
			hex(MessageDigest.getInstance("SHA-256").digest(volume.text.toByteArray()).copyOf(16))

		private fun treeOf(folder: Path, volume: VolumeId): Path =
			folder.resolve(nameOf(volume) + TREE)

		private fun noteOf(folder: Path, volume: VolumeId): Path =
			folder.resolve(nameOf(volume) + NOTE)

		/** What [folder] keeps, by each tree's file: those whose note reads as one, and whose tree is there. */
		private fun stored(folder: Path): List<Pair<Path, Kept>> =
			folder
				.takeIf { it.isDirectory() }
				?.listDirectoryEntries("*$NOTE")
				.orEmpty()
				.mapNotNull { note ->
					val tree = note.resolveSibling(note.name.removeSuffix(NOTE) + TREE)
					val lines = runCatching { note.readLines() }.getOrNull()

					if (lines == null || lines.size < 4 || !tree.exists())
						null
					else
						runCatching {
							tree to
								Kept(
									VolumeId(lines[0]),
									Where(unhex(lines[1])),
									Instant.ofEpochSecond(lines[2].toLong()),
									lines[3].toInt(),
								)
						}.getOrNull()
				}

		private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

		private fun unhex(text: String): ByteArray =
			text
				.chunked(2)
				.map {
					it.toInt(16).toByte()
				}.toByteArray()
	}
}
