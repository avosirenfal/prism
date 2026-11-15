package prism.explorer

import java.nio.file.Files
import java.nio.file.Path
import prism.index.query.AliasProblem
import prism.index.query.Aliases
import prism.index.query.BodySyntax
import prism.index.query.Glob
import prism.index.query.PathPattern
import prism.index.query.QueryText
import prism.index.query.Test
import prism.index.query.TextSyntax

/*
 * The search aliases as their page edits them: a list of names and queries as they are typed, whatever they are
 * yet. What is typed is read as the aliases it would be ([Aliases.of]), which says what is wrong with each, and
 * only a list with nothing wrong in it is ever written: the page cannot save an alias that is not one.
 *
 * The page is in both windows of settings (the preferences' and the launcher's), since the aliases are every
 * search's: each window's edits hold one of these, and write it where it was changed.
 */

/** One alias as its page has it: its name (without the `@`) and its query, as typed. */
data class AliasDraft(
	val name: String = "",
	val query: String = "",
) {
	/** Whether nothing is typed of it yet: a row just added, which is nothing until something is. */
	val blank: Boolean get() = name.isEmpty() && query.isBlank()
}

/** A folder an alias's query names without the `/` that searches inside it: as it is [written], and the query [mended]. */
data class FolderHint(
	val written: String,
	val mended: String,
) {
	/** What the folder is called: the last of its path as written. */
	val name: String get() = written.trimEnd('/').substringAfterLast('/').ifEmpty { written }
}

/** The aliases, as their page edits them: each as typed, in order. */
data class AliasEdits(
	val drafts: List<AliasDraft> = emptyList(),
) {
	/** Where each draft with anything typed is among those: a row left empty is not an alias written wrong. */
	private val typed: List<Int> = drafts.indices.filterNot { drafts[it].blank }

	private val pairs: List<Pair<String, String>>
		get() = typed.map { drafts[it].name to drafts[it].query }

	/** What is typed, read as the aliases it would be. */
	val read: Aliases.Read by lazy { Aliases.of(pairs) }

	/** The aliases that can be made of what is typed: what each query here is read and coloured with. */
	val aliases: Aliases get() = read.aliases

	/** What is wrong with the [at]th, where anything is; nothing with a row left empty. */
	fun problemAt(at: Int): AliasProblem? =
		typed.indexOf(at).takeIf { it >= 0 }?.let { read.unfit(it, drafts[at].name) }

	/** Whether nothing is wrong with any of them: only then are they written. */
	val fit: Boolean get() = read.fit(pairs)

	/** What these write to the preferences, name to query, in order; null while something is wrong with one. */
	val written: Map<String, String>? get() = pairs.toMap().takeIf { fit }

	/** These, with one more at the end: [draft], or a row to type one into. */
	fun added(draft: AliasDraft = AliasDraft()): AliasEdits = copy(drafts = drafts + draft)

	fun removed(at: Int): AliasEdits =
		copy(
			drafts =
				drafts.filterIndexed { index, _ ->
					index !=
						at
				},
		)

	/** These, with the [at]th as [change] makes it. */
	fun set(at: Int, change: AliasDraft.() -> AliasDraft): AliasEdits =
		copy(
			drafts =
				drafts.mapIndexed { index, draft ->
					if (index ==
						at
					) draft.change() else draft
				},
		)

	/**
	 * A folder the [at]th's query names as an entry, where one is there on disk ([isFolder]): `~/projects` finds the
	 * folder itself, and `~/projects/` what is inside it, which is what an alias for a folder is nearly always meant
	 * to find. Said only of a query with nothing wrong with it.
	 */
	fun hintAt(
		at: Int,
		home: Path = Path.of(System.getProperty("user.home")),
		isFolder: (Path) -> Boolean = { Files.isDirectory(it) },
	): FolderHint? {
		val query = drafts.getOrNull(at)?.query ?: return null

		if (problemAt(at) != null)
			return null

		return QueryText.read(query, aliases).terms.firstNotNullOfOrNull { term ->
			val words = term.syntax.body as? BodySyntax.Words
			val bare = words?.text as? TextSyntax.Bare
			val path = (term.meaning?.test as? Test.InPath)?.pattern

			bare
				?.takeIf {
					term.syntax.negation == null && words.start == null && words.end == null &&
						path?.last is PathPattern.Last.Entry && !Glob.isGlob(it.text)
				}?.let { folderOf(Glob.unescaped(it.text), home) }
				?.takeIf(isFolder)
				?.let {
					FolderHint(
						bare.text,
						query.substring(0, bare.span.end) + "/" + query.substring(bare.span.end),
					)
				}
		}
	}

	private fun folderOf(text: String, home: Path): Path? =
		when {
			text == "~" -> home
			text.startsWith("~/") -> home.resolve(text.substring(2))
			text.startsWith("/") -> Path.of(text)
			else -> null
		}

	companion object {
		/** The aliases [written] in the preferences, as the page shows them. */
		fun of(written: Map<String, String>): AliasEdits =
			AliasEdits(written.map { (name, query) -> AliasDraft(name, query) })

		/** What the page shows an alias to be, where there are none yet: each added as it is when chosen. */
		val EXAMPLES: List<AliasDraft> =
			listOf(
				AliasDraft("projects", "~/projects/"),
				AliasDraft("raws", "~/Photos/ ext:raw"),
			)

		/** What the window says in place of "Unsaved changes" while an alias has something wrong with it. */
		const val UNFIT = "Fix the problems on the Aliases page to apply changes"
	}
}
