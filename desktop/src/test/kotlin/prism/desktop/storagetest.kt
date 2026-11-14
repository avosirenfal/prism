package prism.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.LinuxStorage
import prism.linux.Dev

/**
 * The machine's storage read from a machine made in a folder: its /sys, /proc and udev records, as a workstation's
 * would be. An NVMe drive holding the system (EFI, an encrypted btrfs with two subvolumes, swap, and room left
 * unpartitioned); two hard disks mirrored as a ZFS pool; an SSD with Windows on it, not mounted, beside a data
 * partition; a USB stick; and two network shares, one of which does not answer.
 */
class StorageTest {
	@TempDir
	lateinit var root: Path

	private val gib = 1L shl 30

	private fun write(relative: String, text: String) {
		root.resolve(relative).also { it.parent.createDirectories() }.writeText(text)
	}

	private fun link(relative: String, to: String) {
		val at = root.resolve(relative)

		at.parent.createDirectories()
		Files.createSymbolicLink(at, at.parent.relativize(root.resolve(to)))
	}

	/** A block device in sysfs at [device] (under `sys/devices`), linked from where the kernel links one. */
	private fun block(
		name: String,
		device: String,
		dev: Dev,
		bytes: Long,
		udev: Map<String, String> = emptyMap(),
	) {
		write("$device/dev", "$dev\n")
		write("$device/size", "${bytes / 512}\n")
		link("sys/class/block/$name", device)
		link("sys/dev/block/$dev", device)
		if (udev.isNotEmpty())
			write(
				"run/udev/data/b$dev",
				udev.entries.joinToString("\n") { "E:${it.key}=${it.value}" } + "\n",
			)
	}

	/** A drive, its partitions (name, size, what udev says of each), each partition numbered after the drive. */
	private fun drive(
		name: String,
		at: String,
		dev: Dev,
		bytes: Long,
		model: String,
		vendor: String? = null,
		spins: Boolean = false,
		removable: Boolean = false,
		partitions: List<Triple<String, Long, Map<String, String>>> = emptyList(),
		links: List<String> = emptyList(),
	) {
		val device = "sys/devices/$at/block/$name"

		block(name, device, dev, bytes)
		// udev's links to it, as its record lists them: under /dev, each its own line.
		if (links.isNotEmpty())
			write("run/udev/data/b$dev", links.joinToString("") { "S:$it\n" })
		link("sys/block/$name", device)
		write("$device/device/model", "$model\n")
		vendor?.let { write("$device/device/vendor", "$it\n") }
		write("$device/queue/rotational", if (spins) "1\n" else "0\n")
		write("$device/removable", if (removable) "1\n" else "0\n")
		partitions.forEachIndexed { at, (partition, size, udev) ->
			block(partition, "$device/$partition", Dev(dev.major, dev.minor + at + 1), size, udev)
			write("$device/$partition/partition", "${at + 1}\n")
		}
	}

	/** A device made of others (LUKS, LVM), in /sys/devices/virtual, its [slaves] what it is made of. */
	private fun virtual(
		name: String,
		dev: Dev,
		bytes: Long,
		slaves: List<String>,
		udev: Map<String, String> = emptyMap(),
	) {
		val device = "sys/devices/virtual/block/$name"

		block(name, device, dev, bytes, udev)
		link("sys/block/$name", device)
		slaves.forEach { write("$device/slaves/$it", "") }
	}

	private val mountinfo =
		"""
		20 1 0:25 /@ / rw,relatime shared:1 - btrfs /dev/mapper/root rw,subvolid=256,subvol=/@
		21 20 0:25 /@home /home rw,relatime shared:2 - btrfs /dev/mapper/root rw,subvolid=257,subvol=/@home
		22 20 259:1 / /boot/efi rw,relatime shared:3 - vfat /dev/nvme0n1p1 rw
		23 20 0:5 / /proc rw - proc proc rw
		24 20 0:6 / /sys rw - sysfs sysfs rw
		25 20 0:30 / /tmp rw - tmpfs tmpfs rw
		26 20 0:40 / /tank rw - zfs tank rw
		27 26 0:41 / /tank/media rw - zfs tank/media rw
		34 20 0:41 / /srv/share/media rw - zfs tank/media rw
		35 20 0:41 /pictures /srv/pictures rw - zfs tank/media rw
		28 20 8:33 / /run/media/user/STICK rw - vfat /dev/sdc1 rw
		29 20 8:50 / /data rw - ext4 /dev/sdd2 rw
		30 20 0:50 / /mnt/nas rw - nfs4 nas:/export/media rw
		31 20 0:51 / /mnt/old rw - nfs4 old:/gone rw
		32 20 7:0 / /snap/core/123 ro - squashfs /dev/loop0 ro
		33 21 0:25 /@home/user/projects /srv/projects rw - btrfs /dev/mapper/root rw,subvolid=257,subvol=/@home
		""".trimIndent()

