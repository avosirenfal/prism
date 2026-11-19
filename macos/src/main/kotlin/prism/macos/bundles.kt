package prism.macos

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory
import kotlin.streams.asSequence

/*
 * The application bundles a Mac has, kept as the disk changes. They are what the index holds that is named as a bundle
 * is (`Something.app`), read whole as the index is; and from then on each FSEvents batch says which of them changed,
 * which came, and which went ([KeptBundles.after]). Nothing here reads what a bundle says of itself: what is kept is
 * which bundles there are and how often each has changed ([Revision]), and whoever lists them reads a bundle again
 * when its revision is new.
 */

/** An application's bundle, by where it is: a folder whose name ends in `.app`, at an absolute, normal path. */
@JvmInline
value class BundlePath private constructor(
	val path: Path,
) {
	/** Whether it is inside another bundle: an application's helper, kept in that application's Contents. */
	val inAnother: Boolean get() =
		generateSequence(path.parent) { it.parent }.any {
			of(it) !=
				null
		}

	override fun toString(): String = path.toString()

	companion object {
		/** [path] as a bundle's, where it is named as one; none where it is not. */
		fun of(path: Path): BundlePath? =
			path
				.takeIf {
					it.isAbsolute && it == it.normalize() &&
						it.fileName?.toString()?.endsWith(".app") == true
				}?.let(::BundlePath)

		/** The bundle [path] is, or is in: the nearest folder of it named as one; none where it is in none. */
		fun holding(path: Path): BundlePath? =
			generateSequence(path) { it.parent }.firstNotNullOfOrNull(::of)
	}
}

/** How often a bundle has changed since it was first kept, as one count for every bundle: a newer one is larger. */
@JvmInline
value class Revision(
	val number: Long,
) : Comparable<Revision> {
	fun next(): Revision = Revision(number + 1)

	override fun compareTo(other: Revision): Int = number.compareTo(other.number)

	companion object {
		val FIRST = Revision(0)
	}
}

/** What is looked at of the disk as bundles are kept: whether a bundle is there, and what bundles a folder holds. */
interface BundleDisk {
	/** Whether [bundle] is there, as a folder. */
	fun holds(bundle: BundlePath): Boolean

	/** The bundles in [folder] and under it, but not inside one another: what a folder moved in brings. */
	fun bundlesIn(folder: Path): List<BundlePath>

	companion object {
		/** How deep a folder moved in is looked through for bundles: `Games/Strategy/Something.app`. */
		private const val DEPTH = 4

		/** The disk itself. */
		val SYSTEM: BundleDisk =
			object : BundleDisk {
				override fun holds(bundle: BundlePath): Boolean = bundle.path.isDirectory()

				override fun bundlesIn(folder: Path): List<BundlePath> =
					try {
						Files
							.find(folder, DEPTH, { _, facts: BasicFileAttributes -> facts.isDirectory })
							.use { found ->
								found
									.asSequence()
									.mapNotNull(BundlePath::of)
									.filterNot { it.path != folder && BundlePath.holding(it.path.parent) != null }
									.toList()
							}
					} catch (unreadable: IOException) {
						emptyList()
					} catch (unreadable: java.io.UncheckedIOException) {
						emptyList()
					}
			}
	}
}

/**
 * The bundles kept, each with its [Revision]: changed by what is seen of the disk, and by nothing else. [latest] is the
 * revision last given, so that a bundle that changes is given a newer one than any it had.
 */
@ConsistentCopyVisibility
data class KeptBundles private constructor(
	val revisions: Map<BundlePath, Revision>,
	val latest: Revision,
) {
	val bundles: Set<BundlePath> get() = revisions.keys

	/**
	 * These, as the index holds them when it is read whole ([bundles]): every one of them given a new revision, since
	 * what changed while nothing was told (as the index was read again for changes it lost) cannot be known.
	 */
	fun read(bundles: Set<BundlePath>): KeptBundles =
		latest.next().let { now ->
			KeptBundles(bundles.associateWith { now }, now)
		}

	/** These, after each of [events], as [disk] is now. */
	fun after(events: List<FsEvent>, disk: BundleDisk): KeptBundles =
		events.fold(this) { kept, event -> kept.after(event, disk) }

	/**
	 * These after [event]: the bundle it is in, or is, given a new revision where it is there, and let go where it is
	 * not; the bundles under it that are no longer there let go (a folder of them taken away); and, where it is a
	 * folder in no bundle that was made or moved here, the bundles it holds.
	 */
	private fun after(event: FsEvent, disk: BundleDisk): KeptBundles {
		val holding = BundlePath.holding(event.path)

		val arrived =
			// What was made or moved here that is no bundle and in none: a folder, which may hold some.
			if (holding == null &&
				event.flags.any { it == FsFlag.Created || it == FsFlag.Renamed }
			)
				disk.bundlesIn(event.path)
			else
				emptyList()

		val gone =
			bundles
				.filter {
					it.path.startsWith(event.path) && it != holding &&
						!disk.holds(it)
				}.toSet()
		val changed = (listOfNotNull(holding) + arrived).toSet()
		val now = latest.next()

		return KeptBundles(
			(revisions - gone - changed) + changed.filter(disk::holds).associateWith { now },
			if (changed.isEmpty()) latest else now,
		)
	}

	companion object {
		/** No bundle kept. */
		val NONE = KeptBundles(emptyMap(), Revision.FIRST)
	}
}

/** What is known of a Mac's bundles: nothing yet, before the index is first read whole; or what is kept. */
sealed interface Catalogue {
	data object Unread : Catalogue

	data class Read(
		val kept: KeptBundles,
	) : Catalogue

	/** This, as the index holds [bundles] when it is read whole. */
	fun read(bundles: Set<BundlePath>): Catalogue =
		Read(
			when (this) {
				Unread -> KeptBundles.NONE
				is Read -> kept
			}.read(bundles),
		)

	/** This, after [events]: nothing yet, until the index is read; what is kept, moved on, after. */
	fun after(events: List<FsEvent>, disk: BundleDisk): Catalogue =
		when (this) {
			Unread -> Unread
			is Read -> Read(kept.after(events, disk))
		}
}
