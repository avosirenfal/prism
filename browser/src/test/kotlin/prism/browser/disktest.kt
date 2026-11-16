package prism.browser

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.Trashed
import prism.desktop.freedesktop.Bin
import prism.desktop.freedesktop.FreedesktopTrash

/** What the JVM's disk does to real files when the picker asks it: renaming, making a folder, deleting, the trash. */
class DiskTest {
	@TempDir
	lateinit var work: Path

	@TempDir
	lateinit var bin: Path

	private val disk get() = JvmDisk(trash = FreedesktopTrash(bin))

	@Test
	fun aRenameNeverGoesOverWhatIsThereAndIsUndoneByRenamingBack() {
		work.resolve("a.txt").writeText("a")
		work.resolve("b.txt").writeText("b")

		val renamed = disk.make(Change.Rename(work.resolve("a.txt"), work.resolve("c.txt")))

		assertEquals(
			ChangeResult.Done(Change.Rename(work.resolve("c.txt"), work.resolve("a.txt"))),
			renamed,
		)
		assertEquals("a", work.resolve("c.txt").readText())
		assertEquals(
			ChangeResult.Failed("“b.txt” already exists here."),
			disk.make(Change.Rename(work.resolve("c.txt"), work.resolve("b.txt"))),
		)
		assertEquals("b", work.resolve("b.txt").readText())
	}

	@Test
	fun aFolderIsMadeOnlyWhereThereIsNothingAndRemovedOnlyEmpty() {
		val folder = work.resolve("New folder")

		assertEquals(
			ChangeResult.Done(Change.RemoveFolder(folder)),
			disk.make(Change.MakeFolder(folder)),
		)
		assertTrue(Files.isDirectory(folder))
		assertEquals(
			ChangeResult.Failed("“New folder” already exists here."),
			disk.make(Change.MakeFolder(folder)),
		)

		// Undone while something is in it, it is kept: an undo loses nothing.
		folder.resolve("kept.txt").writeText("kept")
		assertEquals(
			ChangeResult.Failed("“New folder” is not empty."),
			disk.make(Change.RemoveFolder(folder)),
		)
		assertEquals("kept", folder.resolve("kept.txt").readText())

		Files.delete(folder.resolve("kept.txt"))
		assertEquals(
			ChangeResult.Done(Change.MakeFolder(folder)),
			disk.make(Change.RemoveFolder(folder)),
		)
		assertFalse(folder.exists())
	}

	@Test
	fun theTrashIsTheDesktopsOwn() {
		val notes = work.resolve("notes.md").also { it.writeText("first") }

		assertEquals(
			ChangeResult.Trashed(
				Change.PutBack(listOf(Trashed(notes, "notes.md", bin))),
				emptyList(),
			),
			disk.make(Change.Trash(listOf(notes))),
		)
		assertFalse(notes.exists())
		assertEquals("first", bin.resolve("files/notes.md").readText())

		val info = bin.resolve("info/notes.md.trashinfo").readText()

		assertTrue(
			info.startsWith(
				"[Trash Info]\nPath=${FreedesktopTrash.escaped(notes.toString())}\nDeletionDate=",
			),
			info,
		)

		// A second of the same name is kept beside the first.
		work.resolve("notes.md").writeText("second")
		assertEquals(
			ChangeResult.Trashed(
				Change.PutBack(listOf(Trashed(notes, "notes.md.2", bin))),
				emptyList(),
			),
			disk.make(Change.Trash(listOf(work.resolve("notes.md")))),
		)
		assertEquals("second", bin.resolve("files/notes.md.2").readText())
		assertTrue(bin.resolve("info/notes.md.2.trashinfo").exists())
	}

	@Test
	fun whatIsTrashedIsPutBackWhereItWasButNeverOverWhatIsThereNow() {
		val notes = work.resolve("notes.md").also { it.writeText("mine") }
		val back = (disk.make(Change.Trash(listOf(notes))) as ChangeResult.Trashed).undo

		// Something of the same name made meanwhile: putting back would replace it, so it is refused.
		notes.writeText("newer")
		assertEquals(
			ChangeResult.Failed("“notes.md” already exists here."),
			disk.make(back),
		)
		assertEquals("newer", notes.readText())

		Files.delete(notes)
		assertEquals(ChangeResult.Done(Change.Trash(listOf(notes))), disk.make(back))
		assertEquals("mine", notes.readText())
		assertFalse(bin.resolve("files/notes.md").exists())
		assertFalse(bin.resolve("info/notes.md.trashinfo").exists())
		// Gone from the trash meanwhile: said so.
		assertEquals(
			ChangeResult.Failed("“notes.md” is no longer in the trash."),
			disk.make(back),
		)
	}

	@Test
	fun aNameTheTrashHoldsWithoutItsRecordIsPassedOver() {
		bin
			.resolve("files")
			.createDirectories()
			.resolve("notes.md")
			.writeText("left behind")

		val notes = work.resolve("notes.md").also { it.writeText("mine") }

		assertEquals(
			ChangeResult.Trashed(
				Change.PutBack(listOf(Trashed(notes, "notes.md.2", bin))),
				emptyList(),
			),
			disk.make(Change.Trash(listOf(notes))),
		)
		assertEquals("left behind", bin.resolve("files/notes.md").readText())
	}

	@Test
	fun aTrashedPathIsWrittenAsAUrlsPathIs() {
		assertEquals(
			"/home/a%20b/%C3%A9t%C3%A9%2B1.txt",
			FreedesktopTrash.escaped("/home/a b/été+1.txt"),
		)
		assertEquals(
			"[Trash Info]\nPath=/tmp/x\nDeletionDate=2026-11-09T10:20:30\n",
			FreedesktopTrash.infoOf(
				FreedesktopTrash.writtenPathOf(Path.of("/tmp/x"), Bin.Home(bin)),
				LocalDateTime.of(2026, 11, 9, 10, 20, 30, 500),
			),
		)
	}

	@Test
	fun somethingGoneIsSaidToBeGone() {
		assertEquals(
			ChangeResult.Failed("“gone.txt” no longer exists."),
			disk.make(Change.Rename(work.resolve("gone.txt"), work.resolve("x.txt"))),
		)
	}
}