	/** `zfs list -H -p`'s lines, as OpenZFS before 2.3 answers: name, used, available, usedbychildren. */
	private val zfsLines =
		listOf(
			"tank\t${2200 * gib}\t${1600 * gib}\t${2190 * gib}",
			"tank/media\t${1540 * gib}\t${1600 * gib}\t0",
			"tank/backups\t${650 * gib}\t${1600 * gib}\t0",
		).joinToString("\n")

	/**
	 * `zfs list -j -p`'s JSON, as OpenZFS 2.3 answers, with what is not read here (its version, each dataset's type,
	 * pool and source) and what a later version might add (a field of its own at each level), and a value written as a
	 * number rather than text, as `--json-int` writes it.
	 */
	private val zfsJson =
		"""
		{"output_version":{"command":"zfs list","vers_major":0,"vers_minor":1},"a_later_field":[1,2],
		 "datasets":{
		  "tank":{"name":"tank","type":"FILESYSTEM","pool":"tank","createtxg":"1","newer":{"x":true},
		   "properties":{"used":{"value":"${2200 * gib}","source":{"type":"NONE","data":"-"}},
		    "available":{"value":"${1600 * gib}","source":{"type":"NONE","data":"-"}},
		    "usedbychildren":{"value":"${2190 * gib}","source":{"type":"NONE","data":"-"}},
		    "something_new":{"value":"?"}}},
		  "tank/media":{"name":"tank/media","type":"FILESYSTEM","pool":"tank","createtxg":"9",
		   "properties":{"used":{"value":${1540 * gib}},"available":{"value":"${1600 * gib}"},
		    "usedbychildren":{"value":"0"}}},
		  "tank/backups":{"name":"tank/backups","type":"FILESYSTEM","pool":"tank","createtxg":"11",
		   "properties":{"used":{"value":"${650 * gib}"},"available":{"value":"${1600 * gib}"},
		    "usedbychildren":{"value":"0"}}},
		  "tank/broken":{"name":"tank/broken","properties":{"used":{"value":"not a number"}}}
		 }}
		""".trimIndent()

