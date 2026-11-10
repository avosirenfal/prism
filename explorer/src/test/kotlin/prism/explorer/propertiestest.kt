package prism.explorer

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.desktop.Contents
import prism.desktop.Facts
import prism.desktop.FileKind
import prism.desktop.FileTypes
import prism.desktop.MimeType
import prism.desktop.Permissions

/** What the properties say of one file, a folder, and several, and their permissions ticked and unticked. */
class PropertiesTest {
	private val png = MimeType("image/png")

	private val types =
		object : FileTypes {
			override fun of(path: Path) = png

			override fun describe(type: MimeType) = "PNG image".takeIf { type == png }

			override fun lineage(type: MimeType) = listOf(type)
		}

	private val at = Instant.parse("2026-11-08T09:41:00Z")

	private fun facts(
		path: String,
		kind: FileKind = FileKind.File,
		size: Long = 0,
		type: MimeType = png,
	) = Facts(Path.of(path), type, kind, size, at, at, null, "avery", "users", null, null)

	private fun lines(facts: List<Facts>, counted: Contents?) =
		generalOf(facts, counted, types, ZoneOffset.UTC).associate { it.label to it.value }

	@Test
	fun aFileIsItsNameTypePlaceSizeAndTimes() {
		val cat = facts("/home/avery/Pictures/cat.png", size = 1_200_000)

		assertEquals(
			mapOf(
				"Name" to "cat.png",
				"Type" to "PNG image (image/png)",
				"Location" to "/home/avery/Pictures",
				"Size" to "1.1 MB (1,200,000 bytes)",
				"Created" to "Not known",
				"Modified" to "Sunday 8 November 2026, 09:41:00",
				"Accessed" to "Sunday 8 November 2026, 09:41:00",
			),
			lines(listOf(cat), tallyOf(listOf(cat)) { error("a file is not counted into") }),
		)
	}

	@Test
	fun aFolderSaysWhatItHoldsOnceItIsCounted() {
		val pictures = facts("/home/avery/Pictures", FileKind.Folder, type = MimeType.FOLDER)
		val counted =
			tallyOf(listOf(pictures)) { Contents(files = 120, folders = 8, bytes = 4_400_000) }

		assertEquals(Contents(120, 9, 4_400_000), counted)
		assertEquals("Folder", lines(listOf(pictures), null)["Type"])
		assertEquals("Counting…", lines(listOf(pictures), null)["Holds"])
		assertEquals("120 files and 8 folders", lines(listOf(pictures), counted)["Holds"])
		assertEquals("4.2 MB (4,400,000 bytes)", lines(listOf(pictures), counted)["Size"])
	}

	@Test
	fun severalAreCountedTogether() {
		val chosen =
			listOf(
				facts("/home/avery/a.png", size = 100),
				facts("/home/avery/b.txt", size = 50, type = MimeType.TEXT),
				facts("/home/avery/Pictures", FileKind.Folder, type = MimeType.FOLDER),
			)

		val said = lines(chosen, tallyOf(chosen) { Contents(1, 0, 1_000) })

		assertEquals("3 items, 2 files and 1 folder", said["Selected"])
		assertEquals("Several types", said["Type"])
		assertEquals("/home/avery", said["Location"])
		assertEquals("3 files and 1 folder", said["Holds"])
		assertEquals("1.1 KB (1,150 bytes)", said["Size"])
		assertFalse("Modified" in said)
	}

	@Test
	fun aPermissionIsTickedAndUntickedAlone() {
		val access = Permissions.Access(read = true, write = false, run = false)
		val permissions = Permissions(access, access, access)
		val writable = permissions.with(Who.Group, Right.Write, true)

		assertTrue(writable.allows(Who.Group, Right.Write))
		assertFalse(writable.allows(Who.Owner, Right.Write))
		assertFalse(writable.allows(Who.Others, Right.Write))
		assertEquals(permissions, writable.with(Who.Group, Right.Write, false))
	}
}
