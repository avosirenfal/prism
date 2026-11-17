package prism.explorer

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.browser.Actions
import prism.browser.Commands
import prism.browser.Item
import prism.browser.On
import prism.browser.ProcessCommands
import prism.browser.Ran
import prism.browser.Subject
import prism.browser.TextEnding
import prism.browser.Thing
import prism.browser.ThingKind
import prism.browser.Where
import prism.browser.World
import prism.preferences.ActionSpec
import prism.preferences.ActionsFile
import prism.preferences.Preferences
import prism.preferences.TextSearchSpec
import prism.preferences.WhenSpec

/**
 * The actions and text searches as the preferences' pages have them: what the catalogue says of each (where it comes
 * from, whether it is on, what it wants that is not here); what changing, turning off, putting back, copying and moving
 * one writes; a form of each written again as it was; and each tried for real.
 */
class ActingTest {
	private fun acting(written: String): Acting =
		ActionsFile.parse(written).let { Acting(it.actions, it.textSearches) }

	private val everything: (String) -> Boolean = { true }

	private fun world(home: Path, installed: (String) -> Boolean = everything): World =
		object : World {
			override val home = home

			override fun lineage(thing: Thing) = emptyList<String>()

			override fun installed(program: String) = installed(program)

			override fun exists(path: Path) = Files.exists(path)
		}

	@Test
	fun everyActionAndSearchPrismComesWithIsWrittenAgainAsItWas_FromTheFormThatEditsIt() {
		ScriptKind.Action.shipped.values.forEach { spec ->
			assertEquals(spec, ActionForm.of(spec).spec(), spec.id)
		}
		ScriptKind.Search.shipped.values.forEach { spec ->
			assertEquals(spec, SearchForm.of(spec).spec(), spec.id)
		}
		// And every command of theirs is the same words as a line and as words apart.
		(ScriptKind.Action.shipped.values + ScriptKind.Search.shipped.values)
			.flatMap { spec -> listOfNotNull(spec.run) + spec.ways.mapNotNull { it.run } }
			.forEach { words ->
				assertEquals(words, CommandForm.of(words).words)
				assertEquals(words, CommandForm.of(words).line().words)
				assertEquals(words, CommandForm.of(words).apart().words)
			}
	}

	@Test
	fun theCatalogueSaysWhereEachComesFrom_AndOneChangedBackIsPrismsOwnAgain() {
		val kind = ScriptKind.Action
		val shipped = kind.shipped.getValue("compress.zip")
		val mine = ActionSpec("mine.mail", "Mail it", run = listOf("mail", "{path}"))
		val changed = shipped.copy(label = "Zip it")
		val acting = kind.put(kind.put(Acting(), mine), changed)
		val catalog = kind.catalog(acting, everything)

		assertEquals(Source.Own(mine), catalog["mine.mail"]?.source)
		assertEquals(Source.Changed(shipped, changed), catalog["compress.zip"]?.source)
		assertTrue(catalog["compress.7z"]?.source is Source.Shipped)
		assertEquals(mapOf(Mark.Own to 1, Mark.Changed to 1), catalog.marked)
		// One changed keeps its place among prism's own; the user's own come after.
		assertEquals(kind.shipped.keys.toList() + "mine.mail", catalog.entries.map { it.id })

		// Written as prism has it, it is not written at all.
		assertEquals(kind.put(Acting(), mine), kind.put(acting, shipped))
		// Put back, what was written over it is gone; the user's own, removed, is gone altogether.
		assertEquals(kind.put(Acting(), mine), kind.without(acting, "compress.zip"))
		assertNull(kind.catalog(kind.without(acting, "mine.mail"), everything)["mine.mail"])

		// The same one written twice is written once.
		assertEquals(1, kind.put(kind.put(Acting(), mine), mine.copy(label = "Send")).actions.size)
	}

