package prism.daemon

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.index.Exclusion
import prism.index.Exclusions
import prism.index.Index
import prism.index.Indexer
import prism.index.SearchContext
import prism.index.Where
import prism.index.query.QueryText

/**
 * The rules of what the index leaves out, changed under a live index: what is now left out is taken out of it, what
 * no longer is, is read, by a path and by a query alike; and what is only hidden is neither, since it is held all
 * the while.
 */
class RuledTest {
	@TempDir
	lateinit var root: Path

	private fun made(vararg files: String) =
		files.forEach {
			root
				.resolve(it)
				.also { path -> path.parent.createDirectories() }
				.writeText(it)
		}

	/** The user's rules [written], and none of prism's own: the test's folders are under one of those. */
	private fun rules(vararg written: Pair<String, Exclusion>): Exclusions =
		Exclusions.of(off = Exclusions.DEFAULTS.toSet(), own = written.toMap(), home = root)

	private fun opened(rules: Exclusions): LiveIndex =
		LiveIndex
			.open(
				null,
				listOf(root),
				Indexer(rules.ignore, mounts = emptyList()),
				LiveSettings(mounts = { emptyList() }),
			).also { it.opened.done() }

	private fun Index.names(): Set<String> =
		entries()
			.map { it.where.toString() }
			.filter { it != "$root" }
			.map { it.removePrefix("$root/") }
			.toSet()

	private fun <T> java.util.concurrent.Future<T>.done(): T = get(30, TimeUnit.SECONDS)

	private fun LiveIndex.found(text: String, rules: Exclusions): Set<String> =
		(
			search(
				QueryText.read(text).query,
				SearchContext(home = root, hiding = rules.hiding),
			) as LiveSearch.Found
		).results
			.map { it.where.toString().removePrefix("$root/") }
			.toSet()

	@Test
	fun aFolderNowLeftOutIsTakenOutAndOneNoLongerLeftOutIsRead() {
		made("games/a/x.sav", "docs/x.txt", "music/x.flac")

		val none = rules()

		opened(none).use { live ->
			assertEquals(
				setOf(
					"games",
					"games/a",
					"games/a/x.sav",
					"docs",
					"docs/x.txt",
					"music",
					"music/x.flac",
				),
				live.index.names(),
			)

			// By its path: gone at once, and nothing else is read again.
			live.ruled(rules("$root/games" to Exclusion.Ignored).ignore).done()
			assertEquals(setOf("docs", "docs/x.txt", "music", "music/x.flac"), live.index.names())

			// Another in its place: the one comes back, as it is on the disk now, and the other goes.
			made("games/b/y.sav")
			live.ruled(rules("$root/music" to Exclusion.Ignored).ignore).done()
			assertEquals(
				setOf(
					"games",
					"games/a",
					"games/a/x.sav",
					"games/b",
					"games/b/y.sav",
					"docs",
					"docs/x.txt",
				),
				live.index.names(),
			)

			// The same rules again change nothing.
			live.ruled(rules("$root/music" to Exclusion.Ignored).ignore).done()
			assertTrue("games/b/y.sav" in live.index.names())
		}
	}

	@Test
	fun aQueryRuleChangedIsTheWholeIndexReadAgainByIt() {
		made("a/build/out.o", "a/src/main.c", "b/build/out.o", "b/notes.txt")

		opened(rules()).use { live ->
			assertTrue("a/build/out.o" in live.index.names())

			// Every folder named build, wherever it is.
			live.ruled(rules("build/" to Exclusion.Ignored).ignore).done()
			assertEquals(
				setOf("a", "a/src", "a/src/main.c", "b", "b/notes.txt"),
				live.index.names(),
			)

			// And a query that is no folder's name, in its place: the folders are back, the files it finds gone.
			live.ruled(rules(".o" to Exclusion.Ignored).ignore).done()
			assertEquals(
				setOf("a", "a/build", "a/src", "a/src/main.c", "b", "b/build", "b/notes.txt"),
				live.index.names(),
			)

			live.ruled(rules().ignore).done()
			assertTrue("b/build/out.o" in live.index.names())
		}
	}

	@Test
	fun whatIsHiddenIsHeldAndASearchOfTheLiveIndexLeavesItOutUnlessItIsNamed() {
		made("games/x.sav", "docs/x.txt")

		val hidden = rules("$root/games" to Exclusion.Hidden)

		opened(hidden).use { live ->
			assertTrue("games/x.sav" in live.index.names())
			assertEquals(setOf("docs/x.txt"), live.found("x", hidden))
			assertEquals(setOf("games/x.sav"), live.found("$root/games/ x", hidden))

			// Shown again: nothing is read, and it is found.
			val shown = rules()

			live.ruled(shown.ignore).done()
			assertEquals(setOf("docs/x.txt", "games/x.sav"), live.found("x", shown))
		}
	}
}
