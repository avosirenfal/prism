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
 * terminal, and `shell`) it does, asking the session's prism for what it needs. Everything else, and everything
 * nobody answered, is the Java prism's, started with the same words, its input and output this program's own: to
 * whoever ran it, there is one prism, and this is how fast it starts.
 */

/** This program's words are to be the Java prism's after all: nothing here could do them. */
internal class HandedOver : RuntimeException(null, null, false, false)

/** The commands this program does itself, as prism's command line names them. */
internal val OWN =
	Group(
		"prism",
		"what the client does itself",
		listOf(
			Category(
				"searching",
				listOf(SearchCommand(otherwise = { throw HandedOver() }), ShellCommand),
			),
		),
	)

/**
 * What [words] come to, where they can be done without the Java prism: by this program, for its own commands, or by
 * the prism listening at [place]. Null where they cannot: the Java prism's, then.
 */
internal fun answered(words: List<String>, place: InstancePlace?, caller: Caller): Ran? {
	val asksForHelp = words.takeWhile { it != "--" }.any { it == "-h" || it == "--help" }

	return when {
		OWN.child(words.firstOrNull().orEmpty()) != null ->
			try {
				execute(OWN, words)
			} catch (handed: HandedOver) {
				null
			}
		// Help is the Java prism's to give, which knows every command; no need to ask who would only say so.
		asksForHelp || place == null -> null
		else ->
			((ask(place, Request(words, caller)) as? Asking.Answered)?.reply as? Reply.Done)?.ran
	}
}

/** The variable that names the Java prism, where it is not beside this program. */
const val JAVA_PRISM = "PRISM_JAVA"

/**
 * The Java prism this program hands its words to: the one [environment] names, or the one installed with this
 * program, which is `../libexec/prism/prism` from where this program is. Null where neither is there.
 */
internal fun javaPrism(environment: Map<String, String>, program: Path?): Path? =
	(
		environment[JAVA_PRISM]?.takeIf { it.isNotEmpty() }?.let(::Path)
			?: program?.parent?.parent?.resolve("libexec/prism/prism")
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

/** [words] run by the Java prism, as though it had been started in this program's place: its exit status. */
private fun handedOver(words: List<String>): Int {
	val java =
		javaPrism(System.getenv(), program())
			?: run {
				System.err.println(
					"prism: nobody answered, and there is no Java prism to do it: " +
						"name one with $JAVA_PRISM",
				)

				return NO_JAVA
			}

	val running =
		try {
			ProcessBuilder(listOf(java.toString()) + words).inheritIO().start()
		} catch (failed: IOException) {
			System.err.println("prism: $java could not be started (${failed.message ?: failed})")

			return NO_JAVA
		}

	// Ended itself (a signal sent to this program alone), it ends what it started.
	Runtime.getRuntime().addShutdownHook(Thread { running.destroy() })

	return running.waitFor()
}

/** The status of there being no Java prism to hand words to: a command that cannot be run, as a shell says it. */
private const val NO_JAVA = 127

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
	val ran = answered(words, InstancePlace.of(), Caller.here())

	if (ran == null) {
		out.flush()
		exitProcess(handedOver(words))
	}
	if (ran.out.isNotEmpty())
		println(ran.out)
	if (ran.err.isNotEmpty())
		System.err.println(ran.err)
	out.flush()
	exitProcess(ran.status)
}