	@Test
	fun oneTurnedOffIsOfferedNowhere_ButIsStillWhatAnotherIsLike_AndWhatAPlaceBringsOutBy() {
		val off = setOf("archive.zip.unpack", "archive.zip.extract", "text.search")
		val actions = Actions.read(listOf(Actions.SHIPPED), disabled = off)

		assertTrue(actions.actions.none { it.id in off })
		assertTrue(actions.searches.none { it.id in off })
		// "Extract here" is like the one turned off, and is still there; the archive's way out still works.
		assertNotNull(actions.actions.firstOrNull { it.id == "archive.zip.unpack-here" })
		assertEquals("archive.zip.extract", actions.places.getValue("archive.zip").out)
		assertNotNull(actions["archive.zip.extract"])
		assertTrue(actions.broken.isEmpty())

		// As the preferences write it, and as the catalogue says it.
		val acting = Acting().enabling("archive.zip.unpack", false)
		val settings = Settings(Preferences.DEFAULT).copy(acting = acting)

		assertEquals(setOf("archive.zip.unpack"), settings.written.disabled)
		assertFalse(ScriptKind.Action.catalog(acting, everything)["archive.zip.unpack"]!!.enabled)
		assertTrue(settings.actions.actions.none { it.id == "archive.zip.unpack" })
		assertEquals(acting.enabling("archive.zip.unpack", true), Acting())
	}

	@Test
	fun whatCannotBeRunHereSaysWhichProgramsItWants_AndWhatIsWrittenWrongSaysWhy() {
		val acting =
			acting(
				"""{"actions": [
					{"id": "a.wrong", "label": "Wrong", "run": ["x", "{nowhere}"]},
					{"id": "a.needs", "label": "Needs", "when": {"programs": ["exiftool"]}, "run": ["exiftool", "{path}"]},
					{"id": "a.which", "label": "Which", "run": ["{which:magick|convert}", "{path}"]},
					{"label": "No id"}
				]}""",
			)

		val catalog = ScriptKind.Action.catalog(acting) { it in setOf("tar", "git", "sh") }

		fun needs(id: String) = (catalog[id]?.trouble as? Trouble.Missing)?.needs

		assertEquals("zip, or 7zz or 7z or 7za or 7zr", needs("archive.zip.remove"))
		assertEquals("unzip, or 7zz or 7z or 7za or 7zr, or bsdtar", needs("archive.zip.unpack"))
		assertEquals("exiftool", needs("a.needs"))
		assertEquals("magick or convert", needs("a.which"))
		// What is like one that cannot be run cannot be either, for want of the same.
		assertEquals(needs("archive.zip.unpack"), needs("archive.zip.unpack-here"))
		// What opens a place that cannot be listed wants what its listing does.
		assertEquals("zipinfo, or 7zz or 7z or 7za or 7zr, or bsdtar", needs("archive.zip.open"))
		assertNull(catalog["archive.tar.unpack"]?.trouble)
		assertNull(catalog["git.changes"]?.trouble)
		assertTrue((catalog["a.wrong"]?.trouble as Trouble.Wrong).why.contains("{nowhere}"))
		assertEquals(listOf(3), catalog.unread.map { it.at })
		assertEquals(
			acting.actions.take(3),
			ScriptKind.Action.withoutAt(acting, 3).actions,
		)
	}

	@Test
	fun theEntriesAreGroupedByTheWindowTheyAreOfferedIn_AndNarrowedByWordsAWindowAFileAndAMark() {
		val kind = ScriptKind.Action
		val catalog = kind.catalog(Acting().enabling("decompress.gz", false), everything)

		fun shown(narrowing: Narrowing) = catalog.groups(narrowing).flatMap { it.entries }.map { it.id }

		val groups = catalog.groups(Narrowing())

		assertEquals(
			listOf(Scene.Anywhere, Scene.In("folder"), Scene.In("archive.tar"), Scene.In("archive.zip")),
			groups.map { it.scene }.take(4),
		)
		assertEquals(catalog.entries.size, groups.sumOf { it.entries.size })
		assertEquals("Place: archive.zip", catalog.scenes.titled(Scene.In("archive.zip")))
		assertEquals("Folders", catalog.scenes.titled(Scene.In("folder")))

		// By a word of its name, its id, or an extension it is for.
		assertEquals(listOf("git.changes"), shown(Narrowing("changed")))
		assertEquals(listOf("decompress.zst"), shown(Narrowing(".zstd decompress")))
		assertTrue(shown(Narrowing("bsdtar")).containsAll(listOf("archive.tar.unpack", "archive.zip.unpack")))
		// In one window.
		assertEquals(
			catalog.entries.filter { it.spec.conditions.within == listOf("archive.zip") }.map { it.id },
			shown(Narrowing(scene = Scene.In("archive.zip"))),
		)
		// For a file of a name: what names its extension, and what is for any file; not what its name rules out.
		val forGz = shown(Narrowing(named = "notes.tar.gz"))

		assertTrue("archive.tar.unpack" in forGz && "compress.zip" in forGz)
		assertFalse("decompress.gz" in forGz || "archive.zip.unpack" in forGz || "git.changes" in forGz)
		assertTrue("decompress.gz" in shown(Narrowing(named = "gz")))
		assertEquals(shown(Narrowing(named = ".zip")), shown(Narrowing(named = "zip")))
		// With a mark.
		assertEquals(listOf("decompress.gz"), shown(Narrowing(marks = setOf(Mark.Off))))
	}

