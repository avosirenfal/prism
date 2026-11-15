package prism.client

import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.system.exitProcess
import prism.cli.Category
import prism.cli.Group
import prism.cli.Ran
import prism.cli.execute

/*
 * The prism a shell starts, as a program compiled ahead of time: it starts in a few milliseconds, where a Java
 * program takes a fifth of a second before it has read its first word, and most of what a shell asks prism for is
 * asked of the prism already running anyway (a window, the launcher, its status, a search).
 *
 * So it does only that: its words are asked of the session's prism, which reads them as its own command line and
 * answers what they printed; and what this program does itself (`search`, which may have a menu to show on this
 * terminal; `dmenu`, whose lines are on this program's standard input) it does, asking the session's prism for what
 * it needs. Everything else, and everything
 * nobody answered, is the Java prism's, started with the same words, its input and output this program's own: to
 * whoever ran it, there is one prism, and this is how fast it starts.
 */

/**
 * This program's words are to be the Java prism's after all: nothing here could do them. Where it has read its
 * standard input already, what it read is [input], which is then the Java prism's to read.
 */
internal class HandedOver(
	val input: String? = null,
) : RuntimeException(null, null, false, false)

/** What [answered] made of a command line: what it came to, or that it is the Java prism's, with what was read for it. */
internal sealed interface Done {
	data class Here(
		val ran: Ran,
	) : Done

	data class Handed(
		val input: String? = null,
	) : Done
}

/** The commands this program does itself, as prism's command line names them. */
internal val OWN =
	Group(
		"prism",
		"commands the native client runs itself",
		listOf(
			Category(
				"searching",
				listOf(SearchCommand(otherwise = { throw HandedOver() })),
			),
			Category(
				"browsing",
				listOf(DmenuCommand(otherwise = { throw HandedOver(it.caller.input) })),
			),
		),
	)

/**
 * What [words] come to, where they can be done without the Java prism: by this program, for its own commands, or by
 * the prism listening at [place]. Where they cannot, they are the Java prism's.
 */
internal fun answered(words: List<String>, place: InstancePlace?, caller: Caller): Done {
	val asksForHelp = words.takeWhile { it != "--" }.any { it == "-h" || it == "--help" }

	return when {
		OWN.child(words.firstOrNull().orEmpty()) != null ->
			try {
				Done.Here(execute(OWN, words))
			} catch (handed: HandedOver) {
				Done.Handed(handed.input)
			}
		// Help is the Java prism's to give, which knows every command; no need to ask who would only say so.
		asksForHelp || place == null -> Done.Handed()
		else ->
			((ask(place, Request(words, caller)) as? Asking.Answered)?.reply as? Reply.Done)
				?.let { Done.Here(it.ran) }
				?: Done.Handed()
	}
}

/**
 * Where a package puts the Java prism, from the folder its `bin` is in: beside the programs a person runs, and not
 * among them (the flake's package, nix/package.nix, which a test holds to this).
 */
const val JAVA_PRISM_INSTALLED = "libexec/prism/prism"

/** The variable that names the Java prism, where it is not beside this program. */
const val JAVA_PRISM = "PRISM_JAVA"

/**
 * The Java prism this program hands its words to: the one [environment] names, or the one installed with this
 * program ([JAVA_PRISM_INSTALLED], from the folder this program's is in). Null where neither is there.
 */
internal fun javaPrism(environment: Map<String, String>, program: Path?): Path? =
	(
		environment[JAVA_PRISM]?.takeIf { it.isNotEmpty() }?.let(::Path)
			?: program?.parent?.parent?.resolve(JAVA_PRISM_INSTALLED)
	)?.takeIf { Files.isExecutable(it) }

/** Where this program is, as the kernel has it. */
private fun program(): Path? =
	try {
		Path("/proc/self/exe").toRealPath()
	} catch (unknown: IOException) {
		ProcessHandle
			.current()
			.info()
			.command()
			.map(::Path)
			.orElse(null)
	}

/**
 * [words] run by the Java prism, as though it had been started in this program's place: its exit status. Its
 * standard input is this program's own, or, where this program has read that already, [input], which it read.
 */
private fun handedOver(words: List<String>, input: String?): Int {
	val java =
		javaPrism(System.getenv(), program())
			?: run {
				System.err.println(
					"prism: no running prism answered and the Java prism wasn't found; " +
						"set $JAVA_PRISM to its path",
				)

				return NO_JAVA
			}

	val running =
		try {
			ProcessBuilder(listOf(java.toString()) + words)
				.inheritIO()
				.apply {
					if (input != null)
						redirectInput(ProcessBuilder.Redirect.PIPE)
				}.start()
		} catch (failed: IOException) {
			System.err.println("prism: couldn't start $java (${failed.message ?: failed})")

			return NO_JAVA
		}

	if (input != null)
		try {
			running.outputStream.use { it.write(input.encodeToByteArray()) }
		} catch (gone: IOException) {
			// It ended without reading it all: what it says of itself, and its status, are what is left to tell.
		}

	// Ended itself (a signal sent to this program alone), it ends what it started.
	Runtime.getRuntime().addShutdownHook(Thread { running.destroy() })

	return running.waitFor()
}

/** The status of there being no Java prism to hand words to: a command that cannot be run, as a shell says it. */
private const val NO_JAVA = 127

/** The status of a mistake of prism's own, as the Java prism has it (`EX_SOFTWARE`). */
private const val FAILED = 70

fun main(args: Array<String>) {
	val out =
		PrintStream(
			BufferedOutputStream(FileOutputStream(FileDescriptor.out), 1 shl 16),
			false,
			Charsets.UTF_8,
		)

	System.setOut(out)
	System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))

	val words = args.toList()

	val ran =
		try {
			answered(words, InstancePlace.of(), Caller.here())
		} catch (failed: Throwable) {
			// A mistake of this program's own: said in full, with the status the Java prism gives its own.
			out.flush()
			System.err.println(
				"prism: prism ${words.joinToString(" ")} failed: " +
					failed.stackTraceToString().trimEnd(),
			)
			exitProcess(FAILED)
		}

	when (ran) {
		is Done.Handed -> {
			out.flush()
			exitProcess(handedOver(words, ran.input))
		}
		is Done.Here -> {
			if (ran.ran.out.isNotEmpty())
				println(ran.ran.out)
			if (ran.ran.err.isNotEmpty())
				System.err.println(ran.ran.err)
			out.flush()
			exitProcess(ran.ran.status)
		}
	}
}
