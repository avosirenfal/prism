package prism.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.DesktopEntries
import prism.desktop.freedesktop.DetachedLauncher
import prism.desktop.freedesktop.SharedMimeInfo
import prism.desktop.freedesktop.Xdg

/**
 * The applications a launcher lists, as desktop entries say them, over entries made in a folder for each test: whose
 * entry counts where the user and the system both have one; which are shown, on which desktop; what an entry says in
 * the user's language; the words it is found by and the other things it does; and that those are started as it says.
 */
class ApplicationsTest {
	@TempDir
	lateinit var root: Path

	private val home get() = root.resolve("home")
	private val user get() = home.resolve(".local/share")
	private val system get() = root.resolve("usr/share")
	private val local get() = root.resolve("usr/local/share")

	private fun xdg(vararg more: Pair<String, String>) =
		Xdg(
			mapOf(
				"XDG_DATA_HOME" to user.toString(),
				"XDG_DATA_DIRS" to "$local:$system",
				"XDG_CONFIG_HOME" to home.resolve(".config").toString(),
				"PATH" to "",
			) + more,
			home,
		)

	private fun entry(folder: Path, id: String, vararg lines: String) =
		folder
			.resolve("applications/$id")
			.also { it.parent.createDirectories() }
			.writeText((listOf("[Desktop Entry]") + lines).joinToString("\n", postfix = "\n"))

	private fun application(folder: Path, id: String, name: String, vararg more: String) =
		entry(folder, id, "Type=Application", "Name=$name", "Exec=$id-program", *more)

	private fun entries(xdg: Xdg = xdg()) = DesktopEntries(xdg, SharedMimeInfo(xdg))

	private fun shown(xdg: Xdg = xdg()) = entries(xdg).all().map { it.name }

	/** A folder of programs named [names] that do nothing. */
	private fun programs(vararg names: String): Path =
		Files.createTempDirectory(root, "bin").also { bin ->
			for (name in names)
				bin
					.resolve(name)
					.also { it.writeText("#!/bin/sh\n") }
					.toFile()
					.setExecutable(true)
		}

	@Test
	fun theFirstFoldersEntryIsTheOneWhateverItSaysAndOneThatSaysItIsGoneLeavesNone() {
		application(system, "editor.desktop", "System's editor", "MimeType=text/plain;")
		application(local, "editor.desktop", "Local editor", "MimeType=text/plain;")
		application(user, "editor.desktop", "My editor", "MimeType=text/plain;")
		application(system, "viewer.desktop", "Viewer", "MimeType=image/png;")
		application(user, "viewer.desktop", "Viewer", "Hidden=true")
		application(system, "link.desktop", "Was an application")
		entry(user, "link.desktop", "Type=Link", "Name=A link now", "URL=https://example.org")
		application(system, "broken.desktop", "Runs")
		entry(
			user,
			"broken.desktop",
			"Type=Application",
			"Name=Cannot run",
			"Exec=broken \"quote",
		)
		application(local, "player.desktop", "Local player")
		application(system, "player.desktop", "System's player")
		// An id is a path under the folder, its `/` made `-`.
		application(system, "kde/dolphin.desktop", "Dolphin")
		application(user, "kde-dolphin.desktop", "My Dolphin")

		val entries = entries()

		assertEquals(listOf("Local player", "My Dolphin", "My editor"), shown())
		// Gone, it opens nothing either: the system's entry is not looked at.
		assertNull(entries.defaultFor(MimeType("image/png")))
		assertEquals(
			listOf("My editor"),
			entries.forType(MimeType.TEXT).map { it.name },
		)
		assertEquals(
			user.resolve("applications/editor.desktop"),
			entries.all().single { it.id == "editor.desktop" }.source,
		)
	}

