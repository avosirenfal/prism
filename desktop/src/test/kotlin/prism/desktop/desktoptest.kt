package prism.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.DesktopEntries
import prism.desktop.freedesktop.DetachedLauncher
import prism.desktop.freedesktop.Exec
import prism.desktop.freedesktop.Freedesktop
import prism.desktop.freedesktop.SharedMimeInfo
import prism.desktop.freedesktop.Xdg

/**
 * The freedesktop.org desktop, over a desktop made in a folder for each test: a MIME database, desktop entries, and the
 * lists that say which application opens which type. A file's type by its name and, failing that, its bytes; an
 * application's command split and its files put in as its field codes say; the default application as the lists say,
 * in their order, and set as a desktop's settings set it; and what is opened started apart from prism.
 */
class DesktopTest {
	@TempDir
	lateinit var root: Path

	private val home get() = root.resolve("home")
	private val system get() = root.resolve("usr/share")

	private fun xdg(vararg more: Pair<String, String>) =
		Xdg(
			mapOf(
				"XDG_DATA_HOME" to home.resolve(".local/share").toString(),
				"XDG_DATA_DIRS" to system.toString(),
				"XDG_CONFIG_HOME" to home.resolve(".config").toString(),
				"XDG_CONFIG_DIRS" to root.resolve("etc/xdg").toString(),
				"PATH" to System.getenv("PATH"),
			) + more,
			home,
		)

	private fun write(path: Path, text: String): Path =
		path
			.also {
				it.parent.createDirectories()
			}.apply { writeText(text) }

	/** A small MIME database in the system's data folder, a type of the user's own in theirs. */
	private fun mime() {
		write(
			system.resolve("mime/globs2"),
			"""
			# generated
			50:image/png:*.png
			50:application/gzip:*.gz
			50:application/x-compressed-tar:*.tar.gz
			50:text/x-kotlin:*.kt
			50:text/x-makefile:Makefile
			50:text/x-readme:README*
			50:text/x-c:*.c:cs
			50:text/x-c++:*.C:cs
			""".trimIndent(),
		)
		write(system.resolve("mime/aliases"), "image/x-png image/png\n")
		write(
			system.resolve("mime/subclasses"),
			"text/x-kotlin text/plain\napplication/x-compressed-tar application/gzip\n",
		)
		write(
			system.resolve("mime/image/png.xml"),
			"<mime-type type=\"image/png\"><comment>PNG image</comment><comment xml:lang=\"de\">PNG-Bild</comment></mime-type>",
		)
		write(home.resolve(".local/share/mime/globs2"), "60:text/x-mine:*.mine\n")
	}

	private fun entry(folder: Path, id: String, text: String) =
		write(folder.resolve("applications/$id"), text)

	/** Applications: an image viewer, two editors, a terminal program, and a service no one is shown. */
	private fun applications() {
		entry(
			system,
			"viewer.desktop",
			"[Desktop Entry]\nType=Application\nName=Viewer\nExec=viewer %F\nMimeType=image/png;\n",
		)
		entry(
			system,
			"editor.desktop",
			"[Desktop Entry]\nType=Application\nName=Editor\nExec=editor --new \"%f\"\nMimeType=text/plain;\n",
		)
		entry(
			system,
			"zed.desktop",
			"[Desktop Entry]\nType=Application\nName=Zed\nExec=zed %U\nMimeType=text/plain;text/x-kotlin;\n",
		)
		entry(
			system,
			"top.desktop",
			"[Desktop Entry]\nType=Application\nName=Top\nExec=top\nTerminal=true\n",
		)
		entry(
			system,
			"service.desktop",
			"[Desktop Entry]\nType=Application\nName=Service\nExec=service %f\nNoDisplay=true\nMimeType=image/png;\n",
		)
	}

