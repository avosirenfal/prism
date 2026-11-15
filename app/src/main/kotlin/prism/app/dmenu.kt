package prism.app

import prism.cli.Args
import prism.cli.Outcome
import prism.client.Caller
import prism.client.DmenuCommand
import prism.client.DmenuFormat
import prism.client.DmenuOptions
import prism.client.Request
import prism.launcher.Dmenu
import prism.launcher.DmenuAnswer

/*
 * Who answers `prism dmenu` (prism.client, dmenu.kt). The command itself reads the lines and asks; here is the prism
 * that is asked: it shows them in the launcher's window, in place of whatever that showed, and answers what was
 * chosen, when it is. Where no prism is running, this process is that prism, for as long as the choosing takes, and
 * keeps to itself meanwhile: it is the session's prism to nobody, since what it prints waits for its last window.
 */

/** [options] and the lines of [input], as the launcher shows them. */
internal fun dmenuOf(options: DmenuOptions, input: String): Dmenu =
	Dmenu(
		Dmenu.linesOf(input),
		placeholder = options.placeholder ?: Dmenu.PLACEHOLDER,
		query = options.query,
		section = options.section,
		title = options.title,
		footer = options.footer,
		redacted = options.redacted,
		width = options.width,
		height = options.height,
	)

/**
 * What `prism dmenu` prints for [answer], and its status: the line chosen, or where it is among the lines, as
 * [format] says; what was typed, where that is the answer and no line is; nothing, and dmenu's own status for it,
 * where nothing was chosen.
 */
internal fun printedFor(answer: DmenuAnswer, format: DmenuFormat): Outcome =
	when (answer) {
		is DmenuAnswer.Chose ->
			Outcome(
				when (format) {
					DmenuFormat.Data -> answer.line.text
					DmenuFormat.Index -> "${answer.line.at}"
				},
			)
		is DmenuAnswer.Typed ->
			Outcome(
				when (format) {
					DmenuFormat.Data -> answer.text
					DmenuFormat.Index -> "${DmenuFormat.NO_LINE}"
				},
			)
		DmenuAnswer.Dismissed -> Outcome("", DmenuCommand.DISMISSED)
	}

/** `prism dmenu`, as the Java prism has it: a running prism asked first, and the lines shown by this one otherwise. */
internal object DmenuServed :
	DmenuCommand(otherwise = ::shownHere),
	Served {
	override suspend fun serve(args: Args, caller: Caller, instance: Instance): Outcome {
		val options = options(args)
		// Read, and made ready to be looked through, here: on the asker's thread, and not the windows'.
		val asked = dmenuOf(options, caller.input.orEmpty())

		return printedFor(
			instance.windows.with { it.dmenu(asked, caller.activation) },
			options.format,
		)
	}
}

/**
 * [request], which is `prism dmenu`'s, shown by this process, in windows of its own that nobody else is given: what
 * it printed. Standard output is kept for the line chosen, whatever the toolkit says meanwhile.
 */
private fun shownHere(request: Request): Outcome =
	keepingStdout { opened(DmenuServed, request, apart = true) { Hosting() } }
