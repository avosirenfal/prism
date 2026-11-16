package prism.browser

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.TrashFor
import prism.desktop.Trashed
import prism.desktop.freedesktop.Bin
import prism.desktop.freedesktop.FreedesktopTrash
import prism.desktop.freedesktop.MountTable
import prism.platform.Dev
import prism.platform.KernelDev
import prism.platform.Mount

/**
 * The trash of each mount, as the freedesktop.org specification lays them out: the home trash for what is on its mount,
 * a trash at the top of every other, and none where what is mounted is kept in memory. The mounts are made up (a folder
 * each, under one temporary folder), so a test says which mount a folder is.
 */
class TrashTest {
	@TempDir
	lateinit var root: Path

	private val uid = prism.linux.uid()

	private val home get() = root.resolve("home")
	private val homeTrash get() = home.resolve(".local/share/Trash")
	private val drive get() = root.resolve("drive")
	private val memory get() = root.resolve("memory")

	/** The home folder, a drive, and a tmpfs, each a mount of its own. */
	private val mounts =
		object : MountTable {
			override fun all(): List<Mount> =
				listOf(
					mount(1, root, "ext4", "/dev/vda"),
					mount(2, home, "ext4", "/dev/vdb"),
					mount(3, drive, "vfat", "/dev/sdb1"),
					mount(4, memory, "tmpfs", "tmpfs"),
				)

			override fun of(path: Path): Mount? = MountTable.deepest(all(), path)
		}

	private fun mount(id: Long, at: Path, type: String, source: String) =
		Mount(id, KernelDev(Dev(0, id.toInt())), "/", at, type, source)

	private val trash get() = FreedesktopTrash(homeTrash, uid, mounts)

	private fun made(path: Path, text: String = "text"): Path =
		path.also {
			it.parent.createDirectories()
			it.writeText(text)
		}

	@Test
	fun whatIsOnTheHomeMountGoesToTheHomeTrash() {
		val notes = made(home.resolve("notes.md"))

		assertEquals(TrashFor.In(Bin.Home(homeTrash)), trash.binFor(notes))
		trash.put(notes)
		assertTrue(homeTrash.resolve("files/notes.md").exists())
		assertTrue(
			homeTrash
				.resolve(
					"info/notes.md.trashinfo",
				).readText()
				.contains("Path=${FreedesktopTrash.escaped(notes.toString())}\n"),
		)
	}

	@Test
	fun whatIsOnAnotherMountGoesToItsOwnTrashThere_MadeAsItIsNeeded_ItsRecordsFromTheTop() {
		val photo = made(drive.resolve("Photos/cat.png"))
		val own = drive.resolve(".Trash-$uid")

		assertFalse(own.exists())
		assertEquals(TrashFor.In(Bin.Top(own, drive)), trash.binFor(photo))

		val trashed = trash.put(photo)

		assertEquals(Trashed(photo, "cat.png", own), trashed)
		assertTrue(own.resolve("files/cat.png").exists())
		assertTrue(
			own.resolve("info/cat.png.trashinfo").readText().contains("Path=Photos/cat.png\n"),
		)
		assertEquals(
			"rwx------",
			PosixFilePermissions.toString(Files.getPosixFilePermissions(own)),
		)

		// The drive mounted elsewhere next time still knows where it goes back to, and it goes back there.
		assertEquals(listOf(photo), trash.entries().map { it.trashed.original })
		trash.putBack(trashed)
		assertTrue(photo.exists())
		assertFalse(own.resolve("info/cat.png.trashinfo").exists())
	}