	/** The workstation: see the class's comment. [udev] whether udev's records are there (a container has none). */
	private fun workstation(udev: Boolean = true) {
		fun record(vararg pairs: Pair<String, String>) = if (udev) mapOf(*pairs) else emptyMap()

		drive(
			"nvme0n1",
			"pci0000:00/0000:00:01.1/0000:01:00.0/nvme/nvme0",
			Dev(259, 0),
			1000 * gib,
			"Samsung SSD 980 PRO 1TB",
			partitions =
				listOf(
					Triple(
						"nvme0n1p1",
						gib / 2,
						record(
							"ID_FS_TYPE" to "vfat",
							"ID_PART_ENTRY_UUID" to "4d3c2b1a-1111-4222-8333-944455556666",
						),
					),
					Triple("nvme0n1p2", 800 * gib, record("ID_FS_TYPE" to "crypto_LUKS")),
					// Swap encrypted with a key of the moment: nothing on the partition says it is swap.
					Triple("nvme0n1p3", 16 * gib, record("ID_PART_ENTRY_NAME" to "disk-swap")),
				),
			links =
				if (udev)
					listOf(
						"disk/by-id/nvme-eui.002538b1110a5c3e",
						"disk/by-id/nvme-Samsung_SSD_980_PRO_1TB_S5GXNF0R123456",
						"disk/by-path/pci-0000:01:00.0-nvme-1",
					)
				else
					emptyList(),
		)
		virtual("dm-1", Dev(253, 1), 16 * gib, listOf("nvme0n1p3"))
		virtual(
			"dm-0",
			Dev(253, 0),
			800 * gib,
			listOf("nvme0n1p2"),
			record("ID_FS_LABEL" to "fedora"),
		)
		write("sys/fs/btrfs/5e2a-btrfs/devices/dm-0", "")
		for ((index, name) in listOf("sda", "sdb").withIndex())
			drive(
				name,
				"pci0000:00/0000:00:17.0/ata${index + 1}/host$index/target$index:0:0/$index:0:0:0",
				Dev(8, index * 16),
				4000 * gib,
				"WDC WD40EFRX-68N",
				vendor = "ATA",
				spins = true,
				links =
					if (udev)
						listOf(
							"disk/by-id/wwn-0x50014ee2b5$index",
							"disk/by-id/ata-WDC_WD40EFRX-68N32N0_WD-WCC7K12345$index",
						)
					else
						emptyList(),
				partitions =
					listOf(
						Triple(
							"${name}1",
							// All of the disk but a few MiB at its end, as ZFS partitions a whole disk.
							4000 * gib - (8L shl 20),
							record("ID_FS_TYPE" to "zfs_member", "ID_FS_LABEL" to "tank"),
						),
					),
			)
		drive(
			"sdc",
			"pci0000:00/0000:00:14.0/usb1/1-2/1-2:1.0/host6/target6:0:0/6:0:0:0",
			Dev(8, 32),
			64 * gib,
			"Ultra",
			vendor = "SanDisk",
			removable = true,
			partitions =
				listOf(
					Triple(
						"sdc1",
						64 * gib,
						record(
							"ID_FS_TYPE" to "vfat",
							"ID_FS_LABEL" to "STICK",
							"ID_PART_ENTRY_UUID" to "0badc0de-0000-4000-8000-00000000beef",
						),
					),
				),
		)
		drive(
			"sdd",
			"pci0000:00/0000:00:17.0/ata4/host3/target3:0:0/3:0:0:0",
			Dev(8, 48),
			1000 * gib,
			"CT1000MX500SSD1",
			vendor = "ATA",
			partitions =
				listOf(
					Triple(
						"sdd1",
						900 * gib,
						record("ID_FS_TYPE" to "ntfs", "ID_FS_LABEL" to "Windows"),
					),
					Triple("sdd2", 100 * gib, record("ID_FS_TYPE" to "ext4")),
				),
		)
		virtual("loop0", Dev(7, 0), gib / 8, emptyList())
		write("proc/self/mountinfo", mountinfo + "\n")
		write(
			"proc/swaps",
			"Filename\tType\tSize\tUsed\tPriority\n/dev/dm-1 partition\t16777212\t1048576\t-2\n",
		)
	}

	private val spaces =
		mapOf(
			"/" to Space(800 * gib, 500 * gib, 290 * gib),
			"/home" to Space(800 * gib, 500 * gib, 290 * gib),
			"/boot/efi" to Space(gib / 2, gib / 8, gib * 3 / 8),
			"/run/media/user/STICK" to Space(64 * gib, 10 * gib, 54 * gib),
			"/data" to Space(98 * gib, 60 * gib, 33 * gib),
			"/mnt/nas" to Space(8000 * gib, 5000 * gib, 3000 * gib),
			"/tank" to Space(1609 * gib, 9 * gib, 1600 * gib),
			"/tank/media" to Space(3140 * gib, 1540 * gib, 1600 * gib),
			"/srv/share/media" to Space(3140 * gib, 1540 * gib, 1600 * gib),
		)

	/**
	 * The workstation read: [zfs] whether `zfs list` answers, and [json] whether it answers JSON (OpenZFS 2.3 and
	 * later) or only its lines; /mnt/old takes longer than it is given.
	 */
	private fun read(
		zfs: Boolean = true,
		json: Boolean = true,
		lines: Boolean = true,
		zpool: String? = null,
	): Storage =
		LinuxStorage(
			root,
			space = { at ->
				if (at == Path.of("/mnt/old"))
					Thread.sleep(5_000)
				spaces[at.toString()]
			},
			run = { command ->
				when (command.firstOrNull()) {
					"zfs" ->
						when {
							!zfs -> null
							"-j" in command -> zfsJson.takeIf { json }
							else -> zfsLines.takeIf { lines }
						}
					"zpool" -> zpool
					else -> null
				}
			},
			rdev = { path -> Dev(253, 0).takeIf { path == root.resolve("dev/mapper/root") } },
		).read()