	@Test
	fun aCopyHasAnIdOfItsOwn_AndOneMovedIsOfferedWhereAndForWhatItIsSaid() {
		val kind = ScriptKind.Action
		val unpack = kind.shipped.getValue("archive.zip.unpack")
		val taken = kind.catalog(Acting(), everything).ids
		val copy = copyOf(unpack, taken)

		assertEquals("archive.zip.unpack-copy", copy.id)
		assertEquals("Extract to… (copy)", copy.label)
		assertEquals("archive.zip.unpack-copy-2", copyOf(unpack, taken + copy.id).id)
		assertEquals("mail-it", idFrom("Mail it…"))
		assertEquals("mail-it-2", fresh("mail-it", setOf("mail-it")))

		val to = Retarget.of(unpack).copy(within = listOf("results"), ext = "cbz, cb7")
		val moved = to.applied(unpack)

		assertEquals(listOf("cbz", "cb7"), moved.conditions.ext)
		assertEquals(listOf("results"), moved.conditions.within)
		assertEquals(unpack.conditions.kind, moved.conditions.kind)
		assertEquals(unpack, Retarget.of(unpack).applied(unpack))
		// Anywhere, for any file: neither is said.
		assertEquals(WhenSpec(kind = "file"), Retarget().applied(unpack).conditions)
	}

	@Test
	fun aFormIsWrittenAsWhatItNowDoes_AndIsCheckedAsItIsTyped() {
		val taken = ScriptKind.Action.catalog(Acting(), everything).ids

		val form =
			ActionForm()
				.named("Mail it", taken)
				.copy(
					ways = listOf(WayForm(command = CommandForm.Line("mail -a {path} 'a b'"))),
					conditions = WhenForm(ext = "pdf, docx", count = prism.browser.Count.One),
					folder = "~/Mail",
				)

		assertEquals("mail-it", form.id)
		// Its id follows its name while it is being named, and stays once it is its own.
		assertEquals("mail", form.named("Mail", taken).id)
		assertEquals("m", form.copy(id = "m").named("Mail", taken).id)
		assertEquals(
			ActionSpec(
				"mail-it",
				"Mail it",
				WhenSpec(ext = listOf("pdf", "docx"), count = "one"),
				run = listOf("mail", "-a", "{path}", "a b"),
			),
			form.spec(),
		)
		// Turned to opening a folder, the command is kept typed, and not written.
		assertEquals(
			prism.preferences.ShowSpec(folder = "~/Mail"),
			form.copy(does = Does.Folder).spec().show,
		)
		assertNull(form.copy(does = Does.Folder).spec().run)
		// With a second way, or programs named, it is written as ways.
		assertEquals(
			listOf(listOf("mutt"), emptyList()),
			form
				.copy(ways = listOf(WayForm("mutt", CommandForm.Line("mutt -a {path}"))) + form.ways)
				.spec()
				.ways
				.map { it.programs },
		)

		fun checked(form: ActionForm, installed: (String) -> Boolean = everything) =
			ScriptKind.Action.checked(Acting(), form.spec(), form.fresh, installed)

		assertTrue(checked(form).savable)
		assertEquals(listOf("Give it a name."), checked(form.copy(label = " ")).wrong)
		assertTrue(checked(form.copy(id = "compress.zip")).wrong.single().contains("already used"))
		assertTrue(checked(form.copy(id = "compress.zip", fresh = false)).savable)
		assertTrue(
			checked(form.copy(ways = listOf(WayForm(command = CommandForm.Line("mail {paht}")))))
				.wrong
				.single()
				.contains("{paht}"),
		)
		assertEquals(
			"mutt",
			checked(form.copy(ways = listOf(WayForm("mutt", CommandForm.Line("mutt {path}"))))) {
				false
			}.missing,
		)
	}

