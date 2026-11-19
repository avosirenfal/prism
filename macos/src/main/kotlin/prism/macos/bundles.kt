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
	/** Where it is: in no other bundle, inside one, or the bundle another wraps. */
	val placement: Placement
		get() =
			path.parent?.let { parent ->
				parent
					.takeIf { it.fileName?.toString() == WRAPPER }
					?.parent
					?.let(::of)
					?.let(Placement::WrappedBy)
					?: holding(parent)?.let(Placement::Within)
			} ?: Placement.Alone

	/**
	 * Whether the application of this bundle, run as [policy] says, is one a person starts, and so listed: one in no
	 * other bundle that can show a window; one inside another only where it is an application in the Dock (Xcode's
	 * Simulator), and not an accessory, as an application's helpers are; and the bundle another wraps never, since it
	 * is the wrapper that is opened.
	 */
	fun startedAs(policy: ActivationPolicy): Boolean =
		when (placement) {
			Placement.Alone -> policy != ActivationPolicy.Prohibited
			is Placement.Within -> policy == ActivationPolicy.Regular
			is Placement.WrappedBy -> false
		}

	override fun toString(): String = path.toString()

	companion object {
		/** The folder a wrapper keeps the bundle it wraps in, as CoreFoundation names it. */
		private const val WRAPPER = "Wrapper"

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

/** Where a bundle is, which is what says, with how it runs, whether it is an application a person starts. */
sealed interface Placement {
	/** In no other bundle: `/Applications/Safari.app`. */
	data object Alone : Placement

	/**
	 * Inside [outer]'s contents: an application's helper (`Google Chrome Helper.app`), its login item, or an
	 * application it brings with it (`Xcode.app/Contents/Developer/Applications/Simulator.app`).
	 */
	data class Within(
		val outer: BundlePath,
	) : Placement

	/**
	 * The bundle [wrapper] wraps, in its `Wrapper` folder: an iPhone's or iPad's application installed on a Mac
	 * (`Something.app/Wrapper/Something.app`), whose wrapper is what Launch Services opens, and whose Info.plist the
	 * wrapper is read by, through its `WrappedBundle` link.
	 */
	data class WrappedBy(
		val wrapper: BundlePath,
	) : Placement
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
