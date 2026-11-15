package prism.desktop

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import prism.desktop.freedesktop.XdgPlaces
import prism.linux.Mounts

/**
 * The file systems a sidebar lists: the root, and what is mounted where desktops mount drives, read from a mount
 * table written out here as the kernel writes one. A folder there that nothing is mounted at is not among them.
 */
class DrivesTest {
	private val home = Path.of("/home/avery")

	/** One line of `/proc/self/mountinfo`, of [source] mounted [at]. */
	private fun line(id: Int, at: String, type: String, source: String) =
		"$id 1 8:$id / $at rw,relatime shared:1 - $type $source rw"

	private fun places(vararg lines: String) =
		XdgPlaces { Mounts.parse(lines.toList()).map { it.at } }

	@Test
	fun onlyWhatIsMountedIsAFileSystem_NotAFolderThatSomethingCouldBeMountedAt() {
		// `/mnt/tmp` is a folder on the root's own file system, and so no line of the table: it is not listed,
		// whether or not it is there. What is mounted deeper, or elsewhere, is not a drive of the desktop's either.
		val drives =
			places(
				line(1, "/", "ext4", "/dev/nvme0n1p2"),
				line(2, "/boot", "vfat", "/dev/nvme0n1p1"),
				line(3, "/mnt/windows", "ntfs3", "/dev/sda2"),
				line(4, "/mnt/games", "btrfs", "/dev/sdb1"),
				line(5, "/mnt/games/steam", "btrfs", "/dev/sdb1"),
				line(6, "/run/media/avery/STICK", "vfat", "/dev/sdc1"),
				line(7, "/run/media/someone/THEIRS", "vfat", "/dev/sdd1"),
				line(8, "/media/cdrom", "iso9660", "/dev/sr0"),
				line(9, "/home", "ext4", "/dev/nvme0n1p3"),
				line(10, "/proc", "proc", "proc"),
			).drives(home)

		assertEquals(
			listOf(
				"Root" to Path.of("/"),
				"cdrom" to Path.of("/media/cdrom"),
				"games" to Path.of("/mnt/games"),
				"windows" to Path.of("/mnt/windows"),
				"STICK" to Path.of("/run/media/avery/STICK"),
			),
			drives.map { it.label to it.path },
		)
		assertEquals(setOf(Place.Kind.Drive), drives.map { it.kind }.toSet())
	}

	@Test
	fun withNothingMountedThereTheRootIsTheOnlyOne() {
		assertEquals(
			listOf(Path.of("/")),
			places(line(1, "/", "ext4", "/dev/sda1")).drives(home).map { it.path },
		)
		// A machine whose kernel says nothing of its mounts still has a root.
		assertEquals(listOf(Path.of("/")), places().drives(home).map { it.path })
	}
}
