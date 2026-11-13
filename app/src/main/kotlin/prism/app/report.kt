package prism.app

import kotlin.system.exitProcess

/*
 * What goes wrong, said where it will be found. prism says it on standard error, in one shape: `prism: ` and what
 * failed, then where, in full. Run from a terminal, that is the terminal; run as a service, the journal
 * (`journalctl --user -u prism`). Nothing that fails is left unsaid, whether it was gone on from or not.
 *
 * Most of what goes wrong is gone on from: a window that fails is closed, and the rest go on (the explorer); a scan
 * that fails is read again (the daemon); a request that fails is answered that it did. What cannot be gone on from
 * ends the process, by the one way that lets what is held be written first: [exitProcess], which runs the hooks a
 * daemon writes its index from.
 */

/** The exit status of a prism that ended because something failed that it could not go on from. */
internal const val FAILED_STATUS = 70

internal object Report {
	/** [line], said as prism's own, on standard error. */
	fun say(line: String) {
		System.err.println("prism: $line")
	}

	/**
	 * [what] ("the window on ~/Documents failed", "writing the index failed"), with the [failure] in full. Where
	 * nothing can be gone on from after it ([isFatal]), prism ends, writing what it holds first.
	 */
	fun problem(what: String, failure: Throwable) {
		System.err.println("prism: $what: ${failure.stackTraceToString().trimEnd()}")
		if (isFatal(failure))
			ended("it cannot go on after that")
	}

	/** What a daemon reports ([prism.daemon.DaemonSettings.report]): what it was doing as it failed. */
	fun whileDoing(doing: String, failure: Throwable) = problem("$doing failed", failure)

	/** prism ended, saying [why], with what it holds written first (the shutdown hooks). */
	fun ended(why: String): Nothing {
		say("ending: $why")
		exitProcess(FAILED_STATUS)
	}

	/**
	 * Whether nothing can be relied on after [failure]: the virtual machine itself failing (memory run out, an error
	 * of its own), but not a stack run out, which is one computation's failure and leaves the rest as it was.
	 */
	fun isFatal(failure: Throwable): Boolean =
		generateSequence(failure) { it.cause }
			.take(MAX_CAUSES)
			.any { it is VirtualMachineError && it !is StackOverflowError }

	/**
	 * Whatever a thread throws that nothing caught, said as everything else is: the thread is gone, and the rest of
	 * prism goes on, unless nothing can ([isFatal]). Threads prism keeps going for as long as it runs catch what
	 * their work throws themselves; this is for what was not foreseen.
	 */
	fun install() {
		Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
			problem("the thread ${thread.name} failed", failure)
		}
	}

	/** How far down a failure's causes are looked at: further than any real one goes, and never round a loop. */
	private const val MAX_CAUSES = 32
}
