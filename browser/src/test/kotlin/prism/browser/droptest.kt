package prism.browser

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.FreedesktopTrash
import prism.desktop.freedesktop.XdgPlaces
import prism.window.DragKeys

/**
 * Files dropped on the browser: what a drop is made with no key held and with one; what the line under the listing says
 * of it; the files read out of what another application drags; and what the reducer makes of a drop on a folder, on
 * the listing of an explorer and of a picker, on disk, with undo.
 */
class DropTest {
	@TempDir
	lateinit var root: Path

	@TempDir
	lateinit var bin: Path

	private val disk get() = JvmDisk(FreedesktopTrash(bin))

	private fun made(vararg files: String) =
		files.forEach { name ->
			root.resolve(name.removeSuffix("/")).let {
				if (name.endsWith("/"))
					it.createDirectories()
				else
					it.also { file -> file.parent.createDirectories() }.writeText(name)
			}
		}

	private fun browsing(
		choosing: Choosing = Choosing.Exploring(),
		folder: Path = root,
	): BrowserState =
		opened(choosing, folder, root, sidebarOf(XdgPlaces(), root)).settled(disk)

	private fun after(state: BrowserState, vararg inputs: Input): BrowserState =
		inputs.fold(state) { at, input -> step(at, input).settled(disk) }

	@Test
	fun withNoKeyHeldADropMovesWithinAVolumeAndCopiesAcrossAndAKeyHeldSaysOtherwise() {
		assertEquals(Transfer.Move, transferOf(Asked.Default, sameVolume = true))
		assertEquals(Transfer.Copy, transferOf(Asked.Default, sameVolume = false))

		for (transfer in Transfer.entries)
			for (same in listOf(true, false))
				assertEquals(transfer, transferOf(Asked.Chosen(transfer), same))

		// The keys that ask: Ctrl copies, Shift moves and both link; on macOS, Option, Command and both.
		val none = DragKeys.None

		assertEquals(Asked.Default, askedOf(none, mac = false))
		assertEquals(Asked.Chosen(Transfer.Copy), askedOf(none.copy(ctrl = true), mac = false))
		assertEquals(Asked.Chosen(Transfer.Move), askedOf(none.copy(shift = true), mac = false))
		assertEquals(
			Asked.Chosen(Transfer.Link),
			askedOf(none.copy(ctrl = true, shift = true), mac = false),
		)
		assertEquals(Asked.Chosen(Transfer.Copy), askedOf(none.copy(alt = true), mac = true))
		assertEquals(Asked.Chosen(Transfer.Move), askedOf(none.copy(meta = true), mac = true))
		assertEquals(Asked.Default, askedOf(none.copy(ctrl = true), mac = true))
	}

	@Test
	fun theLineUnderTheListingSaysWhatADropWouldMake() {
		val documents = root.resolve("Documents")

		assertEquals(
			"Move 2 items into “Documents”",
			wordsOf(
				Hint(Dropping(2, Transfer.Move, documents), DropSpot.Into(documents)),
				exploring = true,
			),
		)
		assertEquals(
			"Copy 1 item here",
			wordsOf(Hint(Dropping(1, Transfer.Copy, root), DropSpot.Here), exploring = true),
		)
		assertEquals(
			"Link to them into “Documents”",
			wordsOf(
				Hint(Dropping(null, Transfer.Link, documents), DropSpot.Into(documents)),
				exploring = true,
			),
		)
		// A picker given files on its listing goes to them.
		assertEquals(
			"Go to the dropped item",
			wordsOf(Hint(Dropping(1, Transfer.Copy, root), DropSpot.Here), exploring = false),
		)
	}

	@Test
	fun theFilesAnotherApplicationGivesAreReadFromAUriList() {
		assertEquals(
			listOf(Path.of("/home/avery/a b.txt"), Path.of("/tmp/x")),
			pathsOfUris(
				"# dragged\r\nfile:///home/avery/a%20b.txt\r\nhttps://example.org/\r\nfile:///tmp/x\r\n\r\n",
			),
		)
	}

