package prism.desktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import prism.platform.Os

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

/**
 * This builder's process given the environment a program started by prism has ([childEnvironment]) of the user's
 * session's ([SessionEnvironment]), and its program found on that session's `PATH`, where it is named and not
 * written as a path: Java looks for it on prism's own, which on a Mac is not the user's.
 */
fun ProcessBuilder.asChildOfPrism(): ProcessBuilder =
	apply {
		val given = childEnvironment(SessionEnvironment.variables)
		val program = command().firstOrNull()

		environment().clear()
		environment().putAll(given)
		program
			?.takeIf { '/' !in it }
			?.let(SessionEnvironment::which)
			?.let { command(listOf(it.toString()) + command().drop(1)) }
	}

/*
 * The environment of the user's session: what a program the user starts from a terminal has, which is what one
 * started by prism should have, and what prism asks whether a program is installed of.
 *
 * On Linux it is prism's own, which a desktop session gives every program it starts. On a Mac it is not: an
 * application started by the Dock, Finder or launchd (prism's daemon) is given launchd's environment, whose `PATH` is
 * `/usr/bin:/bin:/usr/sbin:/sbin`, and what the user's shell adds to it (Homebrew's `/opt/homebrew/bin`, nix-darwin's
 * `/run/current-system/sw/bin`) is added by the shell's own files, which only a shell reads. So on a Mac the user's
 * login shell is asked, once: started as a terminal starts it (`-l -i`), it prints its environment, and what it says
 * is taken over prism's own. With `PRISM_RESOLVING_ENVIRONMENT` set, so that a file of the shell's can pass over what
 * it would rather not do then; given a few seconds, and prism's own where it says nothing in them.
 */

/** The user's session's environment (read once, on a Mac by asking the user's login shell), and what it finds. */
object SessionEnvironment {
	/** How long the user's shell is given to say its environment. */
	private const val SECONDS = 5L

	private val read: Lazy<Map<String, String>> =
		lazy {
			val own = System.getenv()

			sessionOf(
				own,
				if (Os.current ==
					Os.MacOS
				)
					loginShellEnvironment(own, SECONDS)
				else
					null,
			)
		}

	/** Its variables. */
	val variables: Map<String, String> get() = read.value

	/** What [name] is in it; none where it is not set. */
	fun get(name: String): String? = variables[name]

	/** Where [program] is on its `PATH`; none where it is not there. */
	fun which(program: String): Path? = whichOn(variables["PATH"].orEmpty(), program)

	/** It read now, on a thread of its own, so that what asks for it later does not wait: as a daemon starts. */
	fun warm() {
		Thread.ofVirtual().start { read.value }
	}
}

/**
 * The session's environment, of prism's own ([own]) and what the user's shell says ([shell], where it said anything):
 * the shell's over prism's, but for what a shell has of its own and no program it starts should take from it.
 */
internal fun sessionOf(
	own: Map<String, String>,
	shell: Map<String, String>?,
): Map<String, String> = own + shell.orEmpty().filterKeys { it !in SHELLS_OWN }

/** What a shell sets of its own, which says nothing of the session: how deep it is, where it is, what it last ran. */
private val SHELLS_OWN = setOf("SHLVL", "PWD", "OLDPWD", "_", RESOLVING)

/** What a shell asked for its environment by prism is told, so that its files can tell. */
private const val RESOLVING = "PRISM_RESOLVING_ENVIRONMENT"

/** What comes before and after the environment a shell prints, which may print a greeting or a prompt besides. */
private const val MARK = "PRISM-ENVIRONMENT-7f3a"

/**
 * [printed], what a shell printed as it was asked for its environment, read: what `env -0` printed between the two
 * [MARK]s, each `NAME=value` ended by a NUL; none where the marks are not both there.
 */
internal fun environmentPrinted(printed: String): Map<String, String>? {
	val start = printed.indexOf(MARK).takeIf { it >= 0 } ?: return null
	val end = printed.indexOf(MARK, start + MARK.length).takeIf { it >= 0 } ?: return null

	return printed
		.substring(start + MARK.length, end)
		.split('\u0000')
		.mapNotNull { variable ->
			variable.indexOf('=').takeIf { it > 0 }?.let {
				variable.substring(0, it) to
					variable.substring(it + 1)
			}
		}.toMap()
}

/** Where [program] is among the folders of [path] (apart by colons), as a shell would find it; none where it is not. */
internal fun whichOn(path: String, program: String): Path? =
	if ('/' in program)
		Path.of(program).takeIf(::runnable)
	else
		path
			.split(':')
			.filter { it.isNotEmpty() }
			.map { Path.of(it, program) }
			.firstOrNull(::runnable)

private fun runnable(at: Path): Boolean = Files.isExecutable(at) && !Files.isDirectory(at)

/**
 * The environment the user's login shell has, asked of it as [own]'s `SHELL` names it (zsh, a Mac's own, where it
 * names none), given [seconds] to say it; none where it says nothing that can be read in them.
 */
internal fun loginShellEnvironment(
	own: Map<String, String>,
	seconds: Long,
): Map<String, String>? =
	try {
		val shell =
			own["SHELL"]?.takeIf { it.startsWith("/") && runnable(Path.of(it)) } ?: "/bin/zsh"

		val process =
			ProcessBuilder(
				shell,
				"-l",
				"-i",
				"-c",
				"printf %s $MARK; command /usr/bin/env -0; printf %s $MARK",
			).redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.apply {
					// Started from prism's own, as a terminal's shell starts from the session's.
					environment().clear()
					environment().putAll(own + (RESOLVING to "1"))
				}.start()

		val printed =
			CompletableFuture.supplyAsync {
				process.inputStream.readAllBytes().decodeToString()
			}

		if (process.waitFor(seconds, TimeUnit.SECONDS))
			environmentPrinted(printed.get(seconds, TimeUnit.SECONDS))
		else
			null.also {
				process.descendants().forEach { it.destroy() }
				process.destroy()
			}
	} catch (failed: IOException) {
		null
	} catch (failed: java.util.concurrent.ExecutionException) {
		null
	} catch (failed: java.util.concurrent.TimeoutException) {
		null
	}