	@Test
	fun aFileIsTypedByItsNameThenByItsBytes() {
		mime()

		val types = SharedMimeInfo(xdg())
		val folder = root.resolve("files").createDirectories()

		fun typed(name: String, bytes: ByteArray = byteArrayOf(1)) =
			types.of(folder.resolve(name).also { it.writeBytes(bytes) }).name

		assertEquals("image/png", typed("photo.PNG"))
		// The longest extension first.
		assertEquals("application/x-compressed-tar", typed("backup.tar.gz"))
		assertEquals("application/gzip", typed("notes.gz"))
		// A literal name, and a pattern.
		assertEquals("text/x-makefile", typed("Makefile"))
		assertEquals("text/x-readme", typed("README.first"))
		// Case counts where the database says it does.
		assertEquals("text/x-c", typed("main.c"))
		assertEquals("text/x-c++", typed("main.C"))
		// The user's own database is read as well.
		assertEquals("text/x-mine", typed("a.mine"))
		// A name that says nothing: what it holds.
		assertEquals(
			"text/plain",
			typed("LICENSE", "Permission is hereby granted".toByteArray()),
		)
		assertEquals("application/octet-stream", typed("blob", byteArrayOf(0, 1, 2)))
		assertEquals("application/x-zerosize", typed("empty", ByteArray(0)))
		assertEquals("inode/directory", types.of(folder).name)

		assertEquals(
			listOf("text/x-kotlin", "text/plain", "application/octet-stream"),
			types.lineage(MimeType("text/x-kotlin")).map { it.name },
		)
		assertEquals("image/png", types.canonical(MimeType("image/x-png")).name)
		assertEquals("PNG image", types.describe(MimeType("image/png")))
		assertNull(types.describe(MimeType("text/x-mine")))
	}

	@Test
	fun theSystemsOwnDatabaseKnowsAPicture() {
		assumeTrue(
			Path.of("/usr/share/mime/globs2").exists(),
			"shared-mime-info is not installed",
		)

		val types = SharedMimeInfo(Xdg())

		assertEquals("image/png", types.byName("a.png")?.name)
		assertEquals("PNG image", types.describe(MimeType("image/png")))
	}

	@Test
	fun anExecLineIsSplitAsTheSpecificationQuotesItAndItsFilesPutIn() {
		assertEquals(
			listOf("editor", "--title", "My \"great\" \$file", "%f"),
			Exec.split("editor --title \"My \\\"great\\\" \\\$file\" %f"),
		)
		assertNull(Exec.split("broken \"quote"))

		val app =
			Application(
				"viewer.desktop",
				"Viewer",
				emptySet(),
				listOf("viewer", "%i", "--name=%c", "%F"),
				false,
				"eye",
			)

		val files = listOf(Path.of("/a b.png"), Path.of("/c.png"))

		assertEquals(
			listOf(listOf("viewer", "--icon", "eye", "--name=Viewer", "/a b.png", "/c.png")),
			Exec.expand(app.command, files, app),
		)
		// One file at a time: one command each.
		assertEquals(
			listOf(listOf("editor", "/a b.png"), listOf("editor", "/c.png")),
			Exec.expand(listOf("editor", "%f"), files, app),
		)
		assertEquals(
			listOf(listOf("open", "file:///a%20b.png")),
			Exec.expand(listOf("open", "%U"), files.take(1), app),
		)
		// Nothing given, a single-file code says nothing.
		assertEquals(
			listOf(listOf("editor")),
			Exec.expand(listOf("editor", "%f"), emptyList(), app),
		)
		assertEquals(
			listOf(listOf("echo", "100%")),
			Exec.expand(listOf("echo", "100%%"), emptyList(), app),
		)
	}