	@Test
	fun filesDroppedOnAFolderAreMovedCopiedOrLinkedThereAndUndone() {
		made("a.txt", "b.txt", "Documents/", "Documents/a.txt")

		val explorer = browsing()
		val documents = root.resolve("Documents")
		val a = root.resolve("a.txt")
		val b = root.resolve("b.txt")

		// A copy, beside what has the name already.
		val copied =
			after(
				explorer,
				BrowserEvent.Dropped(listOf(a), DropSpot.Into(documents), Transfer.Copy),
			)

		assertTrue(documents.resolve("a (copy).txt").exists())
		assertTrue(a.exists())

		// A link, under the name, or "Link to" it where the name is taken.
		after(
			copied,
			BrowserEvent.Dropped(listOf(a, b), DropSpot.Into(documents), Transfer.Link),
		)

		assertTrue(documents.resolve("Link to a.txt").isSymbolicLink())
		assertEquals(a, documents.resolve("Link to a.txt").readSymbolicLink())
		assertEquals(b, documents.resolve("b.txt").readSymbolicLink())

		// A move, undone.
		val moved =
			after(
				browsing(),
				BrowserEvent.Dropped(listOf(b), DropSpot.Into(documents), Transfer.Move),
			)

		assertFalse(b.exists())
		assertTrue(Files.exists(documents.resolve("b (copy).txt")))

		after(moved, BrowserEvent.Undone)
		assertTrue(b.exists())
	}

	@Test
	fun aDropThatWouldChangeNothingDoesNothing() {
		made("a.txt", "Documents/")

		val explorer = browsing()
		val documents = root.resolve("Documents")

		// Moved to the folder it is in, or a folder dropped on itself.
		assertEquals(
			emptyList<Effect>(),
			step(
				explorer,
				BrowserEvent.Dropped(listOf(root.resolve("a.txt")), DropSpot.Here, Transfer.Move),
			).effects,
		)
		assertEquals(
			emptyList<Effect>(),
			step(
				explorer,
				BrowserEvent.Dropped(listOf(documents), DropSpot.Into(documents), Transfer.Move),
			).effects,
		)
		// Copied to the folder it is in, though, it is a copy beside it, as a paste is.
		assertEquals(
			listOf(Effect.Make(Change.Copy(listOf(root.resolve("a.txt")), root))),
			step(
				explorer,
				BrowserEvent.Dropped(listOf(root.resolve("a.txt")), DropSpot.Here, Transfer.Copy),
			).effects,
		)
	}

	@Test
	fun filesDroppedOnAPickersListingAreGoneToAndOnItsFoldersGivenThem() {
		made("Documents/report.pdf", "Music/")

		val picker = browsing(Choosing.OneFile(), root.resolve("Music"))
		val report = root.resolve("Documents/report.pdf")
		val gone =
			after(picker, BrowserEvent.Dropped(listOf(report), DropSpot.Here, Transfer.Move))

		assertEquals(root.resolve("Documents"), gone.folder)
		assertEquals(listOf("report.pdf"), gone.chosenRows.map { it.name })
		assertTrue(report.exists())

		// On a folder, a picker moves files as an explorer does.
		assertEquals(
			listOf(Effect.Make(Change.Move(listOf(report), root.resolve("Music")))),
			step(
				picker,
				BrowserEvent.Dropped(
					listOf(report),
					DropSpot.Into(root.resolve("Music")),
					Transfer.Move,
				),
			).effects,
		)
	}

	@Test
	fun twoPathsOnOneFileSystemAreOnOneVolumeAndAPathNotThereIsWhereItWouldBe() {
		made("a.txt")

		assertTrue(disk.sameVolume(root.resolve("a.txt"), bin))
		assertTrue(disk.sameVolume(root.resolve("not/there/yet"), root))

		// Shared memory is a file system of its own wherever it is mounted, as on most Linux machines.
		val shared = Path.of("/dev/shm")

		assumeTrue(
			Files.isDirectory(shared) &&
				Files.getAttribute(shared, "unix:dev") != Files.getAttribute(root, "unix:dev"),
		)
		assertFalse(disk.sameVolume(root, shared))
		assertFalse(disk.sameVolume(root.resolve("a.txt"), shared.resolve("not-there")))
	}
}
