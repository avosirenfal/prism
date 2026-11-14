package prism.index.query

/**
 * Something of prism's own that a search can find beside the index's files: its settings, the page for the system's
 * drives, how a query is written. Whoever searches says which of them it [offers][Own.found] (an explorer's window
 * and the launcher each have their own), and gets back those the text asks for, to show before what the index found
 * and to do what each means there when it is chosen.
 *
 * They are found by their own words: a text whose every word begins one of an item's [words] finds it (`settings`,
 * `set`, `key bind`), once it is [LEAST] letters long, so that a letter or two typed on the way to a file's name
 * finds none of them. The word `prism` scopes a search to them: with it, whole and alone, the other words need be
 * no longer than they are, and with no other word every one offered is found. A bare `?` finds [Help], since `?` is
 * what asks how. The index is searched for the same text all the same: a file named `settings.json` is still found,
 * after.
 */
sealed interface Own {
	/** What it is called, where it is listed. */
	val title: String

	/** What choosing it does, in a few words. */
	val does: String

	/** The words it is found by after `prism`, its title's among them, in lower case. */
	val words: List<String>

	/** The settings of whatever is searching: an explorer's preferences, the launcher's settings. */
	data object Settings : Own {
		override val title = "Settings"
		override val does = "Open prism's settings"
		override val words = listOf("settings", "preferences", "options")
	}

	/** The settings' page of key bindings. */
	data object Bindings : Own {
		override val title = "Key bindings"
		override val does = "Change keyboard shortcuts"
		override val words =
			listOf("key", "bindings", "keys", "keybinds", "keyboard", "shortcuts", "hotkeys")
	}

	/** The page of the system's drives and what they hold. */
	data object System : Own {
		override val title = "System"
		override val does = "Drives and storage on this computer"
		override val words = listOf("system", "drives", "disks", "storage")
	}

	/** How a query is written ([QueryHelp]). */
	data object Help : Own {
		override val title = "Search help"
		override val does = "Search syntax, with examples"
		override val words = listOf("help", "search", "syntax")
	}

	companion object {
		/** The word that asks for prism's own. */
		const val WORD = "prism"

		/** What asks how a query is written, alone. */
		const val HOW = "?"

		/** Every one there is, in the order they are listed in where nothing says otherwise. */
		val ALL: List<Own> = listOf(Settings, Bindings, System, Help)

		/** How many letters a text without the word `prism` has before it finds anything of prism's. */
		const val LEAST = 3

		private val SPACES = Regex("\\s+")

		/**
		 * Those of [offered] that [text] asks for, the best first: each whose words the text's words each begin
		 * (the word `prism` aside, which only says that prism's own are meant), those whose title they begin before
		 * those found by another of their words, and in [offered]'s order among equals. None, for a text of fewer
		 * than [LEAST] letters that does not say `prism`; all of them, for `prism` alone; [Help], for a bare `?`.
		 */
		fun found(text: String, offered: List<Own>): List<Own> {
			val said =
				text
					.trim()
					.lowercase()
					.split(SPACES)
					.filter { it.isNotEmpty() }

			if (said == listOf(HOW))
				return offered.filter { it == Help }

			val scoped = WORD in said
			val others = said - WORD

			if (!scoped && others.sumOf { it.length } < LEAST)
				return emptyList()

			fun begun(words: List<String>) =
				others.all { other ->
					words.any {
						it.startsWith(other)
					}
				}

			return offered
				.filter { begun(it.words) }
				.sortedBy { !begun(it.title.lowercase().split(' ')) }
		}
	}
}
