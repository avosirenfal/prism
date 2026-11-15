package prism.app

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.browser.AppKey
import prism.browser.Choosing
import prism.browser.RecentFolders
import prism.browser.TypeFilter
import prism.desktop.FileTypes
import prism.desktop.MimeType
import prism.desktop.freedesktop.Xdg
import prism.explorer.Answered
import prism.portal.AppId
import prism.portal.Asked
import prism.portal.Choice
import prism.portal.ChoiceOption
import prism.portal.ChooserRequest
import prism.portal.Chosen
import prism.portal.FileFilter
import prism.portal.FilterRule
import prism.portal.Parent
import prism.portal.Picked
import prism.browser.Outcome as Ended

/**
 * prism as the portal's file chooser, between the bus and the picker: what a request asks made into a picker, what
 * the picker ends with made into the answer; and installing it, as edits of the user's files.
 */
class PortalTest {
	@TempDir
	lateinit var root: Path

	/** Types as a small database has them: images by their extensions, and nothing else. */
	private val types =
		object : FileTypes {
			override fun of(path: Path) = MimeType.BINARY

			override fun describe(type: MimeType) = null

			override fun lineage(type: MimeType) = listOf(type)

			override fun patternsOf(type: MimeType): List<String> =
				when (type.name) {
					"image/png" -> listOf("*.png")
					"image/jpeg" -> listOf("*.jpg", "*.jpeg")
					"image/*" -> listOf("*.png", "*.jpg", "*.jpeg")
					else -> emptyList()
				}
		}

	private fun request(asked: Asked, parent: Parent = Parent.None) =
		ChooserRequest(AppId.of("org.example.App"), parent, asked)

	private fun opening(
		multiple: Boolean = false,
		directory: Boolean = false,
		filters: List<FileFilter> = emptyList(),
		current: FileFilter? = null,
		folder: Path? = null,
		label: String? = null,
	) = Asked.Opening("", label, emptyList(), multiple, directory, filters, current, folder)

	@Test
	fun aPickerOpensWhereItIsTold_OrWhereItsApplicationWasLastAnswered_OrAtHome() {
		val app = AppKey.of("org.example.App")!!
		val named = root.resolve("named").createDirectories()
		val own = root.resolve("own").createDirectories()
		val any = root.resolve("any").createDirectories()
		val gone = root.resolve("gone")

		val recents =
			RecentFolders()
				.used(listOf(own), app, Instant.parse("2026-11-12T09:00:00Z"))
				.used(listOf(gone), app, Instant.parse("2026-11-12T09:30:00Z"))
				.used(
					listOf(any),
					AppKey.of("org.example.Other"),
					Instant.parse("2026-11-12T10:00:00Z"),
				)

		fun start(asked: Asked, who: AppKey?, of: RecentFolders = recents) =
			startOf(asked, who, of, root)

		// Told where: there, whoever asks and whatever was used; a save over a file, in that file's folder.
		assertEquals(named, start(opening(folder = named), app))
		assertEquals(named, start(opening(folder = named), null))
		assertEquals(
			named,
			start(
				Asked.Saving(
					"",
					null,
					emptyList(),
					emptyList(),
					null,
					null,
					null,
					named.resolve("a.txt"),
				),
				app,
			),
		)
		// Not told: where the application was last answered that is still there.
		assertEquals(own, start(opening(), app))
		assertEquals(
			own,
			start(Asked.SavingMany("", null, emptyList(), null, listOf("a.txt")), app),
		)
		// An application nobody can tell: where any application last was.
		assertEquals(any, start(opening(), null))
		// One answered nowhere yet, or with nothing kept: at home, as ever.
		assertEquals(root, start(opening(), AppKey.of("org.example.New")))
		assertEquals(root, start(opening(), app, RecentFolders()))
		assertEquals(root, start(opening(), null, RecentFolders()))
		// Told a folder that is not there: as if it had not been told.
		assertEquals(own, start(opening(folder = gone), app))

		// And the picker made for the request opens there, for whoever asked.
		val asking = askingOf(request(opening()), types, root, recents, app)

		assertEquals(own, asking.folder)
		assertEquals(app, asking.asker)
		assertEquals(root, askingOf(request(opening()), types, root).folder)
	}

