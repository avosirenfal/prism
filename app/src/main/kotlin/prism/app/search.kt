package prism.app

import java.nio.file.Path
import kotlin.io.path.Path
import prism.browser.Finder
import prism.browser.StaticFinder
import prism.browser.aliasesOf
import prism.browser.exclusionsOf
import prism.cli.Args
import prism.cli.Command
import prism.cli.Outcome
import prism.cli.ParamType
import prism.client.Caller
import prism.client.Matches
import prism.client.RunningPrism
import prism.client.SearchCommand
import prism.client.Seeker
import prism.client.Sifted
import prism.client.Sought
import prism.index.Exclusions
import prism.index.IndexFile
import prism.index.IndexRead
import prism.index.Searched
import prism.index.Usage
import prism.index.UsageFile
import prism.index.query.AliasSource
import prism.index.query.Aliases
import prism.index.query.Problem
import prism.index.query.Query
import prism.index.query.QueryText
import prism.preferences.Excluding
import prism.preferences.PreferenceFile
import prism.preferences.PreferenceStore
import prism.preferences.preferences
import prism.shared.Published
import prism.shared.orElse

/*
 * What `prism search` (prism.client, search.kt) is answered with. The command itself only asks; here is who
 * answers: a prism that is running, of its live index (`prism matches`, which the client asks it for), and, where
 * none is, this process, of the index's file as it was last written.
 */

/**
 * This process's preferences: the one store of their file there is in it, read once, when first asked for. Every
 * window the process opens is given what they are and each change made to them by this store, and so is whatever
 * else here asks what they say (a search's aliases); nothing reads the file itself. In the prism that is running
 * that is every window there is; a command run with no prism to serve it has its own, as the file was when it began.
 */
internal val preferencesHere: PreferenceStore by lazy { PreferenceFile.open() }

/**
 * The search aliases as the preferences define them now: what this process's store holds, so that one changed in a
 * settings window of the prism that is running is in the next search that prism answers.
 */
internal fun aliasesNow(): Aliases = aliasesOf(preferencesHere.preferences.value.aliases)

/** The rules last read, and what of the preferences they were read from. */
private val excluding = Published<Pair<Excluding, Exclusions>?>(null)

/**
 * What the index leaves out, and what searches leave out of what it holds, as the preferences say now: read again
 * only when what they say of it has changed, so that what is worked out for one set of rules (which entries of each
 * block a search skips) is kept for as long as they are the rules.
 */
internal fun exclusionsNow(): Exclusions {
	val written = preferencesHere.preferences.value.excluding

	return excluding
		.update {
			this?.takeIf { it.first == written } ?: (written to exclusionsOf(written))
		}!!
		.second
}

/**
 * What [find] finds for [sought]: its query read, with the [aliases] it uses stood for, refused with what is wrong
 * with it where it cannot be searched for, and of what is found the paths asked for, best first.
 */
internal fun sifted(
	sought: Sought,
	find: (Query) -> Searched,
	aliases: AliasSource = aliasesNow(),
): Sifted {
	val read = QueryText.read(sought.text, aliases)
	val wrong = read.problems.filter { it.kind == Problem.Kind.Invalid }

	if (wrong.isNotEmpty())
		return Sifted.Wrong(
			wrong.map { "${it.message} (“${sought.text.substring(it.span.start, it.span.end)}”)" },
		)
	if (read.query.terms.isEmpty())
		return Sifted.Wrong(listOf("“${sought.text}” has nothing to search for"))

	return when (val searched = find(read.query)) {
		is Searched.Refused -> Sifted.Wrong(listOf(searched.why))
		is Searched.Found -> {
			val results = searched.results
			val from = sought.from.coerceIn(0, results.size)
			val to = minOf(results.size.toLong(), from.toLong() + sought.limit).toInt()

			Sifted.Found(
				results.size,
				(from until to).map { at ->
					results[at].where.let { it.path ?: Path(it.toString()) }.toString()
				},
			)
		}
	}
}

/**
 * The index in [file] searched, as it was last written: what a search is answered with where no prism is running to
 * be asked. Read once, when first asked; with no index there, or one that cannot be read, that is the answer.
 */
internal class IndexFileSeeker(
	private val file: () -> Path = ::defaultIndexFile,
	private val home: Path = Path(System.getProperty("user.home")),
) : Seeker {
	private sealed interface Held {
		data class Index(
			val finder: Finder,
		) : Held

		data class None(
			val why: String,
		) : Held
	}

	private val held: Held by lazy {
		val at = file()

		when (val read = IndexFile.read(at)) {
			is IndexRead.Read ->
				Held.Index(
					StaticFinder(
						read.index,
						UsageFile.read(UsageFile.default()).orElse(Usage.NONE),
						home,
					) { exclusionsNow().hiding },
				)
			IndexRead.Missing ->
				Held.None(
					"no index at $at and no running prism has one; run prism daemon to keep " +
						"one up to date, or prism index to build one",
				)
			is IndexRead.Unreadable -> Held.None("can't read the index at $at (${read.why})")
		}
	}

	override fun seek(sought: Sought): Sifted =
		when (val held = held) {
			is Held.Index -> sifted(sought, held.finder::find)
			is Held.None -> Sifted.NoIndex(held.why)
		}
}

/** `prism search`, as the Java prism has it: a running prism asked first, and the index's file read otherwise. */
internal val PathSearch = SearchCommand(otherwise = IndexFileSeeker())

/**
 * `prism matches`: what `prism search` asks a running prism for, and nothing a person runs: a query's text, and of
 * what the index holds for it, a count and then the paths asked for, in the form [Matches] reads. Run where no prism
 * answers, it reads the index's file, as `prism search` does.
 */
internal object MatchesCommand :
	Command(
		Matches.COMMAND,
		"print a query's matches in the format prism search reads (used by prism search)",
	),
	Served {
	private val query =
		argument("QUERY", ParamType.TEXT, help = "the query, as a single argument")
	private val from =
		option(
			Matches.FROM,
			type = ParamType.INT,
			default = 0,
			help = "number of top matches to skip",
		)
	private val limit =
		option(
			Matches.LIMIT,
			type = ParamType.COUNT,
			default = SearchCommand.COUNT,
			help = "maximum number of matches",
		)

	private fun soughtBy(args: Args) =
		Sought(args[query], args[from].coerceAtLeast(0), args[limit])

	private fun said(sifted: Sifted): Outcome =
		Matches.said(sifted).let { Outcome(it.out, it.status) }

	override suspend fun serve(args: Args, caller: Caller, instance: Instance): Outcome =
		said(
			instance.windows.finder?.let { sifted(soughtBy(args), it::find) }
				?: Sifted.NoIndex("the running prism has no index"),
		)

	override fun run(args: Args): Outcome =
		soughtBy(args).let { said(RunningPrism().seek(it) ?: IndexFileSeeker().seek(it)) }
}