	@Test
	fun drivesAreGroupedByWhatSharesTheirRoom_TheSystemsFirst() {
		workstation()

		val stores = read().stores

		// The system's drive; the pool's two disks, together; the other SSD; the stick; the network.
		assertEquals(
			listOf(
				listOf("nvme0n1"),
				listOf("sda", "sdb"),
				listOf("sdd"),
				listOf("sdc"),
				emptyList(),
			),
			stores.map { store -> store.drives.map { it.name } },
		)
		assertEquals(
			listOf(
				StoreKind.Internal,
				StoreKind.Internal,
				StoreKind.Internal,
				StoreKind.Removable,
				StoreKind.Network,
			),
			stores.map { it.kind },
		)
		assertTrue(stores.first().holdsSystem)

		val (system, pool, ssd, stick) = stores
		val nvme = system.drives.single()

		assertEquals("Samsung SSD 980 PRO 1TB", nvme.model)
		assertEquals(Bus.Nvme, nvme.bus)
		assertEquals(listOf(Bus.Sata, Bus.Sata), pool.drives.map { it.bus })
		assertTrue(pool.drives.all { it.spins && it.model == "WDC WD40EFRX-68N" })
		assertEquals("SanDisk Ultra", stick.drives.single().model)
		assertTrue(stick.drives.single().removable)
		assertEquals(Bus.Usb, stick.drives.single().bus)
		assertEquals(1000 * gib, ssd.drives.single().size)
	}

	@Test
	fun theSystemsDriveHoldsItsFileSystems_ItsSwap_AndItsRoomUnpartitioned() {
		workstation()

		val parts = read().stores.first().parts
		val btrfs = parts[0] as Part.Volume
		val efi = parts[1] as Part.Volume

		// The btrfs over LUKS: one file system, reached at its two subvolumes, and not at a folder of one mounted again.
		assertEquals("btrfs", btrfs.type)
		assertEquals("fedora", btrfs.label)
		assertEquals(listOf(Path.of("/"), Path.of("/home")), btrfs.reached.map { it.at })
		assertEquals(listOf("@", "@home"), btrfs.reached.map { it.name })
		assertEquals(Space(800 * gib, 500 * gib, 290 * gib), btrfs.space)
		assertEquals(Path.of("/"), btrfs.at)
		assertEquals(listOf(Path.of("/boot/efi")), efi.reached.map { it.at })
		assertEquals(Part.Swap("nvme0n1p3", 16 * gib - 4096, gib), parts[2])
		assertEquals(
			Part.Unallocated("nvme0n1", 1000 * gib - gib / 2 - 800 * gib - 16 * gib),
			parts[3],
		)
		assertEquals(4, parts.size)
	}

	@Test
	fun aPoolSharesItsRoomAmongItsDatasets_EachHoldingItsOwn() {
		workstation()

		val pool = read().stores[1].parts.single() as Part.Volume

		assertEquals("tank", pool.label)
		assertEquals(Space(3800 * gib, 2200 * gib, 1600 * gib), pool.space)
		// Each dataset's own use, biggest first, at every folder the kernel says it is mounted at (whatever mounted it:
		// its `mountpoint` is not asked), but not where a folder of it is mounted again; the pool's own (its snapshots,
		// here) under its own name; one not mounted, as it holds room all the same. A dataset `zfs list` gives nothing
		// readable of is left out.
		assertEquals(
			listOf(
				Reached.Mounted(
					"tank/media",
					Path.of("/tank/media"),
					1540 * gib,
					listOf(Path.of("/srv/share/media")),
				),
				Reached.Unmounted("tank/backups", 650 * gib),
				Reached.Mounted("tank", Path.of("/tank"), 10 * gib),
			),
			pool.reached,
		)
		assertEquals(pool.space!!.used, pool.reached.sumOf { it.holds!! })
		assertTrue(pool.pooled)
	}

