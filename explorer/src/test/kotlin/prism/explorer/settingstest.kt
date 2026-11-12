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
import prism.preferences.Preferences

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

		// Search everything given F5, which reading again had.
		val given = start.awaiting(Slot(Action.SearchIndex, 0)).pressed(chord("F5"))

		assertEquals(listOf(chord("F5")), given.bindings.of(Action.SearchIndex))
		assertEquals(listOf(chord("Ctrl+R")), given.bindings.of(Action.Refresh))
		assertEquals(Took(chord("F5"), listOf(Action.Refresh)), given.took)
		assertNull(given.awaited)

		// A slot past the last adds a chord; a slot removed is gone.
		val added = given.awaiting(Slot(Action.SearchIndex, 1)).pressed(chord("Mouse Middle"))

		assertEquals(
			listOf(chord("F5"), chord("Mouse Middle")),
			added.bindings.of(Action.SearchIndex),
		)
		assertNull(added.took)
		assertEquals(
			listOf(chord("Mouse Middle")),
			added.removed(Slot(Action.SearchIndex, 0)).bindings.of(Action.SearchIndex),
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
			).awaiting(Slot(Action.SearchIndex, 0)).pressed(chord("F5"))

		val reset = swapped.reset(Action.Refresh)

		assertEquals(Action.Refresh.defaults, reset.bindings.of(Action.Refresh))
		assertEquals(emptyList<Chord>(), reset.bindings.of(Action.SearchIndex))
		assertEquals(Bindings.DEFAULT, reset.reset(Action.SearchIndex).bindings)
		assertEquals(Bindings.DEFAULT, swapped.resetAll().bindings)
	}

	@Test
	fun onlyWhatWasChangedHereIsSavedOverThePreferencesAsTheyAreBySaving() {
		val base = Preferences(terminal = TerminalChoice.Named("foot"))
		val opened = Settings(base, places)

		assertFalse(opened.changed)
		assertEquals(known, opened.pins)
		assertEquals(
			listOf("Home", "Documents", "x"),
			listOf(home, home.resolve("Documents"), home.resolve("x")).map(opened::labelOf),
		)

		// Meanwhile, a place pinned from a window, and the bindings changed here.
		val elsewhere = base.pinning(listOf(home.resolve("Projects")))

		val rebound =
			opened.copy(
				rebinding = opened.rebinding.awaiting(Slot(Action.Refresh, 0)).pressed(chord("F6")),
			)

		assertTrue(rebound.changed)
		assertEquals(
			elsewhere.copy(bindings = mapOf("Refresh" to listOf("F6", "Ctrl+R"))),
			rebound.over(elsewhere),
		)

		// The pins changed here too: these are the pins then.
		val repinned = rebound.unpinned(1).moved(1, -1)

		assertEquals(listOf(home.resolve("Downloads"), home), repinned.pins)
		assertEquals(repinned.pins, repinned.over(elsewhere).pinned)

		// Pins of the user's, made the desktop's own again: written as none.
		val own = Settings(base.pinning(known), places).copy(pinned = null)

		assertNull(own.over(elsewhere).pinned)
		// Pinning what is pinned changes nothing.
		assertEquals(opened.pins, opened.pinned(home).pins)
		assertEquals(
			known.plusElement(home.resolve("x")),
			opened.pinned(home.resolve("x")).pins,
		)

		// The terminal emptied is the desktop's own.
		assertEquals(
			TerminalChoice.Found,
			opened.copy(terminal = TerminalForm(name = "  ")).over(base).terminal,
		)
	}

	@Test
	fun theTerminalsFieldsMakeTheChoiceTheyShowAndOneThatCannotBeIsSaidSo() {
		assertEquals(TerminalChoice.Found, TerminalForm().choice())
		assertEquals(TerminalChoice.Named("ghostty"), TerminalForm(name = "ghostty").choice())

		val lines =
			TerminalForm(
				open = "ghostty --working-directory={folder}",
				run = "ghostty --working-directory={folder} -e {command}",
			).choice()

		assertTrue(
			lines is TerminalChoice.CommandLines && lines.opensInFolder && lines.runsCommands,
		)
		assertEquals(lines, TerminalForm.of(lines).choice())

		// A name and lines both, or a line that cannot be filled in rightly: said to be wrong, and why.
		val both =
			TerminalForm(
				name = "ghostty",
				open = "ghostty --working-directory={folder}",
			).choice()

		val noFolder = TerminalForm(open = "ghostty --font-size=14").choice()

		assertTrue(both is TerminalChoice.Unreadable && "not both" in both.why)
		assertTrue(noFolder is TerminalChoice.Unreadable && "{folder}" in noFolder.why)
		// A choice the file holds that cannot be read is kept while nothing is typed over it.
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

		val settings = Settings(Preferences(actions = written.actions), emptyList())

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
