package prism.index

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.index.query.QueryText

/**
 * A glob typed as a name term, read as the search's field reads it: found anywhere in a name, where `ext:` with the
 * same glob is the whole of an extension.
 */
class GlobTest {
	@TempDir
	lateinit var root: Path

	@TempDir
	lateinit var state: Path

	private val now = Instant.parse("2026-11-12T10:00:00Z")

	private fun found(text: String): Set<String> {
		val index =
			Index.EMPTY.with(
				Indexer(mounts = emptyList(), clock = Clock.fixed(now, ZoneOffset.UTC)).index(root),
			)

		val context = SearchContext(home = state, now = now, zone = ZoneOffset.UTC)

		return (index.search(QueryText.read(text).query, context) as Searched.Found)
			.results
			.map { it.where.toString().removePrefix("$root/") }
			.toSet()
	}

	@Test
	fun aDotAndAStarFindANameWithThemAnywhereAndExtOnlyAnExtension() {
		val names =
			listOf(
				".whatever-metis",
				"whatever.metis",
				"whatever.haha-metis",
				"whatever.ngfx-metis",
				"whatever.metis.bak",
				"metis",
				"metis.txt",
				"no-dot-metis",
			)

		names.forEach {
			root.resolve(it).also { path -> path.parent.createDirectories() }.writeText(it)
		}

		// A dot, anything, and then metis, wherever in the name: a hidden name, an extension, and one with more after.
		assertEquals(
			setOf(
				".whatever-metis",
				"whatever.metis",
				"whatever.haha-metis",
				"whatever.ngfx-metis",
				"whatever.metis.bak",
			),
			found(".*metis"),
		)
		// Held at the end, it is a name that ends so.
		assertEquals(
			setOf(
				".whatever-metis",
				"whatever.metis",
				"whatever.haha-metis",
				"whatever.ngfx-metis",
			),
			found(".*metis$"),
		)
		// ext: is an extension, whole: a hidden name with no extension has none to be it.
		assertEquals(
			setOf("whatever.metis", "whatever.haha-metis", "whatever.ngfx-metis"),
			found("ext:*metis"),
		)
	}
}