	@Test
	fun zfsListIsAskedForJsonFirst_AndForItsLinesWhereItCannotGiveThat() {
		workstation()

		// Its JSON alone, read past what this does not know; and OpenZFS before 2.3, which knows no `-j`, the same in its
		// lines.
		val pool = read(lines = false).stores[1]

		assertEquals(3, (pool.parts.single() as Part.Volume).reached.size)
		assertEquals(pool, read(json = false).stores[1])
	}

	@Test
	fun withoutZfsListAPoolIsMeasuredByWhatItsDatasetsMountedSay() {
		workstation()

		val pool = read(zfs = false).stores[1].parts.single() as Part.Volume

		// What each refers to itself; the pool free as any of them says. Snapshots and datasets not mounted unseen.
		assertEquals(
			listOf(
				Reached.Mounted(
					"tank/media",
					Path.of("/tank/media"),
					1540 * gib,
					listOf(Path.of("/srv/share/media")),
				),
				Reached.Mounted("tank", Path.of("/tank"), 9 * gib),
			),
			pool.reached,
		)
		assertEquals(Space(3149 * gib, 1549 * gib, 1600 * gib), pool.space)
	}

	@Test
	fun withoutUdevAPoolsDrivesAreThoseZpoolNames() {
		workstation(udev = false)

		val zpool =
			"tank\t3.62T\t2.14T\t1.48T\t-\t-\t3%\t59%\t1.00x\tONLINE\t-\n" +
				"\tmirror-0\t3.62T\t2.14T\t1.48T\t-\t-\t3%\t59.1%\t-\tONLINE\n" +
				"\t\t/dev/sda1\t-\t-\t-\t-\t-\t-\t-\t-\tONLINE\n" +
				"\t\t/dev/sdb1\t-\t-\t-\t-\t-\t-\t-\t-\tONLINE\n"
		val stores = read(zpool = zpool).stores

		assertEquals(listOf("sda", "sdb"), stores[1].drives.map { it.name })
		assertEquals("tank", (stores[1].parts.single() as Part.Volume).label)
	}

	@Test
	fun aPartitionWithNothingMountedSaysWhatIsOnIt() {
		workstation()

		val ssd = read().stores[2]

		assertEquals(listOf("ext4"), ssd.parts.filterIsInstance<Part.Volume>().map { it.type })
		assertEquals(
			Part.Unmounted("sdd1", 900 * gib, "ntfs", "Windows"),
			ssd.parts.filterIsInstance<Part.Unmounted>().single(),
		)
		// What it has free is its file systems' alone: what is on the Windows partition is not known.
		assertEquals(33 * gib, ssd.free)
	}

	@Test
	fun sharesAreTheNetworks_AndOneThatDoesNotAnswerIsSaidToHaveNoSpace() {
		workstation()

		val started = System.nanoTime()
		val network = read().stores.last()
		val waited = (System.nanoTime() - started) / 1_000_000

		assertEquals(StoreKind.Network, network.kind)
		assertEquals(
			listOf(
				"nas:/export/media" to Space(8000 * gib, 5000 * gib, 3000 * gib),
				"old:/gone" to null,
			),
			network.parts.map { (it as Part.Volume).source to it.space },
		)
		// Given its moment, and no more: the whole read does not wait for the share that is down.
		assertTrue(waited < 4_000, "waited ${waited}ms")
	}

	@Test
	fun aFileSystemMountedReadOnlyIsSaidToBe() {
		workstation()
		write(
			"proc/self/mountinfo",
			mountinfo.replace(
				"/data rw - ext4 /dev/sdd2 rw",
				"/data ro - ext4 /dev/sdd2 ro,norecovery",
			) +
				"\n",
		)

		val volumes = read().stores.flatMap { it.parts }.filterIsInstance<Part.Volume>()

		assertEquals(listOf("/dev/sdd2"), volumes.filter { it.readOnly }.map { it.source })
	}

	/** The EFI variable [name] of [vendor], holding [data] after its four bytes of attributes. */
	private fun efi(name: String, vendor: String, data: ByteArray) {
		root
			.resolve("sys/firmware/efi/efivars/$name-$vendor")
			.also {
				it.parent.createDirectories()
			}.toFile()
			.writeBytes(byteArrayOf(7, 0, 0, 0) + data)
	}

	private val global = "8be4df61-93ca-11d2-aa0d-00e098032b8c"

