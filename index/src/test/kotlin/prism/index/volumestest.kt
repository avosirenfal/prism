package prism.index

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.linux.Dev
import prism.linux.KernelDev
import prism.linux.Mount

/**
 * Which file system a mount is of, read from what a system says of its devices; a tree handed out of an index as a
 * block, in its place or another; and the trees kept of file systems that are not mounted.
 */
class VolumesTest {
	@TempDir
	lateinit var system: Path

	@TempDir
	lateinit var files: Path

	@TempDir
	lateinit var state: Path

	private fun text(path: String, text: String) =
		system.resolve(path).also { it.parent.createDirectories() }.writeText(text)

	private fun link(path: String, target: String) =
		system.resolve(path).also {
			it.parent.createDirectories()
			Files.createSymbolicLink(it, Path.of(target))
		}

	/** A block device as /dev, sysfs and udev have it: [name], a partition of [disk] where it is one. */
	private fun device(
		name: String,
		number: String,
		disk: String? = null,
		removable: Boolean = false,
		udev: List<String>? = null,
	) {
		val folder = disk?.let { "sys/devices/block/$it/$name" } ?: "sys/devices/block/$name"

		text("dev/$name", "")
		text("$folder/dev", number)
		text("sys/devices/block/${disk ?: name}/dev", if (disk == null) number else "8:0")
		text("sys/devices/block/${disk ?: name}/removable", if (removable) "1" else "0")
		link("sys/class/block/$name", system.resolve(folder).toString())
		udev?.let { text("run/udev/data/b$number", it.joinToString("\n")) }
	}

	private fun mount(
		source: String,
		type: String = "ext4",
		root: String = "/",
		at: String = "/mnt/x",
	) = Mount(1, KernelDev(Dev(0, 40)), root, Path.of(at), type, source)

	@Test
	fun aFileSystemOnADeviceIsKnownByTheUuidWrittenInItWhereverItsDeviceIs() {
		device(
			"sdb1",
			"8:17",
			disk = "sdb",
			removable = true,
			udev = listOf("E:ID_FS_TYPE=vfat", "E:ID_FS_UUID=ABCD-1234"),
		)
		device("sdc1", "8:33", disk = "sdc", udev = listOf("E:ID_FS_UUID=ABCD-1234"))
		device("dm-3", "253:3", udev = listOf("E:ID_FS_UUID=7f1c", "E:ID_BUS=ata"))
		link("dev/mapper/home", system.resolve("dev/dm-3").toString())
		device("sdd1", "8:49", disk = "sdd", udev = listOf("E:ID_FS_UUID=beef", "E:ID_BUS=usb"))
		device("sde1", "8:65", disk = "sde")
		link("dev/disk/by-uuid/c0de", system.resolve("dev/sde1").toString())

		val volumes = Volumes(system) { null }

		// The same UUID is the same file system, whichever device it is on this time; one plugged in is portable.
		assertEquals(
			Volume(VolumeId("uuid:ABCD-1234:/"), portable = true),
			volumes.of(mount("/dev/sdb1")),
		)
		assertEquals(
			Volume(VolumeId("uuid:ABCD-1234:/"), portable = false),
			volumes.of(mount("/dev/sdc1")),
		)
		// Through a link, as a mapped device is named; on USB, though its disk does not say it is taken out.
		assertEquals(
			Volume(VolumeId("uuid:7f1c:/"), portable = false),
			volumes.of(mount("/dev/mapper/home")),
		)
		assertEquals(
			Volume(VolumeId("uuid:beef:/"), portable = true),
			volumes.of(mount("/dev/sdd1")),
		)
		// Where udev kept nothing, by the link that names it.
		assertEquals(
			Volume(VolumeId("uuid:c0de:/"), portable = false),
			volumes.of(mount("/dev/sde1")),
		)
		// A part of one mounted (a btrfs subvolume) is that part's.
		assertNotEquals(
			volumes.of(mount("/dev/sdc1", "btrfs", root = "/@home")),
			volumes.of(mount("/dev/sdc1", "btrfs", root = "/@")),
		)
		// A device nothing is known of, and what is on no device, are nothing that could be seen again; but a file
		// system that makes its own id from what is written in it is known by that.
		text("dev/sdf1", "")
		assertNull(volumes.of(mount("/dev/sdf1")))

		val asking = Volumes(system) { 0xABCL }

		assertEquals(
			Volume(VolumeId("fsid:ext4:abc:/"), portable = false),
			asking.of(mount("/dev/sdf1")),
		)
		assertNull(asking.of(mount("/dev/sdf1", "xfs")))
		assertEquals(
			Volume(VolumeId("uuid:c0de:/"), portable = false),
			asking.of(mount("/dev/sde1")),
		)
		assertNull(volumes.of(mount("tmpfs", "tmpfs")))
		assertNull(volumes.of(mount("overlay", "overlay")))
	}

