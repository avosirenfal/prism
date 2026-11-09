package barion.picker

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** What the JVM's disk does to real files when the picker asks it: renaming, making a folder, deleting, the trash. */
class DiskTest {
	@TempDir
	lateinit var work: Path

	@TempDir
	lateinit var bin: Path

	private val disk get() = JvmDisk(trash = Trash(bin))

	@Test
	fun aRenameNeverGoesOverWhatIsThere() {
		work.resolve("a.txt").writeText("a")
		work.resolve("b.txt").writeText("b")

		assertEquals(
			ChangeResult.Done,
			disk.make(Change.Rename(work.resolve("a.txt"), work.resolve("c.txt"))),
		)
		assertEquals("a", work.resolve("c.txt").readText())
		assertEquals(
			ChangeResult.Failed("There is already something called “b.txt” here."),
			disk.make(Change.Rename(work.resolve("c.txt"), work.resolve("b.txt"))),
		)
		assertEquals("b", work.resolve("b.txt").readText())
	}

	@Test
	fun aFolderIsMadeOnlyWhereThereIsNothing() {
		assertEquals(
			ChangeResult.Done,
			disk.make(Change.MakeFolder(work.resolve("New folder"))),
		)
		assertTrue(Files.isDirectory(work.resolve("New folder")))
		assertEquals(
			ChangeResult.Failed("There is already something called “New folder” here."),
			disk.make(Change.MakeFolder(work.resolve("New folder"))),
		)
	}

	@Test
	fun deletingAFolderDeletesWhatItHoldsButNotWhatALinkInItLeadsTo() {
		val kept = work.resolve("elsewhere.txt").also { it.writeText("keep me") }
		val folder = work.resolve("folder/deeper").createDirectories().parent

		folder.resolve("deeper/x.txt").writeText("x")
		folder.resolve("link").createSymbolicLinkPointingTo(kept)

		assertEquals(ChangeResult.Done, disk.make(Change.Delete(listOf(folder))))
		assertFalse(folder.exists())
		assertEquals("keep me", kept.readText())
	}

	@Test
	fun theTrashIsTheDesktopsOwn() {
		val notes = work.resolve("notes.md").also { it.writeText("first") }

		assertEquals(ChangeResult.Done, disk.make(Change.Trash(listOf(notes))))
		assertFalse(notes.exists())
		assertEquals("first", bin.resolve("files/notes.md").readText())

		val info = bin.resolve("info/notes.md.trashinfo").readText()

		assertTrue(
			info.startsWith(
				"[Trash Info]\nPath=${Trash.escaped(notes.toString())}\nDeletionDate=",
			),
			info,
		)

		// A second of the same name is kept beside the first.
		work.resolve("notes.md").writeText("second")
		disk.make(Change.Trash(listOf(work.resolve("notes.md"))))
		assertEquals("second", bin.resolve("files/notes.md.2").readText())
		assertTrue(bin.resolve("info/notes.md.2.trashinfo").exists())
	}

	@Test
	fun aTrashedPathIsWrittenAsAUrlsPathIs() {
		assertEquals("/home/a%20b/%C3%A9t%C3%A9%2B1.txt", Trash.escaped("/home/a b/été+1.txt"))
		assertEquals(
			"[Trash Info]\nPath=/tmp/x\nDeletionDate=2026-11-09T10:20:30\n",
			Trash.infoOf(Path.of("/tmp/x"), LocalDateTime.of(2026, 11, 9, 10, 20, 30, 500)),
		)
	}

	@Test
	fun somethingGoneIsSaidToBeGone() {
		assertEquals(
			ChangeResult.Failed("“gone.txt” isn't there any more."),
			disk.make(Change.Rename(work.resolve("gone.txt"), work.resolve("x.txt"))),
		)
	}
}
