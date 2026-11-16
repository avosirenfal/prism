package prism.app

import kotlinx.coroutines.runBlocking
import prism.browser.Chord
import prism.browser.Keyboard
import prism.browser.ModifierKey
import prism.browser.Trigger
import prism.cli.Command
import prism.macos.HotKey
import prism.macos.HotKeyModifier
import prism.macos.HotKeyRegistered
import prism.macos.MacKeys
import prism.preferences.DEFAULT_HOT_KEY

/*
 * The launcher's key on a Mac. Linux leaves a launcher's key to the window manager, which runs `prism launcher`; a
 * Mac has no such binding for a program to be given, so the prism that stays (the daemon) holds the key itself, a
 * Carbon hot key (macos/launcher.kt), and shows the launcher as `prism launcher` would have it shown. Spotlight has
 * ⌘Space, so the key is Option and Space until another is set (`launcher.hotKey` in the preferences); to have ⌘Space,
 * Spotlight's is turned off first (docs/launcher.md, "On a Mac").
 */

/** What a chord of the launcher's key is to Carbon: its key, and its modifiers; or why it cannot be one. */
internal sealed interface HotKeyChord {
	data class Of(
		val key: Int,
		val modifiers: Set<HotKeyModifier>,
	) : HotKeyChord

	data class Not(
		val why: String,
	) : HotKeyChord

	companion object {
		/** [written] (`Option+Space`), read in a Mac's names, as Carbon takes it. */
		fun of(written: String): HotKeyChord {
			val chord =
				Chord.parse(written, Keyboard.Mac)
					?: return Not("“$written” is not a key and its modifiers")

			val key =
				(chord.trigger as? Trigger.Key)?.code?.let(MacKeys::of)
					?: return Not("“$written” is not a key a Mac can have as its own")

			val modifiers =
				mapOf(
					ModifierKey.Meta to HotKeyModifier.Command,
					ModifierKey.Shift to HotKeyModifier.Shift,
					ModifierKey.Alt to HotKeyModifier.Option,
					ModifierKey.Ctrl to HotKeyModifier.Control,
				).filterKeys(chord::has).values.toSet()

			if (modifiers.isEmpty())
				return Not("“$written” has no modifier, so it would be taken from everything typed")

			return Of(key, modifiers)
		}
	}
}

/**
 * The launcher's key held for [instance], as its preferences say it, on the windows' thread (where Carbon tells of
 * it): each press shows the launcher, or puts it away, as `prism launcher` does. Asked for from a thread of its own,
 * once the windows run; what cannot be had is said as a warning of [command]'s, and nothing else changes.
 */
internal fun holdLauncherKey(
	command: Command,
	instance: Instance,
	closing: MutableCollection<AutoCloseable>,
) {
	Thread.ofPlatform().name("prism-hot-key").daemon().start {
		val held =
			try {
				runBlocking {
					instance.windows.with { explorer ->
						val written =
							explorer.preferences.value.value.launcher.hotKey ?: DEFAULT_HOT_KEY

						when (val chord = HotKeyChord.of(written)) {
							is HotKeyChord.Not -> HotKeyRegistered.Refused(chord.why)
							is HotKeyChord.Of ->
								HotKey.register(chord.key, chord.modifiers) {
									// Asked as another prism asks, from a thread of its own: Carbon's dispatch is left at once.
									Thread.ofVirtual().start {
										runBlocking { instance.windows.with { it.launch() } }
									}
								}
						} to written
					}
				}
			} catch (ended: NoWindows) {
				return@start
			}

		when (val registered = held.first) {
			is HotKeyRegistered.Held -> {
				closing += registered.key
				instance.says += { "launcher: shown and put away by ${held.second}" }
			}
			is HotKeyRegistered.Refused ->
				warn(command, "the launcher's key, ${held.second}, can't be had: ${registered.why}")
		}
	}
}
