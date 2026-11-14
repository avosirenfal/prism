package prism.index.query

/**
 * Something of prism's own that a search can find beside the index's files: its settings, the page for the system's
 * drives, how a query is written. Whoever searches says which of them it [offers][Own.found] (an explorer's window
 * and the launcher each have their own), and gets back those the text asks for, to show before what the index found
 * and to do what each means there when it is chosen.
 *
 * They are asked for by the word `prism`: a query with that word in it, whole and alone (`prism`, `prism settings`,
 * `settings prism`), finds those whose words its other words begin (`prism set` finds Settings), and all of them
 * where it has no other word. The one exception is [Help], which a bare `?` finds too, since `?` is what asks how.
 * The index is searched for the same text all the same: a file named `prism settings.txt` is still found, after.
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
		override val words = listOf("settings", "preferences", "options", "configuration")
	}

	/** The settings' page of key bindings. */
	data object Bindings : Own {
		override val title = "Key bindings"
		override val does = "Change what each key does"
		override val words =
			listOf("key", "bindings", "keys", "keybinds", "keyboard", "shortcuts", "hotkeys")
	}

	/** The page of the system's drives and what they hold. */
	data object System : Own {
		override val title = "System"
		override val does = "This computer's drives, and what is on them"
		override val words = listOf("system", "drives", "disks", "storage", "computer")
	}

	/** How a query is written ([QueryHelp]). */
	data object Help : Own {
		override val title = "How to search"
		override val does = "How a query is written, with examples to try"
		override val words =
			listOf("how", "to", "search", "help", "syntax", "query", "examples")
	}

	companion object {
		/** The word that asks for prism's own. */
		const val WORD = "prism"

		/** What asks how a query is written, alone. */
		const val HOW = "?"

		/** Every one there is, in the order they are listed in where nothing says otherwise. */
		val ALL: List<Own> = listOf(Settings, Bindings, System, Help)

		/**
		 * Those of [offered] that [text] asks for, the best first: none, for a text without the word `prism` (and
		 * that is not a bare `?`); otherwise each whose words the text's other words each begin, those whose title
		 * they begin before those found by another of their words, and in [offered]'s order among equals.
		 */
		fun found(text: String, offered: List<Own>): List<Own> {
			val said =
				text
					.trim()
					.lowercase()
					.split(Regex("\\s+"))
					.filter { it.isNotEmpty() }

			if (said == listOf(HOW))
				return offered.filter { it == Help }
			if (WORD !in said)
				return emptyList()

			val others = said - WORD

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
