package prism.browser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.desktop.Boot
import prism.desktop.Bus
import prism.desktop.Drive
import prism.desktop.Part
import prism.desktop.Reached
import prism.desktop.Space
import prism.desktop.Storage
import prism.desktop.Store
import prism.desktop.StoreKind
import prism.preferences.Builtin

/**
 * The System page: opened from quick access, read, read again while it stays, left and come back to as any place is;
 * what it says of each store; and drawn, in both palettes, with `-Pbrowser.screenshots=DIR` writing what it drew there.
 */
@OptIn(ExperimentalTestApi::class)
class SystemPageTest {
	private val gib = 1L shl 30

	private fun drive(
		name: String,
		model: String,
		size: Long,
		bus: Bus,
		spins: Boolean = false,
		link: String? = null,
	) = Drive(name, model, size, bus, spins, removable = bus == Bus.Usb, link = link)

	/** A workstation's storage, as the desktop's own tests read one: see prism.desktop's StorageTest. */
	private val storage =
		Storage(
			listOf(
				Store(
					StoreKind.Internal,
					listOf(
						drive(
							"nvme0n1",
							"Samsung SSD 980 PRO 1TB",
							1000 * gib,
							Bus.Nvme,
							link = "/dev/disk/by-id/nvme-Samsung_SSD_980_PRO_1TB_S5GXNF0R123456",
						),
					),
					listOf(
						Part.Volume(
							"fedora",
							"btrfs",
							"/dev/mapper/root",
							Space(800 * gib, 500 * gib, 290 * gib),
							listOf(
								Reached.Mounted("@", Path("/"), null),
								Reached.Mounted("@home", Path("/home"), null),
							),
						),
						Part.Volume(
							null,
							"vfat",
							"/dev/nvme0n1p1",
							Space(gib / 2, gib / 8, gib * 3 / 8),
							listOf(Reached.Mounted("/boot/efi", Path("/boot/efi"), null)),
						),
						Part.Swap("nvme0n1p3", 16 * gib, gib),
						Part.Unallocated("nvme0n1", 183 * gib),
					),
					Boot.Firmware,
				),
				Store(
					StoreKind.Internal,
					listOf("sda", "sdb").map {
						drive(it, "WDC WD40EFRX-68N", 4000 * gib, Bus.Sata, spins = true)
					},
					listOf(
						Part.Volume(
							"tank",
							"zfs",
							"tank",
							Space(3800 * gib, 2200 * gib, 1600 * gib),
							listOf(
								Reached.Mounted(
									"tank/media",
									Path("/tank/media"),
									1540 * gib,
									listOf(Path("/srv/share/media")),
								),
								Reached.Unmounted("tank/backups", 650 * gib),
								Reached.Mounted("tank", Path("/tank"), 10 * gib),
							),
						),
					),
				),
				Store(
					StoreKind.Internal,
					listOf(drive("sdd", "CT1000MX500SSD1", 1000 * gib, Bus.Sata)),
					listOf(
						Part.Volume(
							null,
							"ext4",
							"/dev/sdd2",
							Space(98 * gib, 93 * gib, 3 * gib),
							listOf(Reached.Mounted("/data", Path("/data"), null)),
						),
						Part.Unmounted("sdd1", 900 * gib, "ntfs", "Windows"),
					),
				),
				Store(
					StoreKind.Removable,
					listOf(drive("sdc", "SanDisk Ultra", 64 * gib, Bus.Usb)),
					listOf(
						Part.Volume(
							"STICK",
							"vfat",
							"/dev/sdc1",
							Space(64 * gib, 10 * gib, 54 * gib),
							listOf(
								Reached.Mounted("/run/media/avery/STICK", Path("/run/media/avery/STICK"), null),
							),
						),
					),
				),
				Store(
					StoreKind.Network,
					emptyList(),
					listOf(
						Part.Volume(
							null,
							"nfs4",
							"nas:/export/media",
							Space(8000 * gib, 5000 * gib, 3000 * gib),
							listOf(Reached.Mounted("/mnt/nas", Path("/mnt/nas"), null)),
						),
						Part.Volume(
							null,
							"nfs4",
							"old:/gone",
							null,
							listOf(Reached.Mounted("/mnt/old", Path("/mnt/old"), null)),
						),
						Part.Volume(
							null,
							"nfs4",
							"nas:/export/archive",
							Space(2000 * gib, 2000 * gib, 0),
							listOf(Reached.Mounted("/mnt/archive", Path("/mnt/archive"), null)),
							readOnly = true,
						),
					),
				),
			),
		)

	private fun explorer(): BrowserState =
		opened(
			Choosing.Exploring(),
			Fixtures.home,
			Fixtures.home,
			Fixtures.sidebar,
		).settled(Fixtures.Memory)

