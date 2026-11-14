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

	private fun made(vararg names: String) =
		names.forEach {
			root
				.resolve(it)
				.also { path ->
					path.parent.createDirectories()
				}.writeText(it)
		}

	@Test
	fun aTermThatStartsWithADotIsAnExtensionOrANameThatBeginsWithOne() {
		made(
			".whatever-metis",
			"whatever.metis",
			"whatever.haha-metis",
			"whatever.ngfx-metis",
			"whatever.metis.bak",
			"metis",
			"metis.txt",
			"no-dot-metis",
			".jpg",
			"something.jpg",
			"jpg.png",
			"v1.2 notes.txt",
			"archive.tar.gz",
		)

		// A dot, anything, and then metis: a hidden name, or an extension; not what is only on the way to one.
		assertEquals(
			setOf(
				".whatever-metis",
				"whatever.metis",
				"whatever.haha-metis",
				"whatever.ngfx-metis",
			),
			found(".*metis"),
		)
		// ext: is an extension, whole: a hidden name with no extension has none to be it.
		assertEquals(
			setOf("whatever.metis", "whatever.haha-metis", "whatever.ngfx-metis"),
			found("ext:*metis"),
		)
		// .jpg is a file named just that, or one with that extension; and it is found as it is typed.
		assertEquals(setOf(".jpg", "something.jpg"), found(".jpg"))
		assertEquals(setOf(".jpg", "something.jpg"), found(".jp"))
		// A dot inside the name is no extension's; the last of two extensions is one, and so are both.
		assertEquals(emptySet<String>(), found(".2"))
		assertEquals(setOf("archive.tar.gz"), found(".gz"))
		assertEquals(setOf("archive.tar.gz"), found(".tar"))
		assertEquals(setOf("whatever.metis.bak"), found(".bak"))
	}

	@Test
	fun aTermWithADotInsideItIsFoundInTheWholeNameAndOneWithNoneOnlyInTheStem() {
		made(
			"lets_awoo.zip",
			"awoo.txt",
			"something.jpg",
			"jpg.png",
			"whatever.asdf",
			"asdf.txt",
			"my-asdf-file.md",
		)

		// A dot inside: the whole name, extension and all.
		assertEquals(setOf("lets_awoo.zip"), found("awoo.z"))
		// No dot: the stem alone, so an extension is never what is found.
		assertEquals(setOf("jpg.png"), found("jpg"))
		assertEquals(setOf("asdf.txt", "my-asdf-file.md"), found("asdf"))
		assertEquals(setOf("asdf.txt", "my-asdf-file.md"), found("asdf*"))
		assertEquals(setOf("asdf.txt", "my-asdf-file.md"), found("*asdf"))
		assertEquals(setOf("asdf.txt"), found("=asdf"))
	}
}
