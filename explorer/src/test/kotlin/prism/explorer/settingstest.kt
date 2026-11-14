package prism.explorer

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.Action
import prism.browser.Bindings
import prism.browser.Chord
import prism.desktop.Place
import prism.desktop.TerminalChoice
import prism.preferences.ActionsFile
import prism.preferences.ColumnId
import prism.preferences.DateFormat
import prism.preferences.ListingKind
import prism.preferences.Look
import prism.preferences.Preferences
import prism.preferences.QuickAccess
import prism.preferences.Role
import prism.preferences.WindowKind

/**
 * The preferences window's edits: a chord given where one is awaited, and taken from whatever had it; the places pinned
 * moved, unpinned and pinned; the terminal's words; and only what was changed saved, over what the preferences are by
 * then.
 */
class SettingsTest {
	private fun chord(text: String) = Chord.parse(text) ?: error(text)

	private val home = Path.of("/home/avery")
	private val places =
		listOf(
			Place("Home", Place.Kind.Home, home),
			Place("Documents", Place.Kind.Documents, home.resolve("Documents")),
			Place("Downloads", Place.Kind.Downloads, home.resolve("Downloads")),
		)
	private val known = places.map { it.path }

	@Test
	fun aChordPressedWhereOneIsAwaitedIsBoundThereAndTakenFromWhateverHadIt() {
		val start = Rebinding(Bindings.DEFAULT)

		// Nothing awaited, nothing pressed is taken.
		assertEquals(start, start.pressed(chord("F5")))

		// Showing hidden files given F5, which reading again had.
		val given = start.awaiting(Slot(Action.ToggleHidden, 0)).pressed(chord("F5"))

		assertEquals(listOf(chord("F5")), given.bindings.of(Action.ToggleHidden))
		assertEquals(listOf(chord("Ctrl+R")), given.bindings.of(Action.Refresh))
		assertEquals(Took(chord("F5"), listOf(Action.Refresh)), given.took)
		assertNull(given.awaited)

		// A slot past the last adds a chord; a slot removed is gone.
		val added = given.awaiting(Slot(Action.ToggleHidden, 1)).pressed(chord("Mouse Middle"))

		assertEquals(
			listOf(chord("F5"), chord("Mouse Middle")),
			added.bindings.of(Action.ToggleHidden),
		)
		assertNull(added.took)
		assertEquals(
			listOf(chord("Mouse Middle")),
			added.removed(Slot(Action.ToggleHidden, 0)).bindings.of(Action.ToggleHidden),
		)

		// A move is bound to its key alone, and takes the key, whatever is held with it, from what had it.
		val moved = start.awaiting(Slot(Action.MoveDown, 0)).pressed(chord("Shift+J"))

		assertEquals(listOf(chord("J")), moved.bindings.of(Action.MoveDown))

		// Escape stops waiting, and changes nothing.
		assertEquals(start, start.awaiting(Slot(Action.Rename, 0)).cancelled())
	}

	@Test
	fun anActionResetHasItsDefaultsBackTakenFromWhateverHasThemMeanwhile() {
		val swapped =
			Rebinding(
				Bindings.DEFAULT,
			).awaiting(Slot(Action.ToggleHidden, 0)).pressed(chord("F5"))

		val reset = swapped.reset(Action.Refresh)

		assertEquals(Action.Refresh.defaults, reset.bindings.of(Action.Refresh))
		assertEquals(emptyList<Chord>(), reset.bindings.of(Action.ToggleHidden))
		assertEquals(Bindings.DEFAULT, reset.reset(Action.ToggleHidden).bindings)
		assertEquals(Bindings.DEFAULT, swapped.resetAll().bindings)
	}

