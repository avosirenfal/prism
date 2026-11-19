package prism.macos

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A Mac's bundles, kept as the disk changes: what each kind of change does to what is kept. */
class BundlesTest {
	private fun bundle(path: String) = BundlePath.of(Path.of(path))!!

	/** A disk of [bundles], each in its folder; [folders] what each of them holds. */
	private class Disk(
		val bundles: Set<BundlePath>,
		val folders: Map<String, List<BundlePath>> = emptyMap(),
	) : BundleDisk {
		override fun holds(bundle: BundlePath): Boolean = bundle in bundles

		override fun bundlesIn(folder: Path): List<BundlePath> =
			folders[folder.toString()].orEmpty()
	}

	private fun event(path: String, vararg flags: FsFlag) =
		FsEvent(Path.of(path), flags.toSet(), 1)

	private val safari = bundle("/Applications/Safari.app")
	private val firefox = bundle("/Applications/Firefox.app")

	@Test
	fun aBundlesPathIsAWholePathNamedAsABundleIs() {
		assertNull(BundlePath.of(Path.of("/Applications/Notes")))
		assertNull(BundlePath.of(Path.of("Safari.app")))
		assertEquals(
			safari,
			BundlePath.holding(Path.of("/Applications/Safari.app/Contents/Info.plist")),
		)
		assertEquals(
			bundle("/Applications/Xcode.app/Contents/Applications/Simulator.app"),
			BundlePath.holding(
				Path.of(
					"/Applications/Xcode.app/Contents/Applications/Simulator.app/Contents/Info.plist",
				),
			),
		)
	}

	private val xcode = bundle("/Applications/Xcode.app")
	private val simulator =
		bundle("/Applications/Xcode.app/Contents/Developer/Applications/Simulator.app")
	private val wrapper = bundle("/Applications/Notes Pad.app")
	private val wrapped = bundle("/Applications/Notes Pad.app/Wrapper/NotesPad.app")

	@Test
	fun aBundleIsAloneInsideAnotherOrTheOneAWrapperWraps() {
		assertEquals(Placement.Alone, safari.placement)
		assertEquals(Placement.Within(xcode), simulator.placement)
		assertEquals(Placement.WrappedBy(wrapper), wrapped.placement)
		// A folder named Wrapper in no bundle wraps nothing.
		assertEquals(Placement.Alone, bundle("/Users/a/Wrapper/Tool.app").placement)
	}

	@Test
	fun whatIsListedIsWhatCanShowAWindowAndIsNoOtherApplicationsHelper() {
		val listed =
			listOf(safari, simulator, wrapped).associateWith { bundle ->
				ActivationPolicy.entries.filter(bundle::startedAs)
			}

		assertEquals(
			mapOf(
				// Safari, or Rectangle in the menu bar; not a faceless agent.
				safari to listOf(ActivationPolicy.Regular, ActivationPolicy.Accessory),
				// Xcode's Simulator, in the Dock; not Chrome's helpers, which are accessories.
				simulator to listOf(ActivationPolicy.Regular),
				// Opened by its wrapper, which is listed in its place.
				wrapped to emptyList(),
			),
			listed,
		)
	}

	@Test
	fun aBundleThatSaysBothThatItIsAnAccessoryAndFacelessIsFaceless() {
		assertEquals(
			ActivationPolicy.Regular,
			ActivationPolicy.of(uiElement = false, backgroundOnly = false),
		)
		assertEquals(
			ActivationPolicy.Accessory,
			ActivationPolicy.of(uiElement = true, backgroundOnly = false),
		)
		assertEquals(
			ActivationPolicy.Prohibited,
			ActivationPolicy.of(uiElement = false, backgroundOnly = true),
		)
		assertEquals(
			ActivationPolicy.Prohibited,
			ActivationPolicy.of(uiElement = true, backgroundOnly = true),
		)
	}

	@Test
	fun aBundleThatChangesIsGivenANewerRevisionAndOneThatGoesIsLetGo() {
		val read = KeptBundles.NONE.read(setOf(safari, firefox))

		assertEquals(read.revisions[safari], read.revisions[firefox])

		// What is in Safari changed: Safari's revision is newer, Firefox's is as it was.
		val changed =
			read.after(
				listOf(event("/Applications/Safari.app/Contents/Info.plist", FsFlag.Modified)),
				Disk(setOf(safari, firefox)),
			)

		assertTrue(changed.revisions.getValue(safari) > read.revisions.getValue(safari))
		assertEquals(read.revisions[firefox], changed.revisions[firefox])

		// Firefox taken to the trash.
		val gone =
			changed.after(
				listOf(event("/Applications/Firefox.app", FsFlag.Renamed)),
				Disk(setOf(safari)),
			)

		assertEquals(setOf(safari), gone.bundles)
	}

	@Test
	fun aBundleMadeOrAFolderOfThemMovedInIsKeptAndAFolderOfThemTakenAwayLetsThemGo() {
		val zed = bundle("/Applications/Zed.app")
		val chess = bundle("/Applications/Games/Chess.app")
		val solitaire = bundle("/Applications/Games/Solitaire.app")
		val read = KeptBundles.NONE.read(setOf(safari))

		val made =
			read.after(
				listOf(event("/Applications/Zed.app", FsFlag.Created)),
				Disk(setOf(safari, zed)),
			)

		assertEquals(setOf(safari, zed), made.bundles)

		val games =
			Disk(
				setOf(safari, zed, chess, solitaire),
				mapOf("/Applications/Games" to listOf(chess, solitaire)),
			)

		val moved = made.after(listOf(event("/Applications/Games", FsFlag.Renamed)), games)

		assertEquals(setOf(safari, zed, chess, solitaire), moved.bundles)

		val taken =
			moved.after(
				listOf(event("/Applications/Games", FsFlag.Removed)),
				Disk(setOf(safari, zed)),
			)

		assertEquals(setOf(safari, zed), taken.bundles)
		// A change that is in no bundle, and makes nothing, changes nothing kept.
		assertEquals(
			taken,
			taken.after(
				listOf(event("/Users/a/notes.txt", FsFlag.Modified)),
				Disk(setOf(safari, zed)),
			),
		)
	}

	@Test
	fun nothingIsKeptUntilTheIndexIsReadAndThenEveryBundleIsReadAgain() {
		val disk = Disk(setOf(safari))

		assertEquals(
			Catalogue.Unread,
			Catalogue.Unread.after(
				listOf(event("/Applications/Safari.app", FsFlag.Created)),
				disk,
			),
		)

		val first = Catalogue.Unread.read(setOf(safari)) as Catalogue.Read
		val again = first.read(setOf(safari)) as Catalogue.Read

		assertTrue(
			again.kept.revisions.getValue(safari) > first.kept.revisions.getValue(safari),
		)
	}
}