	@Test
	fun whatIsAskedIsWhatThePickerChooses() {
		assertEquals(Choosing.OneFile(), askingOf(request(opening()), types, root).choosing)
		assertEquals(
			Choosing.Files(),
			askingOf(request(opening(multiple = true)), types, root).choosing,
		)
		assertEquals(
			Choosing.Folder(),
			askingOf(request(opening(directory = true, multiple = true)), types, root).choosing,
		)
		assertEquals(
			Choosing.SaveAs("report.pdf"),
			askingOf(
				request(
					Asked.Saving("", null, emptyList(), emptyList(), null, "report.pdf", null, null),
				),
				types,
				root,
			).choosing,
		)
		// Saving over a file with no name given: the file's.
		assertEquals(
			Choosing.SaveAs("old.txt"),
			askingOf(
				request(
					Asked.Saving(
						"",
						null,
						emptyList(),
						emptyList(),
						null,
						null,
						null,
						root.resolve("old.txt"),
					),
				),
				types,
				root,
			).choosing,
		)
		assertEquals(
			Choosing.Folder(),
			askingOf(
				request(Asked.SavingMany("", null, emptyList(), null, listOf("a"))),
				types,
				root,
			).choosing,
		)
	}

	@Test
	fun itOpensWhereItIsAskedToWithItsTitleItsButtonAndOverItsWindow() {
		val pictures = root.resolve("Pictures").createDirectories()

		val asking =
			askingOf(
				request(opening(folder = pictures, label = "Attach"), Parent.X11(0x2a)),
				types,
				root,
			)

		assertEquals(pictures, asking.folder)
		assertEquals("Attach", asking.acceptLabel)
		assertEquals(0x2aL, asking.parent)
		// With no title, the picker's own; with a folder that is not there, the home folder.
		assertEquals("Open", asking.title)
		assertEquals(
			root,
			askingOf(request(opening(folder = root.resolve("gone"))), types, root).folder,
		)
		// Over a Wayland window, there is no X11 window to be a dialog of.
		assertNull(askingOf(request(opening(), Parent.Wayland("h")), types, root).parent)
		// Saving, the folder of the file saved over; saving several, a button that says where.
		assertEquals(
			pictures,
			askingOf(
				request(
					Asked.Saving(
						"",
						null,
						emptyList(),
						emptyList(),
						null,
						null,
						null,
						pictures.resolve("a.png"),
					),
				),
				types,
				root,
			).folder,
		)
		assertEquals(
			"Save here",
			askingOf(
				request(Asked.SavingMany("", null, emptyList(), null, listOf("a"))),
				types,
				root,
			).acceptLabel,
		)
	}

	private val images =
		FileFilter("Images", listOf(FilterRule(1u, "image/*"), FilterRule(0u, "*.webp")))
	private val text = FileFilter("Text", listOf(FilterRule(0u, "*.txt")))
	private val every = FileFilter("All files", listOf(FilterRule(0u, "*")))
	private val unknown =
		FileFilter("Mystery", listOf(FilterRule(1u, "application/x-nothing")))

	@Test
	fun filtersByTypeAreFiltersByNameAndTheOneInUseIsTheOneAskedFor() {
		val asking =
			askingOf(
				request(opening(filters = listOf(images, text, every, unknown), current = text)),
				types,
				root,
			)

		assertEquals(
			listOf(
				TypeFilter("Images", listOf("*.png", "*.jpg", "*.jpeg", "*.webp")),
				TypeFilter("Text", listOf("*.txt")),
				// Every file is the picker's own filter of every file.
				TypeFilter("All files", emptyList()),
			),
			// A filter nothing in which can be matched by name is left out, since it would show every file.
			asking.filters,
		)
		assertEquals(TypeFilter("Text", listOf("*.txt")), asking.filter)

		// One in use that is not among those offered is offered first; with none at all, every file.
		val other = FileFilter("PNG", listOf(FilterRule(1u, "image/png")))

		assertEquals(
			listOf("PNG", "Text"),
			askingOf(
				request(opening(filters = listOf(text), current = other)),
				types,
				root,
			).filters.map {
				it.label
			},
		)
		assertEquals(
			listOf(TypeFilter.ALL_FILES),
			askingOf(request(opening()), types, root).filters,
		)
	}

