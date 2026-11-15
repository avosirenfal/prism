package prism.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.cli.Invocation
import prism.cli.Outcome
import prism.cli.execute
import prism.cli.invocation
import prism.client.DmenuCommand
import prism.client.DmenuFormat
import prism.client.DmenuOptions
import prism.launcher.Dmenu
import prism.launcher.DmenuAnswer

/**
 * `prism dmenu`, as the prism that is asked: what it shows for what it was asked, and what it prints for what was
 * chosen. The asking is the client's (prism.client's tests), the window the explorer's and the launcher's (theirs).
 */
class DmenuServedTest {
	@Test
	fun whatIsShownIsTheLinesWithAnythingOnThemAndWhatTheOptionsSay() {
		val asked =
			dmenuOf(
				DmenuOptions(
					placeholder = "Window size...",
					query = "5",
					section = "Sizes ({count})",
					title = "Resize",
					width = 500,
					height = 300,
					footer = false,
					redacted = true,
				),
				"25%\n\n50%\n",
			)

		assertEquals(listOf("25%", "50%"), asked.lines.map { it.text })
		assertEquals(
			Dmenu(
				asked.lines,
				placeholder = "Window size...",
				query = "5",
				section = "Sizes ({count})",
				title = "Resize",
				footer = false,
				redacted = true,
				width = 500,
				height = 300,
			),
			asked,
		)
		// With nothing said, it is as the launcher is, with a word in its field.
		assertEquals(Dmenu(emptyList()), dmenuOf(DmenuOptions(), ""))
		assertEquals(Dmenu.PLACEHOLDER, dmenuOf(DmenuOptions(), "").placeholder)
	}

	@Test
	fun whatIsPrintedIsTheLineChosenOrWhereItIsOrWhatWasTypedAndNothingWhenPutAway() {
		val lines = Dmenu.linesOf("25%\n\n50%\n75%")
		val chose = DmenuAnswer.Chose(lines[2])

		assertEquals(Outcome("75%"), printedFor(chose, DmenuFormat.Data))
		// Where it is among the lines shown: the blank one is none of them.
		assertEquals(Outcome("2"), printedFor(chose, DmenuFormat.Index))
		assertEquals(Outcome("60%"), printedFor(DmenuAnswer.Typed("60%"), DmenuFormat.Data))
		assertEquals(Outcome("-1"), printedFor(DmenuAnswer.Typed("60%"), DmenuFormat.Index))
		assertEquals(
			Outcome("", DmenuCommand.DISMISSED),
			printedFor(DmenuAnswer.Dismissed, DmenuFormat.Data),
		)
		assertEquals(1, DmenuCommand.DISMISSED)
	}

	@Test
	fun itIsOneOfPrismsCommandsAndAnotherPrismDoesItForOne() {
		val help = execute(PRISM, listOf("dmenu", "--help")).out

		assertTrue(help.startsWith("usage: prism dmenu [OPTIONS]"), help)
		assertTrue("--placeholder" in help && "--password" in help, help)
		assertTrue("dmenu" in execute(PRISM, listOf("--help")).out)
		// Asked by another prism, it is done here, whatever its options: none of them asks for a prism of its own.
		assertTrue((DmenuServed as Served).run { !apart(parsed("-p", "Pick", "--no-footer")) })
	}

	private fun parsed(vararg words: String) =
		(invocation(DmenuServed, words.toList()) as Invocation.Run).args
}
