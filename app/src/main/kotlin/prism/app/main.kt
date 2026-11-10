package prism.app

import prism.cli.Category
import prism.cli.Group
import prism.cli.execute
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/** The command line: every command, in its category, in the order `prism --help` lists them. */
val PRISM =
	Group(
		"prism",
		"a file picker for Wayland's portals, over an index of every file on the machine that the kernel keeps current",
		listOf(
			Category("picking", listOf(PickCommand, SnapshotCommand)),
			Category("indexing", listOf(DaemonCommand, IndexCommand, ScanCommand)),
			Category("watching", listOf(ProbeGroup)),
		),
	)

/**
 * The command line [args] run: what a command prints goes to stdout, its help too, and a mistake in the words or a
 * problem with what the command was given to stderr. The output is UTF-8 whatever the platform's charset, and
 * buffered, flushed however the process ends.
 */
fun main(args: Array<String>) {
	val out =
		PrintStream(
			BufferedOutputStream(FileOutputStream(FileDescriptor.out), 1 shl 16),
			false,
			Charsets.UTF_8,
		)

	System.setOut(out)
	System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))

	val ran = execute(PRISM, args.toList())

	if (ran.out.isNotEmpty())
		println(ran.out)
	if (ran.err.isNotEmpty())
		System.err.println(ran.err)
	out.flush()
	exitProcess(ran.status)
}
