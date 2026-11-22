package prism.browser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.Fixtures.picker

/** What the status line says of what is chosen: how many, and what the files of them take. */
class StatusTest {
	private fun id(name: String) = RowId(name)

	private fun chosen(vararg names: String): BrowserState =
		after(
			picker(Choosing.Files()),
			BrowserEvent.Clicked(id(names.first()), Click.Only),
			*names.drop(1).map { BrowserEvent.Clicked(id(it), Click.Toggle) }.toTypedArray(),
		)

	@Test
	fun theFilesChosenAreSaidWithTheirSizesSummed_FoldersNotGoneInto() {
		// main.kt is 2,460 bytes and notes.md 6,250: 8,710 bytes in all.
		assertTrue(
			statusOf(
				chosen("main.kt", "notes.md"),
				waited = false,
			).endsWith("  ·  2 selected (8.5 KB)"),
			statusOf(chosen("main.kt", "notes.md"), waited = false),
		)
		// A folder chosen beside them is counted, and adds nothing to what they take.
		assertTrue(
			statusOf(
				chosen("main.kt", "notes.md", "Music"),
				waited = false,
			).endsWith("3 selected (8.5 KB)"),
		)
		// A folder alone takes nothing the line says.
		assertTrue(statusOf(chosen("Music"), waited = false).endsWith("  ·  1 selected"))
		assertTrue(
			statusOf(chosen("todo.txt"), waited = false).endsWith("1 selected (214 bytes)"),
		)
	}

	@Test
	fun whereAFilesSizeIsNotReadYetWhatIsSaidIsTheLeastTheyTake() {
		val sized = Fixtures.row("a.txt", 2_048)
		val unread = Fixtures.row("b.txt", 1).copy(size = null)
		val folder = Fixtures.row("c")
		val rows = Rows(listOf(sized, unread, folder))

		assertEquals(
			ChosenSize(2_048, partial = true),
			rows.chosenSize(setOf(sized.id, unread.id, folder.id)),
		)
		assertEquals("at least 2.0 KB", rows.chosenSize(setOf(sized.id, unread.id))!!.said)
		assertEquals("2.0 KB", rows.chosenSize(setOf(sized.id))!!.said)
		assertNull(rows.chosenSize(setOf(folder.id)))
		assertNull(rows.chosenSize(emptySet()))
	}
}
