package prism.macos

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The folders an index leaves out for want of Full Disk Access: whole paths, each of them. */
class AccessTest {
	@Test
	fun everyProtectedFolderIsAWholePath() {
		val folders = protectedFolders(Path.of("/Users/a"))

		assertTrue(folders.all { it.isAbsolute && it == it.normalize() }, folders.toString())
		assertTrue(Path.of("/Users/a/Desktop") in folders)
		assertTrue(Path.of("/Users/a/Library/Mail") in folders)
		assertEquals(Path.of("/Volumes"), folders.last())
	}
}