	@Test
	fun theDefaultIsTheFirstTheListsNameThenTheFirstThatOpensTheTypeOrAKindOfIt() {
		mime()
		applications()

		val xdg = xdg("XDG_CURRENT_DESKTOP" to "niri:GNOME")
		val apps = DesktopEntries(xdg, SharedMimeInfo(xdg))

		// No list says: those that open it, by name; a service no one is shown is never offered, but can open it.
		assertEquals(
			listOf("Viewer"),
			apps
				.all()
				.filter {
					MimeType("image/png") in it.types
				}.map { it.name },
		)
		assertEquals("Editor", apps.defaultFor(MimeType("text/plain"))?.name)
		// A kind of text: those that open it, then those that open text.
		assertEquals(
			listOf("Zed", "Editor"),
			apps.forType(MimeType("text/x-kotlin")).map {
				it.name
			},
		)

		// The system's list, then the user's, then the desktop's own: each before the last.
		write(
			system.resolve("applications/mimeapps.list"),
			"[Default Applications]\ntext/plain=zed.desktop\n",
		)
		assertEquals("Zed", apps.defaultFor(MimeType("text/plain"))?.name)
		write(
			home.resolve(".config/mimeapps.list"),
			"[Default Applications]\ntext/plain=editor.desktop\n",
		)
		assertEquals("Editor", apps.defaultFor(MimeType("text/plain"))?.name)
		write(
			home.resolve(".config/niri-mimeapps.list"),
			"[Default Applications]\ntext/plain=gone.desktop;zed.desktop\n",
		)
		// One not installed is passed over.
		assertEquals("Zed", apps.defaultFor(MimeType("text/plain"))?.name)

		// A type with no default of its own takes its parent's.
		assertEquals("Zed", apps.defaultFor(MimeType("text/x-kotlin"))?.name)
	}

	@Test
	fun aDefaultSetIsTheUsersOwnAndLeavesTheRestOfTheirListAsItWas() {
		mime()
		applications()

		val xdg = xdg()
		val apps = DesktopEntries(xdg, SharedMimeInfo(xdg))

		val list =
			write(
				home.resolve(".config/mimeapps.list"),
				"# mine\n[Default Applications]\nimage/png=viewer.desktop\n",
			)

		val zed = apps.all().single { it.name == "Zed" }

		apps.setDefault(MimeType("text/plain"), zed)

		assertEquals(zed, apps.defaultFor(MimeType("text/plain")))
		assertEquals("Viewer", apps.defaultFor(MimeType("image/png"))?.name)
		assertTrue(list.readText().startsWith("# mine\n"))
		assertTrue(
			"[Added Associations]\ntext/plain=zed.desktop;" in list.readText(),
			list.readText(),
		)
	}

	@Test
	fun whatIsOpenedIsStartedApartFromPrismAndWhatNothingOpensIsSaid() {
		mime()
		applications()

		val started = ArrayList<List<String>>()
		// The user's systemd, as its socket says it is running.
		val runtime =
			root.resolve("run").also {
				it
					.resolve("systemd")
					.createDirectories()
					.resolve("private")
					.writeText("")
			}

		val xdg =
			xdg(
				"XDG_RUNTIME_DIR" to runtime.toString(),
				"PATH" to fakePath("viewer", "editor", "setsid", "systemd-run", "xterm"),
			)

		val launcher =
			DetachedLauncher(xdg) { command, _ ->
				started += command
				Launched.Started(command.last())
			}

		val desktop =
			object : Desktop {
				override val types = SharedMimeInfo(xdg)
				override val applications = DesktopEntries(xdg, types)
				override val launcher = launcher
				override val places = prism.desktop.freedesktop.XdgPlaces()
				override val facts = JvmFacts(types)
			}
		val files = root.resolve("files").createDirectories()
		val pictures =
			listOf("a.png", "b.png").map {
				files.resolve(it).also { f ->
					f.writeText("x")
				}
			}
		val blob = files.resolve("blob").also { it.writeBytes(byteArrayOf(0, 1)) }

		val opening = desktop.open(pictures.plusElement(blob))

		assertEquals(listOf(blob), opening.unopened)
		assertEquals(1, started.size)

		// In a session of its own, and, with the user's systemd there, a scope of its own.
		val command = started.single()

		assertTrue(command[0].endsWith("setsid") && command[1] == "--fork", "$command")
		assertTrue(
			command.containsAll(listOf("systemd-run", "--user", "--scope", "--slice=app.slice")),
			"$command",
		)
		assertEquals(
			listOf("viewer") + pictures.map { it.toString() },
			command
				.dropWhile {
					it !=
						"--"
				}.drop(1),
		)

		// Without it, a session of its own alone.
		Files.delete(
			runtime.resolve("systemd").also {
				it.resolve("private").takeIf(Files::exists)?.let(Files::delete)
			},
		)
		started.clear()
		DetachedLauncher(xdg("PATH" to fakePath("editor", "setsid"))) { c, _ ->
			started += c
			Launched.Started("")
		}.run(listOf("editor", "x"), files)
		assertEquals(listOf("--fork", "editor", "x"), started.single().drop(1))

		// A program not there is said not to be, and nothing is started.
		assertInstanceOf(
			Launched.Failed::class.java,
			DetachedLauncher(
				xdg("PATH" to fakePath()),
			) { _, _ -> error("started") }.run(listOf("nothing-here"), files),
		)

		// A terminal of the user's, in the folder; an application that runs in one, in it.
		started.clear()

		val inTerminal =
			DetachedLauncher(xdg("PATH" to fakePath("setsid", "xterm", "top"))) { c, _ ->
				started += c
				Launched.Started("")
			}

		inTerminal.terminal(files, listOf("xterm", "-T", "{folder}"))
		inTerminal.launch(
			DesktopEntries(xdg, SharedMimeInfo(xdg)).all().single {
				it.name ==
					"Top"
			},
			emptyList(),
		)
		assertEquals(listOf("xterm", "-T", files.toString()), started[0].drop(2))
		assertEquals(listOf("xterm", "-e", "top"), started[1].drop(2))
	}

