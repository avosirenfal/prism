package prism.index

import java.nio.file.Path
import prism.platform.Os

/*
 * Places the index holds though the rules leave out a folder above them: each read as a root of its own, which a scan
 * reads whatever the rules say, and hidden, so that a search finds what is in one only where it names the place
 * (Exclusion.Hidden). What a Mac's applications are is read from the index, and some of them are in its system's
 * tree, which is left out as a whole; those are read, and nothing else of the tree.
 */

/** A place the index holds, though a folder above it is left out: absolute, normal, and not the root. */
@ConsistentCopyVisibility
data class Included private constructor(
	val path: Path,
) {
	/** The rule that hides it from what a search finds. */
	internal val hidden: Naming get() = Naming.Folder(path)

	/** Whether it is under one of [roots], as the index holds them: what a daemon of those roots reads it as one of. */
	fun under(roots: Collection<Path>): Boolean =
		roots.any {
			path != it &&
				path.startsWith(it)
		}

	companion object {
		/** [path] as a place to include; none where it is not an absolute, normal path below the root. */
		fun of(path: Path): Included? =
			path
				.takeIf {
					it.isAbsolute && it == it.normalize() &&
						it.nameCount > 0
				}?.let(::Included)

		/**
		 * The places included on [os]. On a Mac, where applications are in its system's tree (/System, left out):
		 * the system's applications, with their Utilities, the applications kept with its services, and Finder.
		 */
		fun defaultsOn(os: Os): List<Included> =
			when (os) {
				Os.MacOS ->
					listOf(
						"/System/Applications",
						"/System/Applications/Utilities",
						"/System/Library/CoreServices/Applications",
						"/System/Library/CoreServices/Finder.app",
					).mapNotNull { of(Path.of(it)) }
				Os.Linux, Os.Windows -> emptyList()
			}

		/** The places included on the platform this runs on. */
		val DEFAULTS: List<Included> by lazy { defaultsOn(Os.current) }
	}
}