	private fun little(value: Long, bytes: Int) =
		ByteArray(bytes) {
			(value ushr (8 * it)).toByte()
		}

	@Test
	fun theDriveTheComputerWasStartedFromIsTheOneTheBootLoaderRecords() {
		workstation()
		// systemd-boot's record: the EFI partition's GUID, as UTF-16 text.
		efi(
			"LoaderDevicePartUUID",
			"4a67b082-0a4c-41cf-b6c7-440b29bb8c4f",
			"4D3C2B1A-1111-4222-8333-944455556666\u0000".toByteArray(Charsets.UTF_16LE),
		)

		val stores = read().stores

		assertEquals(listOf(Boot.Firmware, null, null, null, null), stores.map { it.boot })
	}

	@Test
	fun withoutTheLoadersRecordItIsTheOneTheFirmwaresBootEntryNames_ALiveSticksHere() {
		workstation()

		// Boot0003: started from the stick, its device path a PCI node, the hard drive node of the stick's partition
		// (number 1, its GUID, a GPT signature), and the end.
		val guid =
			byteArrayOf(
				0xde.toByte(),
				0xc0.toByte(),
				0xad.toByte(),
				0x0b,
				0,
				0,
				0,
				0x40,
				0x80.toByte(),
				0,
				0,
				0,
				0,
				0,
			) +
				byteArrayOf(0xbe.toByte(), 0xef.toByte())

		val pci = byteArrayOf(1, 1, 6, 0, 0, 0x14)

		val drive =
			byteArrayOf(
				4,
				1,
				42,
				0,
			) + little(1, 4) + little(2048, 8) + little(1 shl 27, 8) + guid +
				byteArrayOf(2, 2)

		val path = pci + drive + byteArrayOf(0x7f, 0xff.toByte(), 4, 0)
		val entry =
			little(1, 4) + little(path.size.toLong(), 2) +
				"USB\u0000".toByteArray(Charsets.UTF_16LE) +
				path

		efi("BootCurrent", global, little(3, 2))
		efi("Boot0003", global, entry)

		val stores = read().stores

		assertEquals(listOf(null, null, null, Boot.Firmware, null), stores.map { it.boot })
		assertEquals("SanDisk Ultra", stores[3].drives.single().model)
	}

	@Test
	fun whereNothingSaysWhereItWasStartedFrom_TheDriveTheSystemIsOnIsSaidToBe() {
		workstation()

		assertEquals(listOf(Boot.Root, null, null, null, null), read().stores.map { it.boot })
	}

	@Test
	fun eachDrivesDevlinkIsItsLinkByModelAndSerial_BeforeItsWorldwideName() {
		workstation()

		val drives = read().stores.flatMap { it.drives }.associate { it.name to it.link }

		assertEquals(
			"/dev/disk/by-id/nvme-Samsung_SSD_980_PRO_1TB_S5GXNF0R123456",
			drives["nvme0n1"],
		)
		assertEquals("/dev/disk/by-id/ata-WDC_WD40EFRX-68N32N0_WD-WCC7K123450", drives["sda"])
		assertEquals("/dev/disk/by-id/ata-WDC_WD40EFRX-68N32N0_WD-WCC7K123451", drives["sdb"])
		// One udev has no links of: none, rather than a guess.
		assertNull(drives["sdc"])
	}

	@Test
	fun whatHoldsNoDrivesFilesIsLeftOut() {
		workstation()

		val reached =
			read()
				.stores
				.flatMap {
					it.parts
				}.filterIsInstance<Part.Volume>()
				.flatMap { it.reached }

		for (left in listOf(
			"/proc",
			"/sys",
			"/tmp",
			"/snap/core/123",
			"/srv/projects",
			"/srv/pictures",
		))
			assertTrue(reached.none { it.at == Path.of(left) }, "$left is shown")
		assertNull(
			read().stores.flatMap { it.drives }.firstOrNull {
				it.name == "loop0" ||
					it.name == "dm-0"
			},
		)
	}

	@Test
	fun theMachinesOwnStorageIsRead() {
		// Whatever this machine has: read without failing, and each store's parts as large as they say together.
		val storage = LinuxStorage().read()

		for (store in storage.stores)
			assertEquals(store.parts.sumOf { it.size }, store.size)
	}
}