	@Test
	fun whatThePickerEndsWithIsTheAnswerWithThePortalsOwnFilter() {
		val asked =
			opening(filters = listOf(images, text), current = images).copy(
				choices =
					listOf(
						Choice("enc", "Encoding", listOf(ChoiceOption("utf8", "UTF-8")), "utf8"),
					),
			)

		val asking = askingOf(request(asked), types, root)

		val chosen =
			Answered(
				Ended.Chosen(listOf(root.resolve("a.txt"))),
				TypeFilter("Text", listOf("*.txt")),
			)

		assertEquals(
			Picked.Chose(listOf(root.resolve("a.txt")), text, listOf(Chosen("enc", "utf8"))),
			pickedOf(request(asked), asking, chosen, types),
		)
		assertEquals(
			Picked.Cancelled,
			pickedOf(
				request(asked),
				asking,
				Answered(Ended.Cancelled, asking.filters.first()),
				types,
			),
		)
	}

	@Test
	fun savingSeveralPutsEachInTheFolderUnderAFreeName() {
		root.resolve("a.txt").writeText("there")

		val asked =
			Asked.SavingMany(
				"",
				null,
				emptyList(),
				null,
				listOf("a.txt", "b.txt", "b.txt"),
			)

		val asking = askingOf(request(asked), types, root)

		val picked =
			pickedOf(
				request(asked),
				asking,
				Answered(Ended.Chosen(listOf(root)), TypeFilter.ALL_FILES),
				types,
			)

		assertEquals(
			listOf(
				root.resolve("a (copy).txt"),
				root.resolve("b.txt"),
				root.resolve("b (copy).txt"),
			),
			(picked as Picked.Chose).paths,
		)
		assertNull(picked.filter)
	}

	// ---- installing ---------------------------------------------------------------------------------------------

	@Test
	fun aPortalsConfPrefersPrismForTheFileChooserAndNothingElseChanges() {
		val made = withPrism(null)

		assertEquals(
			"[preferred]\n# prism portal install: $CHOOSER_KEY was (not set)\n$CHOOSER_KEY=prism\n",
			made.text,
		)
		assertNull(made.before)

		val theirs = "[preferred]\ndefault=gnome;gtk\n$CHOOSER_KEY=gtk\norg.freedesktop.impl.portal.ScreenCast=wlr\n\n[other]\nx=1\n"
		val changed = withPrism(theirs)

		assertEquals("gtk", changed.before)
		assertEquals(
			"[preferred]\n# prism portal install: $CHOOSER_KEY was gtk\n$CHOOSER_KEY=prism\ndefault=gnome;gtk\n" +
				"org.freedesktop.impl.portal.ScreenCast=wlr\n\n[other]\nx=1\n",
			changed.text,
		)
		// Done twice, it is done once.
		assertEquals(changed.text, withPrism(changed.text).text)

		// Undone, it is as it was, but for where the line is.
		assertEquals(
			"[preferred]\n$CHOOSER_KEY=gtk\ndefault=gnome;gtk\norg.freedesktop.impl.portal.ScreenCast=wlr\n\n[other]\nx=1\n",
			withoutPrism(changed.text),
		)
		// A file that held nothing but prism's line is gone; one that does not prefer prism is left as it is.
		assertNull(withoutPrism(made.text))
		assertEquals(theirs, withoutPrism(theirs))
		// A file with no [preferred] gets one.
		assertEquals(
			"[other]\nx=1\n\n[preferred]\n# prism portal install: $CHOOSER_KEY was (not set)\n$CHOOSER_KEY=prism\n",
			withPrism("[other]\nx=1\n").text,
		)
	}