	@Test
	fun whatAsksNotToBeShownIsLeftOutOfWhatIsListedAndStillOpensFiles() {
		application(system, "shown.desktop", "Shown")
		application(
			system,
			"service.desktop",
			"Service",
			"NoDisplay=true",
			"MimeType=image/png;",
		)

		val entries = entries()

		assertEquals(listOf("Shown"), entries.all().map { it.name })
		assertEquals("Service", entries.defaultFor(MimeType("image/png"))?.name)
	}

	@Test
	fun anEntryForOtherDesktopsIsNotShownOnThisOne() {
		application(system, "everywhere.desktop", "Everywhere")
		application(system, "gnome.desktop", "GNOME's", "OnlyShowIn=GNOME;Unity;")
		application(system, "not-kde.desktop", "Not KDE's", "NotShowIn=KDE;")
		application(system, "niri.desktop", "niri's", "OnlyShowIn=Niri;", "MimeType=image/png;")

		fun on(desktop: String?) =
			shown(xdg(*listOfNotNull(desktop?.let { "XDG_CURRENT_DESKTOP" to it }).toTypedArray()))

		assertEquals(listOf("Everywhere", "GNOME's", "Not KDE's"), on("ubuntu:GNOME"))
		assertEquals(listOf("Everywhere"), on("KDE"))
		// However the desktop writes its name.
		assertEquals(listOf("Everywhere", "niri's", "Not KDE's"), on("niri"))
		// A desktop that does not say what it is is none of those an entry names.
		assertEquals(listOf("Everywhere", "Not KDE's"), on(null))
		// Not shown, it is installed all the same.
		assertEquals(
			"niri's",
			entries(xdg("XDG_CURRENT_DESKTOP" to "KDE")).defaultFor(MimeType("image/png"))?.name,
		)
	}

	@Test
	fun anEntryWhoseProgramIsNotThereIsNotInstalled() {
		val bin = programs("there")

		application(system, "on-path.desktop", "On the PATH", "TryExec=there")
		application(system, "off-path.desktop", "Not on the PATH", "TryExec=missing")
		application(system, "by-path.desktop", "By its path", "TryExec=${bin.resolve("there")}")
		application(system, "gone.desktop", "Gone", "TryExec=${bin.resolve("missing")}")
		application(system, "unasked.desktop", "Says nothing of it")

		assertEquals(
			listOf("By its path", "On the PATH", "Says nothing of it"),
			shown(xdg("PATH" to "/nowhere:$bin")),
		)
	}

	@Test
	fun whatAnEntrySaysIsReadInTheUsersLanguageTheMostParticularItHas() {
		entry(
			system,
			"app.desktop",
			"Type=Application",
			"Exec=app",
			"Name=Plain",
			"Name[sr]=sr",
			"Name[sr@Latn]=sr@Latn",
			"Name[sr_YU]=sr_YU",
			"Name[sr_YU@Latn]=sr_YU@Latn",
			"Name[de]=Deutsch",
			"GenericName=Thing",
			"GenericName[de]=Ding",
			"Comment=Does things",
			"Comment[de]=",
			"Keywords=one;two;",
			"Keywords[de]=eins;zwei;",
			"StartupWMClass=Plainly",
		)

		fun read(vararg environment: Pair<String, String>) =
			entries(xdg(*environment)).all().single()

		fun named(language: String) = read("LANG" to language).name

		assertEquals("sr_YU@Latn", named("sr_YU.UTF-8@Latn"))
		assertEquals("sr_YU", named("sr_YU.UTF-8"))
		assertEquals("sr_YU", named("sr_YU@Cyrl"))
		assertEquals("sr@Latn", named("sr_RS@Latn"))
		assertEquals("sr", named("sr_RS"))
		assertEquals("sr", named("sr"))
		assertEquals("Plain", named("fr_FR.UTF-8"))
		assertEquals("Plain", named("C"))
		assertEquals("Plain", named("POSIX"))
		assertEquals("Plain", read().name)
		// What messages are in: LC_ALL before LC_MESSAGES before LANG.
		assertEquals("Deutsch", read("LANG" to "sr", "LC_MESSAGES" to "de_DE.UTF-8").name)
		assertEquals(
			"sr",
			read("LANG" to "fr", "LC_MESSAGES" to "de_DE", "LC_ALL" to "sr_RS").name,
		)

		val german = read("LANG" to "de_AT.UTF-8")

		assertEquals("Ding", german.generic)
		// Said to be nothing in German, it is what it is said to be otherwise.
		assertEquals("Does things", german.comment)
		assertEquals(listOf("eins", "zwei"), german.keywords)
		assertEquals(listOf("one", "two"), read().keywords)
		assertEquals("Thing", read().generic)
		// What its windows call their application, where the entry says: what a window is known as its by.
		assertEquals("Plainly", read().wmClass)
	}

