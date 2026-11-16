package prism.macos

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Objective-C reached through its runtime, on a Mac: text there and back, a URL, the uniform types, a bundle's
 * Info.plist, Launch Services' default, an icon drawn, and the appearance asked; each the system answering.
 */
@EnabledOnOs(OS.MAC)
class ObjCTest {
	@Test
	fun textAndPathsGoThereAndBack() {
		ObjC.pooled {
			assertEquals("héllo, wörld", nsString("héllo, wörld").text())
			assertEquals(Path.of("/Users/a b/c"), fileUrl(Path.of("/Users/a b/c")).filePath())
			assertNull(Id.NIL.text())
			assertTrue(nsString("x").isA("NSString"))
		}
	}

	@Test
	fun theSystemSaysWhatATypeIs() {
		val png = UniformTypes.ofExtension("png")

		assertEquals("public.png", png?.identifier)
		assertEquals("image/png", png?.mime)
		assertTrue(png!!.conformsTo("public.image"), "${png.supertypes}")
		assertTrue("png" in png.extensions)
		assertEquals("public.png", UniformTypes.ofMime("image/png")?.identifier)
		assertNull(UniformTypes.ofExtension("no-such-extension-anywhere"))
		assertEquals("public.folder", UniformTypes.ofFile(Path.of("/Users"))?.identifier)
	}

	@Test
	fun aBundleSaysWhatItIsAndLaunchServicesWhatOpensWhat() {
		val textEdit = Applications.bundle(Path.of("/System/Applications/TextEdit.app"))

		assertEquals("com.apple.TextEdit", textEdit?.identifier)
		assertEquals("TextEdit", textEdit?.name)
		assertTrue(textEdit!!.opens.isNotEmpty(), "$textEdit")
		assertNotNull(Applications.defaultFor("public.plain-text"))
		assertTrue(Applications.forType("public.plain-text").isNotEmpty())

		val icon = Applications.iconPng(Path.of("/System/Applications/TextEdit.app"), 64)

		assertNotNull(icon)
		// A PNG's signature.
		assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), icon!!.take(4).map { it.toInt() and 0xFF })
		assertNotNull(displayNameOf(Path.of("/")))
	}

	@Test
	fun theAppearanceIsAsked() {
		// Either answer is right; that it answers without failing is what is tested.
		prefersDark()
	}
}
