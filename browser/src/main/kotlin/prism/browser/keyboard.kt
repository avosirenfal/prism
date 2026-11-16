package prism.browser

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import prism.platform.Os

/*
 * How a platform's keyboard is used. A shortcut on Linux and Windows is made with Ctrl (Ctrl+C), and on macOS with
 * Command (⌘C); Ctrl there is a key of its own, which the system takes for itself with the arrows (Ctrl+Left goes to
 * the Space on the left). So where the browser means "the key shortcuts are made with", it says the primary modifier,
 * and each platform's [Keyboard] says which that is; an action's defaults are written with it (`Primary+C`), and where
 * macOS's own convention is another chord altogether (Finder's ⌘[ for Back), the Mac's defaults say so ([MAC]).
 */

/** A modifier a chord can hold. */
enum class ModifierKey {
	Ctrl,
	Alt,
	Shift,
	Meta,
}

/** A platform's way with modifiers: what its shortcuts are made with, and what each is called there. */
enum class Keyboard(
	/** What its shortcuts are made with: Ctrl, or Command on a Mac. */
	val primary: ModifierKey,
	/**
	 * What moves a list's cursor without choosing, beside Shift, which extends what is chosen: Ctrl, as Explorer has it;
	 * on a Mac, Option, since the system has Ctrl with the arrows and Command with them is Finder's (⌘↑ is the folder
	 * above).
	 */
	val aside: ModifierKey,
	/** What each modifier is called, as a chord is shown and written. */
	private val names: Map<ModifierKey, String>,
) {
	Pc(
		ModifierKey.Ctrl,
		ModifierKey.Ctrl,
		mapOf(
			ModifierKey.Ctrl to "Ctrl",
			ModifierKey.Alt to "Alt",
			ModifierKey.Shift to "Shift",
			ModifierKey.Meta to "Meta",
		),
	),
	Mac(
		ModifierKey.Meta,
		ModifierKey.Alt,
		mapOf(
			ModifierKey.Ctrl to "Ctrl",
			ModifierKey.Alt to "Option",
			ModifierKey.Shift to "Shift",
			ModifierKey.Meta to "Cmd",
		),
	),
	;

	fun nameOf(modifier: ModifierKey): String = names.getValue(modifier)

	/** What the primary modifier is called here: `Ctrl`, or `Cmd`. */
	val primaryName: String get() = nameOf(primary)

	/**
	 * The modifier [word] names, whichever platform's name it is (`Alt` or `Option`, `Meta`, `Cmd` or `Command`), or
	 * `Primary` for this keyboard's [primary]: a file of bindings written on one platform reads on another.
	 */
	fun modifierNamed(word: String): ModifierKey? =
		when (word.lowercase()) {
			"primary" -> primary
			"ctrl", "control" -> ModifierKey.Ctrl
			"alt", "option", "opt" -> ModifierKey.Alt
			"shift" -> ModifierKey.Shift
			"meta", "cmd", "command", "super" -> ModifierKey.Meta
			else -> null
		}

	companion object {
		/** The keyboard of the platform this runs on. */
		val current: Keyboard = of(Os.current)

		fun of(os: Os): Keyboard = if (os == Os.MacOS) Mac else Pc
	}
}

/** Whether this keyboard's primary modifier is held: Ctrl, or Command on a Mac. */
val KeyEvent.isPrimaryPressed: Boolean
	get() = Keyboard.current.primary in held(isCtrlPressed, isAltPressed, isMetaPressed)

/** Whether this keyboard's primary modifier is held: Ctrl, or Command on a Mac. */
val PointerKeyboardModifiers.isPrimaryPressed: Boolean
	get() = Keyboard.current.primary in held(isCtrlPressed, isAltPressed, isMetaPressed)

private fun held(ctrl: Boolean, alt: Boolean, meta: Boolean): Set<ModifierKey> =
	setOfNotNull(
		ModifierKey.Ctrl.takeIf {
			ctrl
		},
		ModifierKey.Alt.takeIf { alt },
		ModifierKey.Meta.takeIf { meta },
	)
