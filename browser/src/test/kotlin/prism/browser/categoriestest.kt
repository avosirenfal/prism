package prism.browser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import prism.index.Extension

class CategoriesTest {
	@Test
	fun aNamesExtensionAsANumberSaysWhatTheNameItselfDoes() {
		val names =
			listOf(
				"photo.jpg",
				"PHOTO.JPEG",
				"song.flac",
				"film.mkv",
				"archive.tar.gz",
				"notes.md",
				"main.kt",
				"build.gradle.kts",
				"report.pdf",
				"sheet.xlsx",
				"page.html",
				"data.json",
				"a.c",
				"a.h",
				"x.7z",
				"Makefile",
				".gitignore",
				".config.toml",
				"name.",
				"what.unknown",
				"a.averylongextension",
				"",
			)

		for (name in names)
			assertEquals(categoryOf(name), categoryOf(Extension.of(name)), name)
		assertEquals(Category.Image, categoryOf(Extension.of("photo.JPG")))
		assertEquals(Category.Other, categoryOf(Extension.of("Makefile")))
	}
}
