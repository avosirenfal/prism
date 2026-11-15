package prism.explorer

import java.nio.file.Path
import prism.browser.excludedOf
import prism.browser.exclusionsOf
import prism.index.Exclusions
import prism.index.Naming
import prism.preferences.Excluded
import prism.preferences.Excluding

/*
 * What the index leaves out, as its page edits it: prism's own rules, each switched on or off and made one kind or
 * the other, and the user's own, added and taken away. What the page holds is what the preferences keep
 * ([Excluding]: the user's differences from prism's own), so what it writes is what it shows; and a rule is added
 * only once it reads as one ([Exclusions.unfit]), so there is never one written that is not.
 *
 * The page is in both windows of settings (the preferences' and the launcher's), since the rules are every search's:
 * each window's edits hold one of these, and write it where it was changed.
 */

/** What is being typed to be added: the rule's text, and what it is to do. */
data class RuleDraft(
	val text: String = "",
	val excluded: Excluded = Excluded.Ignored,
)

/** How a rule typed is read: the folder at a path, or a search. */
enum class RuleKind(
	val label: String,
) {
	Folder("Folder"),
	Search("Search"),
}

/** The rules, as their page edits them: [kept] as the preferences keep them, and what is typed to be [adding]. */
data class ExcludingEdits(
	val kept: Excluding = Excluding(),
	val adding: RuleDraft = RuleDraft(),
	val home: Path = Path.of(System.getProperty("user.home")),
) {
	/** The rules as they would be: prism's own first, then the user's. */
	val rules: Exclusions by lazy { exclusionsOf(kept, home) }

	/** Prism's own rules, in their order. */
	val builtIn: List<Exclusions.Rule> get() = rules.rules.filter { it.builtIn }

	/** The user's own, in the order they were added. */
	val own: List<Exclusions.Rule> get() = rules.rules.filterNot { it.builtIn }

	/** What is typed to be added, read: what kind of rule it is; null where nothing is typed, or it is no rule. */
	val addingKind: RuleKind?
		get() =
			when ((Naming.read(adding.text, home) as? Naming.Read.Fit)?.naming) {
				is Naming.Folder -> RuleKind.Folder
				is Naming.Sought -> RuleKind.Search
				null -> null
			}

	/** Why what is typed cannot be added; null where it can, and where nothing is typed yet. */
	val addingProblem: String?
		get() =
			adding.text.takeIf { it.isNotBlank() }?.let {
				Exclusions.unfit(it, rules.rules.map { rule -> rule.names }, home)
			}

	/** Whether what is typed can be added. */
	val addable: Boolean get() = adding.text.isNotBlank() && addingProblem == null

	/** The folder typed to be added, where it is one that is not there on the disk: it can be added all the same. */
	fun missing(
		isFolder: (Path) -> Boolean = {
			java.nio.file.Files
				.isDirectory(it)
		},
	): Path? =
		((Naming.read(adding.text, home) as? Naming.Read.Fit)?.naming as? Naming.Folder)
			?.path
			?.takeIf { addable && !isFolder(it) }

	/** These, with what is typed to be added as [change] makes it. */
	fun typing(change: RuleDraft.() -> RuleDraft): ExcludingEdits =
		copy(adding = adding.change())

	/** These, with what is typed added as one of the user's own, where it can be, and the field empty again. */
	fun added(): ExcludingEdits =
		if (addable)
			copy(
				kept = kept.copy(own = kept.own + (adding.text.trim() to adding.excluded)),
				adding = RuleDraft(excluded = adding.excluded),
			)
		else
			this

	/** These, without the user's own rule [written]. */
	fun removed(written: String): ExcludingEdits =
		copy(
			kept =
				kept.copy(
					own =
						kept.own - written,
				),
		)

	/** These, with prism's own rule [written] switched [on], or off. */
	fun switched(written: String, on: Boolean): ExcludingEdits =
		copy(
			kept =
				kept.copy(
					off =
						if (on)
							kept.off - written
						else
							kept.off + written,
				),
		)

	/** These, with the rule [written] doing [excluded]: one of prism's own, or one of the user's. */
	fun doing(written: String, excluded: Excluded): ExcludingEdits =
		copy(
			kept =
				if (written in kept.own)
					kept.copy(
						own =
							kept.own.mapValues { (rule, was) ->
								if (rule ==
									written
								) excluded else was
							},
					)
				else if (excluded == DEFAULT)
					kept.copy(changed = kept.changed - written)
				else
					kept.copy(changed = kept.changed + (written to excluded)),
		)

	/** These, with every one of prism's own as it comes: on, and not indexed. The user's own are as they were. */
	fun reset(): ExcludingEdits = copy(kept = Excluding(own = kept.own))

	/** Whether any of prism's own is other than it comes. */
	val changed: Boolean get() = kept.off.isNotEmpty() || kept.changed.isNotEmpty()

	/** What a rule does, as the preferences keep it. */
	fun excluded(rule: Exclusions.Rule): Excluded = excludedOf(rule.exclusion)

	companion object {
		/** What one of prism's own rules does unless it is changed. */
		val DEFAULT = Excluded.Ignored

		/** The rules [kept] in the preferences, as the page shows them. */
		fun of(kept: Excluding): ExcludingEdits = ExcludingEdits(kept)
	}
}