	@Test
	fun whenSomethingIsOfferedIsSaidInAPersonsWords() {
		val scenes = ScriptKind.Action.catalog(Acting(), everything).scenes
		val gz = ScriptKind.Action.shipped.getValue("decompress.gz").conditions

		assertEquals(
			listOf(
				"In the menu of the files selected, in any window.",
				"For any files ending in .gz, but not .tar.gz.",
			),
			offeredSaid(gz, On.Files, scenes),
		)
		assertEquals("Any files · .gz · not .tar.gz", offeredBrief(gz, On.Files))
		assertEquals(
			listOf(
				"In the menu of the folder itself (a click on empty space), in the place archive.zip.",
				"For any items.",
			),
			offeredSaid(
				ScriptKind.Action.shipped.getValue("archive.zip.extract-all").conditions,
				On.Files,
				scenes,
			),
		)
		assertEquals(
			"In any menu, in folders or search results.",
			offeredSaid(WhenSpec(within = listOf("folder", "results")), null, scenes).first(),
		)
		assertEquals(
			"Runs unzip, or 7zz or 7z or 7za or 7zr, or bsdtar",
			doesBrief(ScriptKind.Action.shipped.getValue("archive.zip.unpack")) { null },
		)
		assertEquals(
			"Same as “Extract to…”",
			doesBrief(ScriptKind.Action.shipped.getValue("archive.zip.unpack-here")) {
				ScriptKind.Action.shipped[it]?.label
			},
		)
	}

	@Test
	fun anExampleShowsTheCommandForAFileItIsFor_AndATrialRunsItForReal(@TempDir home: Path) {
		val world = world(home)
		val spec =
			ActionSpec(
				"t.count",
				"Count",
				WhenSpec(ext = listOf("txt"), count = "many"),
				ask = mapOf("word" to prism.preferences.AskSpec(text = "Which word?")),
				run = listOf("sh", "-c", "grep -c \"\$word\" \"\$1\"", "sh", "{path}"),
			)

		// The example: two files of the extension it is for, and its answer as a name, since none is given yet.
		assertEquals(
			Example.Runs(
				listOf("example.txt", "another.txt"),
				listOf("example.txt", "another.txt").map {
					listOf("sh", "-c", "grep -c \"\$word\" \"\$1\"", "sh", home.resolve(it).toString())
				},
			),
			exampleOf(Acting(), spec, world),
		)
		assertEquals(
			Example.Opens("Opens the place archive.zip with archive = ${home.resolve("example.zip")}"),
			exampleOf(Acting(), ScriptKind.Action.shipped.getValue("archive.zip.open"), world),
		)
		assertEquals(Example.None, exampleOf(Acting(), spec.copy(run = listOf("{no}")), world))
		assertEquals(listOf("word"), valuesAsked(Acting(), spec, world))

		Files.writeString(home.resolve("a.txt"), "one\ntwo one\n")
		Files.writeString(home.resolve("b.md"), "three\n")

		fun tried(trial: Trial, of: ActionSpec = spec) =
			tried(Acting(), of, trial, world, ProcessCommands)

		assertEquals(Tried.Refused("Name at least one file to try it on."), tried(Trial()))
		assertEquals(
			Tried.Refused("Fill in “word” first."),
			tried(Trial("a.txt")),
		)
		assertEquals(
			Tried.Refused("${home.resolve("nowhere")} doesn't exist."),
			tried(Trial("nowhere", mapOf("word" to "one"))),
		)

		// Run for each file, each with what it printed; and said not to be for a file its conditions leave out.
		val ran = tried(Trial("a.txt\nb.md", mapOf("word" to "one"))) as Tried.Ran

		assertEquals(listOf(0 to "2\n", 1 to "0\n"), ran.runs.map { it.status to it.output })
		assertEquals(home.resolve("a.txt").toString(), ran.runs.first().words.last())
		assertTrue(ran.note!!.contains("don't meet its conditions"))

		// One that fails says so, with what it wrote; and nothing after it is run.
		val failed =
			tried(
				Trial("a.txt\nb.md"),
				spec.copy(ask = emptyMap(), run = listOf("sh", "-c", "echo no >&2; exit 3", "sh", "{path}")),
			) as Tried.Ran

		assertEquals(listOf(3 to "no\n"), failed.runs.map { it.status to it.errors })
		assertEquals(
			Tried.Opens("Opens the folder $home, with a.txt selected"),
			tried(Trial("a.txt"), ScriptKind.Action.shipped.getValue("containing-folder")),
		)
	}