	@Test
	fun aSharedTrashIsUsedOnlyWhereItIsStickyAndNoLink() {
		val shared = drive.resolve(".Trash").createDirectories()
		val file = made(drive.resolve("a.txt"))

		// Not sticky: anyone could take another's trash away, so it is not used at all.
		assertEquals(
			TrashFor.In(Bin.Top(drive.resolve(".Trash-$uid"), drive)),
			trash.binFor(file),
		)

		Files.setAttribute(shared, "unix:mode", "1777".toInt(8))
		assertEquals(TrashFor.In(Bin.Top(shared.resolve("$uid"), drive)), trash.binFor(file))

		// A link, sticky or not, is not used either.
		Files.delete(shared.resolve("$uid"))
		Files.delete(shared)
		drive.resolve("elsewhere").createDirectories().also {
			Files.setAttribute(it, "unix:mode", "1777".toInt(8))
		}
		shared.createSymbolicLinkPointingTo(drive.resolve("elsewhere"))
		assertEquals(
			TrashFor.In(Bin.Top(drive.resolve(".Trash-$uid"), drive)),
			trash.binFor(file),
		)
	}

	@Test
	fun whatIsKeptInMemoryHasNoTrash() {
		val scratch = made(memory.resolve("scratch.txt"))
		val where = trash.binFor(scratch)

		assertInstanceOf(TrashFor.Nowhere::class.java, where)
		assertTrue((where as TrashFor.Nowhere).why.contains("tmpfs"), where.why)
		assertFalse(memory.resolve(".Trash-$uid").exists())
	}

	@Test
	fun theDiskTrashesWhatHasATrashAndSaysWhatHasNone() {
		val disk = JvmDisk(trash)
		val notes = made(home.resolve("notes.md"))
		val scratch = made(memory.resolve("scratch.txt"))

		val result = disk.make(Change.Trash(listOf(scratch, notes))) as ChangeResult.Trashed

		assertEquals(listOf(Trashed(notes, "notes.md", homeTrash)), result.undo.entries)
		assertEquals(listOf(scratch), result.nowhere.map { it.path })
		assertTrue(scratch.exists())
		assertFalse(notes.exists())
	}

	@Test
	fun whatIsErasedIsGoneWithAllItHolds_ButNotWhatALinkLeadsTo() {
		val disk = JvmDisk(trash)
		val kept = made(home.resolve("kept/precious.txt"), "precious")
		val folder = home.resolve("doomed").createDirectories()

		made(folder.resolve("inner/deep.txt"))
		folder.resolve("link").createSymbolicLinkPointingTo(kept.parent)

		assertEquals(null, disk.erase(Erasing.Files(listOf(folder))))
		assertFalse(folder.exists())
		assertEquals("precious", kept.readText())
	}

	@Test
	fun theTrashsEntriesAreEveryTrashsAndAreDeletedForGoodAndEmptied() {
		val disk = JvmDisk(trash)

		trash.put(made(home.resolve("a.txt")))
		trash.put(made(drive.resolve("b.txt")))

		val rows = (disk.trash() as Listing.Shown).entries

		assertEquals(setOf("a.txt", "b.txt"), rows.map { it.name }.toSet())
		assertTrue(rows.all { it.item is Item.InTrash && it.modified != null })

		val a = rows.single { it.name == "a.txt" }.item as Item.InTrash

		assertEquals(null, disk.erase(Erasing.FromTrash(listOf(a.trashed))))
		assertEquals(listOf("b.txt"), (disk.trash() as Listing.Shown).entries.map { it.name })

		assertEquals(null, disk.erase(Erasing.Everything))
		assertEquals(emptyList<Row>(), (disk.trash() as Listing.Shown).entries)
		assertFalse(drive.resolve(".Trash-$uid/files/b.txt").exists())
	}

	@Test
	fun aRecordsPathIsReadBackAsItWasWritten() {
		val top = Bin.Top(drive.resolve(".Trash-$uid"), drive)

		assertEquals(
			drive.resolve("a b/é.txt"),
			FreedesktopTrash.originalOf(
				FreedesktopTrash.writtenPathOf(drive.resolve("a b/é.txt"), top),
				top,
			),
		)
		// A relative path that would climb out of its mount is not one this trash's entries can have.
		assertEquals(null, FreedesktopTrash.originalOf("../etc/passwd", top))
		assertEquals(
			Path.of("/etc/hosts"),
			FreedesktopTrash.originalOf("/etc/hosts", Bin.Home(homeTrash)),
		)
	}
}
