package prism.app

import java.nio.file.Path
import kotlin.io.path.Path
import prism.browser.Finder
import prism.browser.StaticFinder
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
import prism.index.IndexFile
import prism.index.IndexRead
import prism.index.Searched
import prism.index.UsageFile
import prism.index.query.Problem
import prism.index.query.Query
import prism.index.query.QueryText

/*
 * What `prism search` (prism.client, search.kt) is answered with. The command itself only asks; here is who
 * answers: a prism that is running, of its live index (`prism matches`, which the client asks it for), and, where
 * none is, this process, of the index's file as it was last written.
 */

/**
 * What [find] finds for [sought]: its query read, refused with what is wrong with it where it cannot be searched
 * for, and of what is found the paths asked for, best first.
 */
internal fun sifted(sought: Sought, find: (Query) -> Searched): Sifted {
	val read = QueryText.read(sought.text)
	val wrong = read.problems.filter { it.kind == Problem.Kind.Invalid }

	if (wrong.isNotEmpty())
		return Sifted.Wrong(
			wrong.map { "${it.message}: ${sought.text.substring(it.span.start, it.span.end)}" },
		)
	if (read.query.terms.isEmpty())
		return Sifted.Wrong(listOf("there is nothing in “${sought.text}” to search for yet"))

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
				Held.Index(StaticFinder(read.index, UsageFile.read(UsageFile.default()).usage, home))
			IndexRead.Missing ->
				Held.None(
					"there is no index at $at, and no prism running that keeps one: " +
						"prism daemon keeps one current, and prism index makes one once",
				)
			is IndexRead.Unreadable -> Held.None("the index at $at can't be read (${read.why})")
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
		"what prism search asks the prism already running: a query's matches, as one prism says them to another",
	),
	Served {
	private val query =
		argument("QUERY", ParamType.TEXT, help = "the query's text, as one word")
	private val from =
		option(
			Matches.FROM,
			type = ParamType.INT,
			default = 0,
			help = "how many of the best to leave out",
		)
	private val limit =
		option(
			Matches.LIMIT,
			type = ParamType.COUNT,
			default = SearchCommand.COUNT,
			help = "how many to give",
		)

	private fun soughtBy(args: Args) =
		Sought(args[query], args[from].coerceAtLeast(0), args[limit])

	private fun said(sifted: Sifted): Outcome =
		Matches.said(sifted).let { Outcome(it.out, it.status) }

	override suspend fun serve(args: Args, caller: Caller, instance: Instance): Outcome =
		said(
			instance.windows.finder?.let { sifted(soughtBy(args), it::find) }
				?: Sifted.NoIndex("the prism already running keeps no index"),
		)

	override fun run(args: Args): Outcome =
		soughtBy(args).let { said(RunningPrism().seek(it) ?: IndexFileSeeker().seek(it)) }
}
