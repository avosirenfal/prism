package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import prism.platform.Dev
import prism.platform.KernelDev

/**
 * The volumes mounted: a `struct statfs` read as `<sys/mount.h>` lays it out (here, from bytes written at its offsets,
 * on any platform), and on a Mac the system's own list, with its sealed root and the data volume Finder does not show.
 */
class MountsTest {
	@Test
	fun aStatfsIsReadAsItIsLaidOut() {
		Arena.ofConfined().use { arena ->
			val statfs = arena.allocate(MacMounts.Statfs.SIZE, 8)
			val int = JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN)

			fun write(at: Long, text: String) =
				text.toByteArray().forEachIndexed {
					index,
					byte,
					->
					statfs.set(JAVA_BYTE, at + index, byte)
				}

			statfs.set(int, MacMounts.Statfs.FSID, (1 shl 24) or 5)
			statfs.set(int, MacMounts.Statfs.FLAGS, 0x1000 or 0x100000 or 0x800000)
			write(MacMounts.Statfs.TYPE_NAME, "apfs")
			write(MacMounts.Statfs.ON_NAME, "/System/Volumes/Data")
			write(MacMounts.Statfs.FROM_NAME, "/dev/disk3s5")

			val mount = MacMounts.mountOf(statfs, 7)

			assertEquals(7, mount.id)
			assertEquals(KernelDev(Dev(1, 5)), mount.dev)
			assertEquals(Path.of("/System/Volumes/Data"), mount.at)
			assertEquals("apfs", mount.type)
			assertEquals("/dev/disk3s5", mount.source)
			assertEquals(listOf("local", "nobrowse", "journaled"), mount.options)
			assertTrue(MountFlag.NoBrowse in mount)
		}
	}

	@Test
	fun aUuidIsItsBytesInOrderAndTheNilOneIsNone() {
		assertEquals(
			UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
			uuidOf(ByteArray(16) { (it * 0x11).toByte() }),
		)
		assertNull(uuidOf(ByteArray(16)))
	}

	@Test
	@EnabledOnOs(OS.MAC)
	fun aMacsVolumesAreItsSealedRootAndTheDataFinderDoesNotShow() {
		val mounts = MacMounts.current()
		val root = mounts.single { it.at == Path.of("/") }
		val data = mounts.singleOrNull { it.at == Path.of("/System/Volumes/Data") }

		assertEquals("apfs", root.type)
		assertTrue(MountFlag.Local in root)
		assertNotNull(data, "$mounts")
		assertTrue(MountFlag.NoBrowse in data!!, "$data")
		assertTrue(mounts.any { it.type == "devfs" && MountFlag.NoBrowse in it }, "$mounts")
		assertNotNull(volumeUuidOf(Path.of("/System/Volumes/Data")))
		// What a stat of a file says its device is, the volume's f_fsid says too.
		assertEquals(
			data.dev.dev,
			devOf(
				(
					java.nio.file.Files
						.getAttribute(Path.of("/Users"), "unix:dev") as Long
				).toInt(),
			),
		)
	}
}