	@Test
	fun aSearchTriedSaysWhatItFound_AndWhichLinesItPrintedCouldNotBeRead(@TempDir home: Path) {
		val world = world(home)

		Files.writeString(home.resolve("a.txt"), "alpha\nbeta alpha\n")

		val spec =
			TextSearchSpec(
				"t.find",
				"Find",
				run = listOf("sh", "-c", "echo looking; xargs -0 grep -Hn -- \"\$query\"", "sh"),
			)

		assertEquals(
			Example.Runs(emptyList(), listOf(spec.run!!)),
			exampleOf(Acting(), spec, world),
		)
		assertEquals(
			Tried.Refused("Name at least one file or folder to look in."),
			tried(Acting(), spec, Trial(query = "alpha"), world, ProcessCommands),
		)

		val found =
			tried(Acting(), spec, Trial("a.txt", query = "alpha"), world, ProcessCommands) as Tried.Found

		assertEquals(2, found.lines)
		assertEquals(1, found.files)
		assertEquals(listOf(1L, 2L), found.first.map { it.hit?.position })
		assertEquals(listOf("looking"), found.unread)
		assertEquals(TextEnding.Done(), found.ended)

		// Said to be read as JSON, nothing it prints is: every line of it is shown as not read.
		val asJson =
			tried(
				Acting(),
				spec.copy(read = "json"),
				Trial("a.txt", query = "alpha"),
				world,
				ProcessCommands,
			) as Tried.Found

		assertEquals(0, asJson.lines)
		assertEquals(3, asJson.unread.size)

		// One that takes lines is given the lines typed, and keeps those it says.
		val kept =
			tried(
				Acting(),
				ScriptKind.Search.shipped.getValue("text.lines"),
				Trial("one\ntwo\nthree two", query = "two"),
				world,
				ProcessCommands,
			) as Tried.Found

		assertEquals(listOf("two", "three two"), kept.first.map { it.hit?.text })

		// What a command that cannot be started says is how it ended.
		val none =
			tried(Acting(), spec, Trial("a.txt"), world, Commands { _, _ -> Ran(null, "", "no sh") })

		assertTrue((none as Tried.Found).ended is TextEnding.Failed)
	}

	@Test
	fun onlyWhatWasChangedIsWritten_OverThePreferencesAsTheyAreByThen() {
		val mine = ActionSpec("mine.mail", "Mail it", run = listOf("mail", "{path}"))
		val base = Preferences.DEFAULT
		val settings =
			Settings(base).let { it.copy(acting = ScriptKind.Action.put(it.acting, mine)) }

		assertTrue(settings.changed)
		assertEquals(
			listOf(mine),
			settings.written.written
				.actionSpecs()
				.map { (it as prism.preferences.Spec.Read).value },
		)
		// Written as the file writes it: what is as it would be unsaid is not said.
		assertEquals(
			"""{"id":"mine.mail","label":"Mail it","run":["mail","{path}"]}""",
			Json.encodeToString(
				kotlinx.serialization.json.JsonElement
					.serializer(),
				settings.written.actions.single(),
			),
		)
		// The menus page sees it before it is applied.
		assertNotNull(settings.actions["mine.mail"])
		assertEquals(
			Subject(On.Files, listOf(Thing(Item.OnDisk(Path.of("/a")), "a", ThingKind.File)), Where.Results)
				.let { settings.actions["mine.mail"]!!.fits(it) },
			true,
		)

		// What was turned off elsewhere meanwhile is kept, where it was not changed here.
		val now = base.copy(disabled = setOf("git.changes"))

		assertEquals(setOf("git.changes"), settings.over(now).disabled)
		assertEquals(1, settings.over(now).actions.size)
		assertFalse(settings.applied(settings.over(now)).changed)
	}
}