	/** An explorer on the System page, [storage] read, after [inputs]. */
	private fun onSystem(vararg inputs: Input): BrowserState =
		inputs.fold(
			step(
				step(explorer(), BrowserEvent.OpenedSystem).state,
				Answer.StorageRead(storage),
			).state,
		) {
			state,
			input,
			->
			step(state, input).state
		}

	private fun screenshot(name: String, png: ByteArray) {
		System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
			Path(dir).createDirectories().resolve("$name.png").writeBytes(png)
		}
	}

	@Test
	fun theSystemPageIsReadAsItOpens_ReadAgainWhileItStays_AndComeBackToAsAnyPlace() {
		val explorer = explorer()
		val opening = step(explorer, BrowserEvent.OpenedSystem)

		assertEquals(listOf(Effect.ReadStorage), opening.effects)
		assertEquals(Location.System(), opening.state.at)

		val shown = step(opening.state, Answer.StorageRead(storage)).state

		assertEquals(Location.System(storage), shown.at)
		assertEquals("System", shown.placeName)
		assertTrue(
			shown.listing !is Listing.Reading,
			"it lists nothing, so it waits for nothing more",
		)

		// Opened again: read again, what it shows staying until the read arrives.
		val again = step(shown, BrowserEvent.OpenedSystem)

		assertEquals(listOf(Effect.ReadStorage), again.effects)
		assertEquals(storage, again.state.system?.storage)

		// Left for a folder, a read of it arriving late changes nothing; back to it, it is read afresh.
		val left = after(shown, BrowserEvent.Opened(Fixtures.home.resolve("Documents")))

		assertEquals(left, step(left, Answer.StorageRead(storage)).state)

		val back = step(left, BrowserEvent.Back)

		assertEquals(Location.System(), back.state.at)
		assertEquals(listOf(Effect.ReadStorage), back.effects)
	}

	@Test
	fun quickAccessOffersTheSystemPage_HiddenRatherThanRemoved() {
		val explorer = explorer()

		assertTrue(SidebarItem.System in explorer.sidebar.shown(explorer.role))
		assertEquals(BrowserEvent.OpenedSystem, openingOf(SidebarItem.System))

		val unpinned = step(explorer, BrowserEvent.Unpinned(SidebarItem.System)).state

		assertTrue(SidebarItem.System !in unpinned.sidebar.shown(unpinned.role))
		assertTrue(
			unpinned.sidebar.quick.items.any {
				it is prism.preferences.QuickItem.Own &&
					it.place == Builtin.System
			},
		)
	}

	@Test
	fun eachStoreIsSaidByItsDrives_AndThePageByWhatTheyMakeTogether() {
		assertEquals(
			listOf(
				"Samsung SSD 980 PRO 1TB",
				"2 × WDC WD40EFRX-68N",
				"CT1000MX500SSD1",
				"SanDisk Ultra",
				"Network",
			),
			storage.stores.map(::titleOf),
		)
		assertEquals(
			listOf(
				"NVMe SSD  ·  nvme0n1  ·  1000 GB",
				"Hard disks  ·  sda, sdb  ·  3.9 TB each",
				"SSD  ·  sdd  ·  1000 GB",
				"USB drive  ·  sdc  ·  64 GB",
				"3 shares",
			),
			storage.stores.map(::descriptionOf),
		)
		assertEquals(
			"5 drives and 3 network shares  ·  " +
				"${sizeOf(
					storage.stores.sumOf { it.free },
				)} free of ${sizeOf(storage.stores.sumOf { it.size })}",
			overviewOf(storage),
		)
		assertEquals("Loading…", statusOf(Location.System()))
	}

	@Test
	fun aBarsBlocksAreAsWideAsTheirParts_ButNoneTooNarrowToSee() {
		val widths = widthsOf(listOf(800L, 1L, 199L), 1000f, 10f)

		// The smallest at the least; what it takes beyond its share taken from the others, in their shares.
		assertEquals(10f, widths[1])
		assertEquals(1000f, widths.sum(), 0.01f)
		assertEquals(800f / 999 * 990, widths[0], 0.01f)
	}

	@Test
	fun eachKindOfStoreIsATabOfItsOwn_AndAStoreFoldsToItsBar_EachKeptAsThePageIsReadAgain() {
		val shown = onSystem(BrowserEvent.ShowedStores(StoreKind.Network))

		assertEquals(StoreKind.Network, shown.system?.shows)

		val key = storage.stores.first().key

		val folded =
			step(
				onSystem(BrowserEvent.FoldedStore(key)),
				Answer.StorageRead(storage),
			).state

		assertEquals(setOf(key), folded.system?.folded)
		assertEquals(
			emptySet<String>(),
			step(folded, BrowserEvent.FoldedStore(key)).state.system?.folded,
		)

		// Left and come back to, the tab and what was folded are as they were; what it shows is read afresh.
		val removable = onSystem(BrowserEvent.ShowedStores(StoreKind.Removable))
		val back = step(after(removable, BrowserEvent.Opened(Fixtures.home)), BrowserEvent.Back)

		assertEquals(Location.System(null, StoreKind.Removable), back.state.at)
	}

	@Test
	fun theSystemPageDrawsInBothPalettes() {
		// Each palette, its stores in the machine (one folded); a narrow window; and the other two tabs.
		val folded = BrowserEvent.FoldedStore(storage.stores[2].key)
		val removable = BrowserEvent.ShowedStores(StoreKind.Removable)
		val network = BrowserEvent.ShowedStores(StoreKind.Network)

		listOf(
			"light" to Triple(onSystem(folded), Palette.Light, 1100 to 1300),
			"dark" to Triple(onSystem(), Palette.Dark, 1100 to 900),
			"narrow" to Triple(onSystem(), Palette.Dark, 720 to 900),
			"removable" to Triple(onSystem(removable), Palette.Light, 1100 to 600),
			"network" to Triple(onSystem(network), Palette.Dark, 1100 to 700),
		).forEach { (name, look) ->
			val (state, palette, size) = look
			val png = renderedPng(state, size.first, size.second, palette, scale = 2f)

			assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
			screenshot("system-$name", png)
		}
	}

	@Test
	fun theSearchFieldNarrowsThePageToWhatHasWhatIsTyped() {
		fun shown(query: String) =
			storage.narrowed(query).stores.map { store -> titleOf(store) to store.parts.size }

		// A store by its drives' name, whole; a part by its folder, its label, its type; a pool by a dataset's name.
		assertEquals(listOf("2 × WDC WD40EFRX-68N" to 1), shown("wdc"))
		assertEquals(listOf("Samsung SSD 980 PRO 1TB" to 1), shown("/boot"))
		assertEquals(listOf("CT1000MX500SSD1" to 1), shown("windows"))
		assertEquals(listOf("2 × WDC WD40EFRX-68N" to 1), shown("backups"))
		assertEquals(listOf("Network" to 3), shown("network"))
		assertEquals(emptyList<Pair<String, Int>>(), shown("nothing like this"))
		assertEquals(storage, storage.narrowed("  "))
	}

	@Test
	fun aFileSystemMountedIsOpenedByAClick_AndOneThatDoesNotAnswerSaysSo() =
		runComposeUiTest {
			val events = ArrayList<BrowserEvent>()

			setContent {
				BrowserTheme {
					FileBrowser(onSystem()) { events += it }
				}
			}

			onNodeWithTag("part:/").performClick()
			assertEquals(
				listOf(BrowserEvent.Opened(Path("/"))),
				events.filterIsInstance<BrowserEvent.Opened>(),
			)
			// The store it was started from is marked so; the tabs say what each shows.
			onNodeWithTag("booted").assertExists()
			onNodeWithTag("stores:Network").performClick()
			assertEquals(BrowserEvent.ShowedStores(StoreKind.Network), events.last())
			onNodeWithTag("fold:${storage.stores.first().key}").performClick()
			assertEquals(BrowserEvent.FoldedStore(storage.stores.first().key), events.last())
		}

	@Test
	fun aStoreRightClickedOffersItsDrivesDevlinkToCopy() =
		runComposeUiTest {
			val events = ArrayList<BrowserEvent>()

			setContent {
				BrowserTheme {
					FileBrowser(onSystem()) { events += it }
				}
			}

			onNodeWithTag("store:Samsung SSD 980 PRO 1TB").performMouseInput {
				rightClick(Offset(centerX, 30f))
			}
			onNodeWithText("Copy device path").performClick()
			assertEquals(
				BrowserEvent.CopiedPaths(
					listOf(Path("/dev/disk/by-id/nvme-Samsung_SSD_980_PRO_1TB_S5GXNF0R123456")),
				),
				events.last(),
			)
		}

	@Test
	fun theNetworksTabSaysWhatDoesNotAnswer_AndWhatCanOnlyBeRead() =
		runComposeUiTest {
			setContent {
				BrowserTheme {
					FileBrowser(onSystem(BrowserEvent.ShowedStores(StoreKind.Network)))
				}
			}

			onNodeWithText("Not responding").assertExists()
			// A share that can only be read is full as it was made: said to be read-only, not to have nothing free.
			onNodeWithText("2.0 TB, read-only").assertExists()
			onNodeWithTag("system-bar").assertExists()
		}
}