	@Test
	fun aProcessStartedIsNotPrismsChildNorInItsSession() {
		assumeTrue(Path.of("/usr/bin/setsid").exists() && Path.of("/proc/self/stat").exists())

		val out = root.resolve("stat")
		val launched =
			DetachedLauncher(Xdg(System.getenv() - "XDG_RUNTIME_DIR"))
				.run(listOf("sh", "-c", "cat /proc/\$\$/stat > '$out'"), root)

		assertInstanceOf(Launched.Started::class.java, launched)

		val deadline = System.currentTimeMillis() + 10_000

		while (!(out.exists() && out.readText().isNotBlank()) &&
			System.currentTimeMillis() < deadline
			)
			Thread.sleep(20)

		// pid (comm) state ppid pgrp session ...
		val fields = out.readText().substringAfterLast(") ").split(' ')
		val pid = out.readText().substringBefore(' ').toLong()
		val parent = fields[1].toLong()
		val session = fields[3].toLong()
		val ours = ProcessHandle.current()

		assertNotEquals(ours.pid(), parent)
		assertEquals(pid, session, "the process leads a session of its own")
		assertNotEquals(
			Files
				.readString(
					Path.of("/proc/self/stat"),
				).substringAfterLast(") ")
				.split(' ')[3]
				.toLong(),
			session,
		)
	}

	@Test
	fun aFilesFactsAndItsPermissionsChanged() {
		val folder = root.resolve("facts/inner").createDirectories()
		val file = root.resolve("facts/a.txt").also { it.writeText("hello") }

		root.resolve("facts/inner/b.txt").writeText("world!")
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"))

		val facts = Freedesktop(xdg()).facts
		val read = facts.of(file)!!

		assertEquals(FileKind.File, read.kind)
		assertEquals(5L, read.size)
		assertEquals(
			Permissions.Access(read = true, write = true, run = false),
			read.permissions?.owner,
		)
		assertEquals(
			Permissions.Access(read = false, write = false, run = false),
			read.permissions?.others,
		)

		val runnable =
			read.permissions!!.copy(
				owner = read.permissions!!.owner.copy(run = true),
			)

		assertNull(facts.permit(file, runnable))
		assertEquals(
			"rwxr-----",
			PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
		)
		assertEquals(
			Contents(files = 2, folders = 1, bytes = 11),
			facts.contents(root.resolve("facts")),
		)
		assertEquals(FileKind.Folder, facts.of(folder)?.kind)
		assertNull(facts.of(root.resolve("nothing")))
	}

	/** A folder of programs named [names] that do nothing, as a `PATH` of them alone. */
	private fun fakePath(vararg names: String): String {
		val bin = Files.createTempDirectory(root, "bin")

		for (name in names)
			bin
				.resolve(name)
				.also { it.writeText("#!/bin/sh\n") }
				.toFile()
				.setExecutable(true)

		return bin.toString()
	}
}