	@Test
	fun aCopyOfTheFileThePortalReadsIsMadeWhereTheUserHasNoneAndTakenAwayWhole() {
		val system = root.resolve("etc/xdg/xdg-desktop-portal").createDirectories()

		val xdg =
			Xdg(
				mapOf(
					"XDG_CONFIG_HOME" to root.resolve("config").toString(),
					"XDG_CONFIG_DIRS" to root.resolve("etc/xdg").toString(),
					"XDG_DATA_DIRS" to root.resolve("share").toString(),
					"XDG_CURRENT_DESKTOP" to "niri",
				),
				root,
			)

		system.resolve("portals.conf").writeText("[preferred]\ndefault=gtk\n")
		assertEquals(system.resolve("portals.conf"), portalConfigNow(xdg))
		system
			.resolve(
				"niri-portals.conf",
			).writeText(
				"[preferred]\ndefault=gnome;gtk\norg.freedesktop.impl.portal.ScreenCast=gnome\n",
			)
		assertEquals(system.resolve("niri-portals.conf"), portalConfigNow(xdg))

		val copied =
			withPrism(
				"$MADE_FROM ${system.resolve("niri-portals.conf")}\n" +
					Files.readString(system.resolve("niri-portals.conf")),
			)

		assertTrue("default=gnome;gtk" in copied.text)
		assertTrue("org.freedesktop.impl.portal.ScreenCast=gnome" in copied.text)
		assertTrue("$CHOOSER_KEY=prism" in copied.text)
		assertNull(withoutPrism(copied.text))
	}

	@Test
	fun prismsLineGoesInTheFileThePortalReadsForThisDesktop() {
		val config = root.resolve("config/xdg-desktop-portal").createDirectories()

		val xdg =
			Xdg(
				mapOf(
					"XDG_CONFIG_HOME" to root.resolve("config").toString(),
					"XDG_CURRENT_DESKTOP" to "niri:GNOME",
				),
				root,
			)

		assertEquals(config.resolve("portals.conf"), chooserConfigOf(xdg))
		config.resolve("gnome-portals.conf").writeText("[preferred]\n")
		assertEquals(config.resolve("gnome-portals.conf"), chooserConfigOf(xdg))
		config.resolve("niri-portals.conf").writeText("[preferred]\n")
		assertEquals(config.resolve("niri-portals.conf"), chooserConfigOf(xdg))
	}

	@Test
	fun theFilesInstalledSayTheyArePrismsAndRunItQuotedAsTheyMust() {
		val xdg = Xdg(mapOf("XDG_DATA_HOME" to root.resolve("data").toString()), root)

		val files =
			installedFiles(
				xdg,
				listOf("/opt/my apps/prism/bin/prism"),
				chooser = true,
				manager = true,
			)

		assertEquals(
			setOf(
				"data/xdg-desktop-portal/portals/prism.portal",
				"data/dbus-1/services/org.freedesktop.impl.portal.desktop.prism.service",
				"data/dbus-1/services/org.freedesktop.FileManager1.service",
				"data/applications/prism-folder.desktop",
			),
			files.keys.map { root.relativize(it).toString() }.toSet(),
		)
		assertTrue(files.values.all { it.startsWith(WRITTEN_BY) })
		assertTrue(
			"Exec=\"/opt/my apps/prism/bin/prism\" portal serve" in
				files.getValue(
					root.resolve("data/dbus-1/services/org.freedesktop.FileManager1.service"),
				),
		)
		assertTrue(
			"Interfaces=org.freedesktop.impl.portal.FileChooser;" in
				files.getValue(root.resolve("data/xdg-desktop-portal/portals/prism.portal")),
		)
		assertTrue(
			"MimeType=inode/directory;" in
				files.getValue(root.resolve("data/applications/prism-folder.desktop")),
		)
		assertEquals(
			2,
			installedFiles(xdg, listOf("prism"), chooser = false, manager = true).size,
		)

		// Only prism's are prism's.
		val theirs = root.resolve("theirs.service").also { it.writeText("[D-BUS Service]\n") }
		val ours = root.resolve("ours.service").also { it.writeText(files.values.first()) }

		assertFalse(isPrisms(theirs))
		assertTrue(isPrisms(ours))
		assertFalse(isPrisms(root.resolve("nothing")))
		assertEquals("plain", quoted("plain"))
		assertEquals("\"a \\\"b\\\" \\${'$'}c\"", quoted("a \"b\" ${'$'}c"))
		assertTrue(Files.notExists(root.resolve("data")))
	}
}
