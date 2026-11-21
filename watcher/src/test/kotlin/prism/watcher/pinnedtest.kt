package prism.watcher

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What the loader does once the watcher is pinned, which is all of it that can be done without a kernel that takes
 * the watcher: who may read what is pinned, and where it is pinned.
 */
class PinnedTest {
	@TempDir
	lateinit var mount: Path

	private fun modeOf(path: Path): String =
		PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

	@Test
	fun theFolderAndTheMapsAreGivenToTheirReadersAndTheHooksAreLeft() {
		val folder = Files.createDirectory(mount.resolve("ea0285c31f2ed2a0"))
		val owner = Files.getOwner(folder)

		val private =
			PosixFilePermissions.asFileAttribute(
				PosixFilePermissions.fromString("rw-------"),
			)

		for (name in listOf("changes", "lost", "hook_exit_vfs_mkdir"))
			Files.createFile(folder.resolve(name), private)

		givenTo(folder, listOf("changes", "lost"), owner, group = null)

		assertEquals("rwxr-x---", modeOf(folder))
		assertEquals("rw-rw----", modeOf(folder.resolve("changes")))
		assertEquals("rw-rw----", modeOf(folder.resolve("lost")))
		assertEquals("rw-------", modeOf(folder.resolve("hook_exit_vfs_mkdir")))
	}

	@Test
	fun theWatcherIsPinnedUnderANameOfItsOwnText() {
		val pins = WatcherPins(Path.of("/run/prism-watcher"))

		assertEquals(Path.of("/run/prism-watcher", WatcherPins.FINGERPRINT), pins.folder)
		assertTrue(
			Regex("[0-9a-f]{16}").matches(WatcherPins.FINGERPRINT),
			WatcherPins.FINGERPRINT,
		)
		assertEquals(
			Path.of("/elsewhere"),
			WatcherPins.default(mapOf("PRISM_WATCHER" to "/elsewhere")).mount,
		)
		assertEquals(Path.of("/run/prism-watcher"), WatcherPins.default(emptyMap()).mount)
	}

	@Test
	fun aRingWhosePinWasTakenAwayOrPinnedAnewIsSaidToBeGone() {
		val folder = Files.createDirectory(mount.resolve("ea0285c31f2ed2a0"))
		val ring = Pin.ringIn(folder)

		Files.createFile(ring)

		val pin = Pin.at(ring)!!

		assertEquals(null, pin.gone())

		// The loader run again: what it pinned taken away, and a new ring pinned in its place.
		Files.delete(ring)
		assertEquals("the watcher pinned at $folder was unloaded", pin.gone())
		// (Made a moment later, as a loader run again makes it: a file system other than a BPF one may give it the
		// inode let go, and only when it was made tells the two apart.)
		Thread.sleep(50)
		Files.createFile(ring)
		assertEquals("the watcher pinned at $folder was replaced by a new one", pin.gone())
		// And nothing pinned to begin with is no pin.
		assertEquals(null, Pin.at(folder.resolve("nothing")))
	}
}
