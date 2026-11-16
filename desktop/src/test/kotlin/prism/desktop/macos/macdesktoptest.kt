package prism.desktop.macos

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import prism.desktop.MimeType
import prism.desktop.Place
import prism.desktop.TrashFor
import prism.macos.UniformType

/**
 * A Mac's desktop: how a uniform type is said as a MIME type (on any platform), and on a Mac the system itself
 * answering: types, applications, places, volumes, icons, and the trash both ways.
 */
class MacDesktopTest {
	private fun type(identifier: String, mime: String? = null, vararg supertypes: String) =
		UniformType(identifier, mime, null, emptyList(), supertypes.toList())

	@Test
	fun aUniformTypeIsItsMimeTypeOrOneMadeOfItUnderItsKind() {
		assertEquals(
			MimeType("image/png"),
			UniformFileTypes.mimeOf(type("public.png", "image/png", "public.image")),
		)
		assertEquals(
			MimeType.FOLDER,
			UniformFileTypes.mimeOf(type("public.folder", null, "public.directory")),
		)
		assertEquals(
			MimeType("image/x-uti.com.canon.cr3-raw-image"),
			UniformFileTypes.mimeOf(
				type("com.canon.cr3-raw-image", null, "public.camera-raw-image", "public.image"),
			),
		)
		assertEquals(
			MimeType("application/x-uti.com.apple.application-bundle"),
			UniformFileTypes.mimeOf(
				type("com.apple.application-bundle", null, "com.apple.package", "public.directory"),
			),
		)
		assertEquals("image", UniformFileTypes.mimeOf(type("x.y", null, "public.image")).media)
	}

	@Test
	fun aCategoryIsSaidAsAPersonWouldSayIt() {
		assertEquals(
			"Developer Tools",
			LaunchServicesApplications.categoryName("public.app-category.developer-tools"),
		)
		assertEquals(
			"Productivity",
			LaunchServicesApplications.categoryName("public.app-category.productivity"),
		)
	}

	@Test
	@EnabledOnOs(OS.MAC)
	fun aMacSaysWhatAFileIsAndWhatOpensIt() {
		val desktop = MacDesktop()
		val folder = Files.createTempDirectory("prism-types")
		val notes = folder.resolve("notes.txt").also { it.writeText("hello") }
		val readme = folder.resolve("README").also { it.writeText("words, and no extension") }

		assertEquals(MimeType("text/plain"), desktop.types.of(notes))
		assertEquals(MimeType.TEXT, desktop.types.of(readme))
		assertEquals(MimeType.FOLDER, desktop.types.of(folder))
		assertEquals(MimeType("image/png"), desktop.types.of(folder.resolve("missing.png")))
		assertNotNull(desktop.types.describe(MimeType("image/png")))
		assertTrue(MimeType("image/png") in desktop.types.lineage(MimeType("image/png")))
		assertTrue("*.png" in desktop.types.patternsOf(MimeType("image/png")))

		val textEdit = desktop.applications.all().firstOrNull { it.id == "com.apple.TextEdit" }

		assertNotNull(textEdit, "TextEdit among ${desktop.applications.all().map { it.id }}")
		assertNotNull(desktop.applications.defaultFor(MimeType("text/plain")))
		assertTrue(desktop.applications.forType(MimeType("text/plain")).isNotEmpty())
		assertNotNull(desktop.icons.find(textEdit!!.icon!!, 32, 2))
	}

	@Test
	@EnabledOnOs(OS.MAC)
	fun aMacsPlacesAreFindersAndItsVolumesAreShownWithRoom() {
		val home = Path.of(System.getProperty("user.home"))
		val desktop = MacDesktop()

		assertEquals(
			Place.Kind.Home,
			desktop.places
				.known(home)
				.first()
				.kind,
		)
		assertEquals(
			Path.of("/"),
			desktop.places
				.drives(home)
				.first()
				.path,
		)

		val computer =
			desktop.storage
				.read()
				.stores
				.first()

		assertTrue(computer.holdsSystem, "$computer")
		assertTrue(computer.writable, "$computer")
	}

	@Test
	@EnabledOnOs(OS.MAC)
	fun whatIsTrashedIsInTheSystemsTrashAndIsPutBack() {
		val home = Path.of(System.getProperty("user.home"))
		val folder = home.resolve("Library/Caches/prism-test").createDirectories()
		val file =
			folder.resolve("trash me ${System.nanoTime()}.txt").also {
				it.writeText("gone")
			}
		val trash = SystemTrash()

		assertEquals(TrashFor.In(home.resolve(".Trash")), trash.whereFor(file))

		val trashed = trash.put(file)

		assertFalse(file.exists())
		assertTrue(trashed.bin.resolve(trashed.name).exists())
		assertEquals(file, trash.putBack(trashed))
		assertTrue(file.exists())
		Files.delete(file)
		assertInstanceOf(TrashFor.Nowhere::class.java, trash.whereFor(Path.of("/")))
	}
}
