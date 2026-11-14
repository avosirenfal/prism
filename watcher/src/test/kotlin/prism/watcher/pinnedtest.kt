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
}
