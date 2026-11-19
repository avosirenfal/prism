package prism.index

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.index.query.QueryText
import prism.platform.Os

/** Places held though a folder above them is left out: read as roots of their own, and hidden from what is searched. */
class IncludedTest {
	@TempDir
	lateinit var root: Path

	private fun made(vararg files: String) =
		files.forEach {
			root
				.resolve(it)
				.also { path ->
					path.parent.createDirectories()
				}.writeText(it)
		}

	@Test
	fun anIncludedPlaceIsHeldUnderAFolderLeftOutAndHiddenTillASearchNamesIt() {
		made(
			"System/Applications/Safari.app/Contents/Info.plist",
			"System/Library/CoreServices/Finder.app/Contents/Info.plist",
			"System/Library/CoreServices/Dock.app/Contents/Info.plist",
			"Applications/Firefox.app/Contents/Info.plist",
		)

		val included =
			listOf("System/Applications", "System/Library/CoreServices/Finder.app").map {
				Included.of(root.resolve(it))!!
			}

		val rules =
			Exclusions.of(
				off = Exclusions.DEFAULTS.toSet(),
				own = mapOf("$root/System" to Exclusion.Ignored),
				home = root,
				included = included,
			)

		val places =
			(
				listOf(root) +
					included
						.filter {
							it.under(listOf(root))
						}.map { it.path }
			).map(Where::of).toSet()
		val indexer = Indexer(rules.ignore, mounts = emptyList(), asked = places)
		val index = Index.EMPTY.with(places.map(indexer::index))

		fun found(text: String): Set<String> =
			(
				index.search(
					QueryText.read(text).query,
					SearchContext(home = root, hiding = rules.hiding),
				) as Searched.Found
			).results
				.map { root.relativize(it.where.path!!).toString() }
				.toSet()

		val held = index.entries().map { root.relativize(it.where.path!!).toString() }.toSet()

		// What is included is held, and nothing else of the folder left out.
		assertTrue("System/Applications/Safari.app" in held)
		assertTrue("System/Library/CoreServices/Finder.app" in held)
		assertTrue("System/Library/CoreServices/Dock.app" !in held)
		// And is found only by a search that names where it is.
		assertEquals(emptySet<String>(), found("Safari"))
		assertEquals(
			setOf("System/Applications/Safari.app"),
			found("$root/System/Applications/Safari.app"),
		)
		assertEquals(setOf("Applications/Firefox.app"), found("Firefox"))
	}

	@Test
	fun theIncludedAreAMacsSystemApplicationsAndAreWhole() {
		assertEquals(
			listOf(
				"/System/Applications",
				"/System/Applications/Utilities",
				"/System/Library/CoreServices/Applications",
				"/System/Library/CoreServices/Finder.app",
			),
			Included.defaultsOn(Os.MacOS).map { it.path.toString() },
		)
		assertEquals(emptyList<Included>(), Included.defaultsOn(Os.Linux))
		assertNull(Included.of(Path.of("relative")))
		assertNull(Included.of(Path.of("/")))

		// Under a root that holds it, and not under itself or another tree.
		val apps = Included.of(Path.of("/System/Applications"))!!

		assertTrue(apps.under(listOf(Path.of("/"))))
		assertTrue(!apps.under(listOf(Path.of("/System/Applications"), Path.of("/Users"))))
	}
}