	@Test
	fun zfsIsKnownByItsOwnIdAndAShareByWhatWasMounted() {
		val volumes =
			Volumes(system) { path -> 0x1234L.takeIf { path == Path.of("/tank/home") } }

		assertEquals(
			Volume(VolumeId("zfs:tank/home:1234"), portable = false),
			volumes.of(mount("tank/home", "zfs", at = "/tank/home")),
		)
		// One that cannot be asked is not known; nor is a snapshot, which is mounted only while it is looked into.
		assertNull(volumes.of(mount("tank/other", "zfs", at = "/tank/other")))
		assertNull(volumes.of(mount("tank/home@monday", "zfs", at = "/tank/home")))
		assertEquals(
			Volume(VolumeId("net:nfs4:nas:/export:/"), portable = true),
			volumes.of(mount("nas:/export", "nfs4")),
		)
		assertEquals(
			Volume(VolumeId("net:fuse.sshfs:me@host:/srv:/"), portable = true),
			volumes.of(mount("me@host:/srv", "fuse.sshfs")),
		)
	}

	private fun made(vararg paths: String) =
		paths.forEach {
			files
				.resolve(it)
				.also { path ->
					path.parent.createDirectories()
				}.writeText(it)
		}

	private fun names(tree: Block): List<String> =
		Index.of(setOf(tree.root), listOf(tree)).entries().map { it.where.toString() }

	@Test
	fun aTreeIsHandedOutOfAnIndexAsItIsThereOrAsItWouldBeSomewhereElse() {
		made("mnt/data/a.txt", "mnt/data/sub/b.txt", "home/x.txt")

		val indexer = Indexer(IgnoreRules(emptySet(), emptySet()), mounts = emptyList())
		val index =
			Index.EMPTY
				.with(indexer.index(files))
				// A graft under it, and something hidden: the tree is of what is held now.
				.with(indexer.reread(Where.of(files.resolve("mnt/data/sub")))!!)
				.with(Change.Absent(Where.of(files.resolve("mnt/data/a.txt"))))
		val at = Where.of(files.resolve("mnt/data"))

		assertEquals(
			listOf("$files/mnt/data", "$files/mnt/data/sub", "$files/mnt/data/sub/b.txt"),
			names(index.tree(at)!!),
		)

		val moved = index.tree(at, Where.of(Path.of("/run/media/elsewhere")))!!

		assertEquals(
			listOf(
				"/run/media/elsewhere",
				"/run/media/elsewhere/sub",
				"/run/media/elsewhere/sub/b.txt",
			),
			names(moved),
		)
		assertNull(index.tree(Where.of(files.resolve("nothing/here"))))
	}

	@Test
	fun aTreeKeptIsThereUntilItIsTakenOrLetGoInMemoryOrBesideTheIndexsFile() {
		made("mnt/data/a.txt", "mnt/data/sub/b.txt")

		val indexer = Indexer(IgnoreRules(emptySet(), emptySet()), mounts = emptyList())
		val at = Where.of(files.resolve("mnt/data"))
		val tree = Index.EMPTY.with(indexer.index(files)).tree(at)!!
		val since = Instant.parse("2026-11-01T10:00:00Z")
		val one = VolumeId("uuid:one:/")
		val two = VolumeId("zfs:tank/with spaces and / in it:1f")

		for (index in listOf(null, state.resolve("index.prism"))) {
			val away = Away.beside(index)

			assertEquals(emptyList<Kept>(), away.kept)
			away.keep(one, tree, since)
			away.keep(two, tree, since.plusSeconds(86_400))
			assertEquals(
				setOf(Kept(one, at, since, 4), Kept(two, at, since.plusSeconds(86_400), 4)),
				away.kept.toSet(),
			)

			// Beside a file, it is there for whoever opens the same file next.
			val again = if (index == null) away else Away.beside(index)

			assertEquals(2, again.kept.size)
			// Taken, it is the tree where it is mounted now, and no longer kept.
			assertEquals(
				listOf("/mnt/now", "/mnt/now/a.txt", "/mnt/now/sub", "/mnt/now/sub/b.txt"),
				names(again.take(one, Where.of(Path.of("/mnt/now")))!!),
			)
			assertNull(again.take(one, at))
			assertEquals(listOf(two), again.kept.map { it.volume })
			// Let go once it is older than is kept.
			assertEquals(emptyList<Kept>(), again.expire(since))
			assertEquals(listOf(two), again.expire(since.plusSeconds(90_000)).map { it.volume })
			assertTrue(again.kept.isEmpty())

			// Which file system is where, as it was noted.
			again.note(mapOf(at to one))
			assertEquals(
				mapOf(at to one),
				(
					if (index ==
						null
					) again else Away.beside(index)
				).mounted(),
			)
		}
	}
}
