package prism.browser

import java.awt.event.KeyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.preferences.Preferences

/**
 * The bindings: a chord written as it is shown and read back as it was; where each action acts (a window's from
 * anywhere only for a chord no text field types); the user's preferences in place of the defaults; and every chord
 * doing one thing.
 */
class BindingsTest {
	private fun chord(text: String) = Chord.parse(text) ?: error("“$text” is not a chord")

	@Test
	fun aChordIsWrittenAsItIsShownAndReadBackAsItWas() {
		assertEquals(
			Chord(Trigger.Key(KeyEvent.VK_F), ctrl = true, shift = true),
			chord("Ctrl+Shift+F"),
		)
		assertEquals("Ctrl+Shift+F", chord("shift+ctrl+f").toString())
		assertEquals("PageUp", chord("PageUp").toString())
		assertEquals("Backspace", chord("Backspace").toString())
		assertEquals("Mouse Back", chord("Mouse Back").toString())
		assertEquals(KeyEvent.VK_ADD, (chord("Ctrl+Add").trigger as Trigger.Key).code)
		assertNull(Chord.parse("Ctrl+NoSuchKey"))
		assertNull(Chord.parse("Hyper+A"))

		// Every default reads back as itself.
		for (action in Action.entries)
			for (default in action.defaults)
				assertEquals(default, chord(default.toString()), "$action")
	}

	@Test
	fun aWindowsActionActsFromAnywhereOnlyForAChordNoTextFieldTypes() {
		val bindings = Bindings.DEFAULT

		assertEquals(Action.SearchIndex, bindings.window(chord("Ctrl+Shift+F")))
		assertEquals(Action.Refresh, bindings.window(chord("F5")))
		assertEquals(Action.Back, bindings.window(chord("Mouse Back")))
		// Backspace is the folder above from the listing, but in a text field it deletes a character.
		assertNull(bindings.window(chord("Backspace")))
		assertEquals(Action.Up, bindings.listing(chord("Backspace")))
		// Ctrl+C copies files from the listing; a text field copies its text.
		assertNull(bindings.window(chord("Ctrl+C")))
		assertEquals(Action.CopyFiles, bindings.listing(chord("Ctrl+C")))
		// Escape is left to whatever has the keyboard first.
		assertNull(bindings.window(chord("Escape")))
		assertEquals(Action.Cancel, bindings.leftover(chord("Escape")))
		// The cursor moves by its key, Shift and Ctrl saying how it chooses.
		assertEquals(Action.MoveDown, bindings.movement(chord("Shift+Down")))
		assertEquals(Action.First, bindings.movement(chord("Ctrl+Home")))
		// A row's own Shift chord before the move it would otherwise be.
		assertEquals(Action.Erase, bindings.listing(chord("Shift+Delete")))
	}

	@Test
	fun theUsersBindingsTakeTheDefaultsPlaceAndOnlyTheDifferencesAreKept() {
		val preferences =
			Preferences.DEFAULT
				.binding(Action.SearchIndex.name, listOf("Ctrl+Space"))
				.binding(Action.Back.name, listOf("Mouse Back"))
				.binding(Action.Refresh.name, emptyList())
				.binding("NoLongerAnAction", listOf("Ctrl+Q"))
		val bindings = Bindings.of(preferences)

		assertEquals(Action.SearchIndex, bindings.window(chord("Ctrl+Space")))
		assertNull(bindings.window(chord("Ctrl+Shift+F")))
		assertNull(bindings.window(chord("Alt+Left")))
		assertNull(bindings.window(chord("F5")))
		assertEquals(Action.Forward, bindings.window(chord("Alt+Right")))
		assertEquals(
			mapOf(
				"SearchIndex" to listOf("Ctrl+Space"),
				"Back" to listOf("Mouse Back"),
				"Refresh" to emptyList<String>(),
			),
			bindings.differences(),
		)
		assertEquals(emptyMap<String, List<String>>(), Bindings.DEFAULT.differences())
	}

	@Test
	fun aChordDoesOneThing() {
		val bindings = Bindings.DEFAULT

		assertEquals(listOf(Action.Refresh), bindings.clashes(Action.SearchIndex, chord("F5")))
		assertTrue(bindings.clashes(Action.Refresh, chord("F5")).isEmpty())
		// A move claims its key with Shift and Ctrl too.
		assertEquals(
			listOf(Action.MoveDown),
			bindings.clashes(Action.Rename, chord("Shift+Down")),
		)
		assertFalse(bindings.clashes(Action.Rename, chord("Ctrl+Shift+Q")).isNotEmpty())
		// No two defaults clash.
		for (action in Action.entries)
			for (default in action.defaults)
				assertEquals(
					emptyList<Action>(),
					bindings.clashes(action, default),
					"$action $default",
				)
	}
}