	@Test
	fun onlyWhatWasChangedHereIsSavedOverThePreferencesAsTheyAreBySaving() {
		val base = Preferences(terminal = TerminalChoice.Named("foot"))
		val opened = Settings(base)

		assertFalse(opened.changed)

		// Meanwhile, quick access arranged from a window, and a window zoomed; and the bindings changed here.
		val elsewhere =
			base
				.arranging(QuickAccess.DEFAULT.pinning(home.resolve("Projects")))
				.copy(scales = base.scales.with(WindowKind.Properties, 1.5f))

		val rebound =
			opened.copy(
				rebinding = opened.rebinding.awaiting(Slot(Action.Refresh, 0)).pressed(chord("F6")),
			)

		assertTrue(rebound.changed)
		assertEquals(
			elsewhere.copy(bindings = mapOf("Refresh" to listOf("F6", "Ctrl+R"))),
			rebound.over(elsewhere),
		)

		// A new window's look, and the sidebar's size, changed here: those are written, and the window zoomed kept.
		val looked =
			opened.copy(
				listings =
					opened.listings.looking(
						Role.Explorer,
						ListingKind.Folder,
						Look().let {
							it.with(
								it.columns(ListingKind.Folder).showing(ColumnId.Size, false),
							)
						},
					),
				scales = opened.scales.copy(sidebar = 1.25f),
				dates = DateFormat.Iso,
			)

		val saved = looked.over(elsewhere)

		assertFalse(
			saved.listings
				.look(
					Role.Explorer,
					ListingKind.Folder,
				).columns(ListingKind.Folder)
				.isShown(ColumnId.Size),
		)
		assertEquals(1.25f, saved.scales.sidebar)
		assertEquals(1.5f, saved.scales.of(WindowKind.Properties))
		assertEquals(DateFormat.Iso, saved.dates)
		assertEquals(elsewhere.quickAccess, saved.quickAccess)

		// Applied, the window edits from what is applied: nothing changed, the page where it was.
		val applied = looked.copy(page = Page.Layout).applied(saved)

		assertFalse(applied.changed)
		assertEquals(Page.Layout, applied.page)
		assertEquals(saved, applied.base)

		// Automatic chosen, it is the desktop's own.
		assertEquals(
			TerminalChoice.Found,
			opened
				.copy(
					terminal = opened.terminal.picked(TerminalPick.Automatic),
				).over(base)
				.terminal,
		)
	}

	@Test
	fun theTerminalChosenIsTheChoiceSaved_AndCustomLinesThatCannotBeOneAreSaidSo() {
		assertEquals(TerminalChoice.Found, TerminalForm().choice())
		assertEquals(
			TerminalChoice.Named("ghostty"),
			TerminalForm(TerminalPick.Named("ghostty")).choice(),
		)

		val lines =
			TerminalForm()
				.typed(
					open = "ghostty --working-directory={folder}",
					run = "ghostty --working-directory={folder} -e {command}",
				).choice()

		assertTrue(
			lines is TerminalChoice.CommandLines && lines.opensInFolder && lines.runsCommands,
		)
		assertEquals(lines, TerminalForm.of(lines).choice())

		// Lines typed are kept while another terminal is chosen, to come back to.
		val typed = TerminalForm().typed(open = "ghostty --working-directory={folder}")

		assertEquals(
			typed.choice(),
			typed.picked(TerminalPick.Named("foot")).picked(TerminalPick.Custom).choice(),
		)

		// Custom with nothing written, or a line that cannot be filled in rightly: said to be wrong, and why.
		val empty = TerminalForm().picked(TerminalPick.Custom).choice()
		val noFolder = TerminalForm().typed(open = "ghostty --font-size=14").choice()

		assertTrue(empty is TerminalChoice.Unreadable && "none is written" in empty.why)
		assertTrue(noFolder is TerminalChoice.Unreadable && "{folder}" in noFolder.why)
		// A choice the file holds that cannot be read is kept while nothing is changed.
		assertEquals(noFolder, TerminalForm.of(noFolder).choice())
	}

	@Test
	fun aCommandsWordsAreSplitAsAShellWouldAndWrittenBackSoTheyReadTheSame() {
		assertEquals(
			listOf("foot", "--working-directory", "{folder}"),
			wordsOf("foot  --working-directory {folder}"),
		)
		assertEquals(
			listOf("kitty", "--title", "a shell", ""),
			wordsOf("""kitty --title "a shell" ''"""),
		)
		assertEquals(listOf("say", "it's \"so\""), wordsOf("""say "it's \"so\"""""))
		assertEquals(emptyList<String>(), wordsOf("   "))

		for (words in listOf(listOf("a b", "c"), listOf("it's", "\\", "\"q\""), listOf("")))
			assertEquals(words, wordsOf(textOf(words)), "$words")
	}

	@Test
	fun anActionWrittenWrongIsSaidWithWhyAndTheRestAreListed() {
		val written =
			ActionsFile.parse(
				"""{"actions": [
					{"id": "mine", "label": "Mine", "run": ["true"], "key": "F9"},
					{"id": "wrong", "label": "Wrong", "run": ["cp", "{nowhere}"]}
				]}""",
			)

		val settings = Settings(Preferences(actions = written.actions))

		assertEquals("Mine", settings.actions["mine"]?.label)
		assertEquals(listOf("wrong"), settings.actions.broken.map { it.id })
		assertTrue(
			"nothing fills “{nowhere}”" in
				settings.actions.broken
					.single()
					.why,
		)
		// What reads is bound like any of prism's own, by the chord it names until another is given it.
		assertTrue(
			settings.rebinding.bindings.actions
				.any { it.key == "action:mine" },
		)
	}
}
