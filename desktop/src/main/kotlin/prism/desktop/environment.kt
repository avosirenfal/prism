package prism.desktop

import java.nio.file.Path

/*
 * The environment of a program prism starts. A program started from a file manager is the user's, not the file
 * manager's: it should find the environment the user's session has, not one arranged for running prism. Two things
 * arrange prism's:
 *
 * - A launcher that changes a variable for prism's sake (a Nix package's, which puts the libraries prism loads on
 *   `LD_LIBRARY_PATH`) says what the variable was before, in `PRISM_RESTORE_` and the variable's name: empty where it
 *   was not set. A program prism starts is given it as it was.
 * - An app image's own launcher (jpackage's) adds the image's folder to `LD_LIBRARY_PATH`, and marks the process as
 *   one it started (`_JPACKAGE_LAUNCHER`), which another program packaged the same way, started with that mark,
 *   takes to mean it need not read its own configuration, and fails. Neither is passed on.
 */

/** The start of the name of a variable that holds what another was before prism's launcher changed it. */
const val RESTORE_PREFIX = "PRISM_RESTORE_"

/** The mark jpackage's launcher leaves on a process it started. */
private const val JPACKAGE_MARK = "_JPACKAGE_LAUNCHER"

/**
 * The environment a program started by prism is given, where prism's own is [own]: each variable a launcher changed
 * for prism put back as it was, and nothing of an app image's launcher ([appImage], the launcher's path, where prism
 * is run from one) passed on.
 */
fun childEnvironment(
	own: Map<String, String>,
	appImage: Path? = System.getProperty("jpackage.app-path")?.let(Path::of),
): Map<String, String> {
	val restored =
		own
			.filterKeys {
				it.startsWith(RESTORE_PREFIX)
			}.mapKeys { it.key.removePrefix(RESTORE_PREFIX) }

	// An app image's launcher is at bin/, and what it adds to the path is its lib/app.
	val imageLibraries =
		appImage
			?.parent
			?.parent
			?.resolve("lib/app")
			?.toString()

	return (own + restored)
		.filterKeys { !it.startsWith(RESTORE_PREFIX) && it != JPACKAGE_MARK }
		.mapValues { (name, value) ->
			if (name == "LD_LIBRARY_PATH" && imageLibraries != null && name !in restored)
				value.split(':').filter { it.isNotEmpty() && it != imageLibraries }.joinToString(":")
			else
				value
		}
		// Put back as not set, or left with nothing in it: not set.
		.filter { (name, value) ->
			value.isNotEmpty() ||
				(name !in restored && name != "LD_LIBRARY_PATH")
		}
}

/** This builder's process given the environment a program started by prism has ([childEnvironment]). */
fun ProcessBuilder.asChildOfPrism(): ProcessBuilder =
	apply {
		val given = childEnvironment(System.getenv())

		environment().clear()
		environment().putAll(given)
	}