	@Test
	fun anEntrySaysWhatElseItsApplicationDoesAndEachIsStartedAsItSays() {
		entry(
			system,
			"browser.desktop",
			"Type=Application",
			"Name=Browser",
			"Name[de]=Stöberer",
			"Exec=browser %U",
			"Icon=globe",
			"Actions=new-window;private;nameless;nothing-to-run;absent;",
			"",
			"[Desktop Action new-window]",
			"Name=New Window",
			"Name[de]=Neues Fenster",
			"Exec=browser --new-window %u",
			"",
			"[Desktop Action private]",
			"Name=Private Window",
			"Exec=browser --private --name=%c %i %k",
			"Icon=mask",
			"",
			"[Desktop Action nameless]",
			"Exec=browser --nameless",
			"",
			"[Desktop Action nothing-to-run]",
			"Name=Nothing to run",
			"",
			"[Desktop Action unlisted]",
			"Name=Not among its actions",
			"Exec=browser --unlisted",
		)
		entry(system, "plain.desktop", "Type=Application", "Name=Plain", "Exec=plain")

		val xdg =
			xdg("PATH" to programs("browser", "plain").toString(), "LANG" to "de_DE.UTF-8")
		val browser = entries(xdg).all().single { it.id == "browser.desktop" }
		val file = system.resolve("applications/browser.desktop")

		assertEquals(
			listOf(
				ApplicationAction(
					"new-window",
					"Neues Fenster",
					listOf("browser", "--new-window", "%u"),
				),
				ApplicationAction(
					"private",
					"Private Window",
					listOf("browser", "--private", "--name=%c", "%i", "%k"),
					"mask",
				),
			),
			browser.actions,
		)
		assertEquals(
			emptyList<ApplicationAction>(),
			entries(xdg)
				.all()
				.first {
					it.name ==
						"Plain"
				}.actions,
		)

		val started = ArrayList<List<String>>()

		val launcher =
			DetachedLauncher(xdg) { command, _, _ ->
				started += command
				Launched.Started(command.last())
			}

		// Started with no files: what it would put them in is left out.
		assertInstanceOf(Launched.Started::class.java, launcher.launch(browser, emptyList()))
		assertEquals(listOf("browser"), started.last())

		assertInstanceOf(
			Launched.Started::class.java,
			launcher.launch(browser.doing(browser.actions[0]), emptyList()),
		)
		assertEquals(listOf("browser", "--new-window"), started.last())

		// An action's own icon, the application's name and its entry, where its command asks for them.
		launcher.launch(browser.doing(browser.actions[1]), emptyList())
		assertEquals(
			listOf(
				"browser",
				"--private",
				"--name=Stöberer: Private Window",
				"--icon",
				"mask",
				file.toString(),
			),
			started.last(),
		)
		assertEquals("globe", browser.doing(browser.actions[0]).icon)
	}

	@Test
	fun whatIsInstalledSinceIsSeenOnceReadAgain() {
		application(system, "one.desktop", "One")

		val entries: Applications = entries()

		application(system, "two.desktop", "Two")
		assertEquals(listOf("One"), entries.all().map { it.name })
		entries.reread()
		assertEquals(listOf("One", "Two"), entries.all().map { it.name })
	}
}
