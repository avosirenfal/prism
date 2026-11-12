package prism.browser

import java.nio.file.Path
import kotlin.io.path.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.Fixtures.home
import prism.preferences.ActionsFile
import prism.preferences.Look
import prism.preferences.MenuSpec
import prism.preferences.Preferences
import prism.preferences.Sort

/**
 * The user's own actions, as values (docs/menus.md): what is written read into what it means, or said to be wrong;
 * commands filled word by word; when each is offered; and what choosing one comes to, step by step, in the reducer.
 */
class ActionsTest {
	/** Actions and places as a preferences file would write them. */
	private fun file(actions: String = "[]", places: String = "[]"): ActionsFile =
		ActionsFile.parse("""{"actions": $actions, "places": $places}""")

	private fun read(actions: String = "[]", places: String = "[]"): Actions =
		Actions.read(listOf(file(actions, places)))

	private fun action(text: String): UserAction =
		read("[$text]")
			.also {
				assertEquals(emptyList<Broken>(), it.broken)
			}.actions
			.single()

	/** Why the one action written as [text] does not read. */
	private fun wrong(text: String, places: String = "[]"): String =
		read("[$text]", places)
			.also {
				assertEquals(emptyList<UserAction>(), it.actions)
			}.broken
			.single()
			.why

	/** A world where the programs named are installed, the paths named are there, and a type is a name's extension. */
	private class Known(
		val programs: Set<String> = emptySet(),
		val there: Set<Path> = emptySet(),
	) : World {
		override val home: Path = Fixtures.home

		override fun lineage(thing: Thing): List<String> =
			when {
				thing.kind == ThingKind.Folder -> listOf("inode/directory")
				thing.name.endsWith(".png") -> listOf("image/png")
				thing.name.endsWith(".kt") -> listOf("text/x-kotlin", "text/plain")
				else -> listOf("application/octet-stream")
			}

		override fun installed(program: String): Boolean = program in programs

		override fun exists(path: Path): Boolean = path in there
	}

	private fun files(vararg names: String, folder: Path = home): Subject =
		Subject(On.Files, names.map { Thing.file(folder.resolve(it)) }, Where.Folder(folder))

	// ---- commands ---------------------------------------------------------------------------------------------------

	@Test
	fun aCommandIsWordsAndAFileIsOneWordWhateverIsInItsName() {
		val command =
			(
				Command.read(
					listOf("magick", "{path}", "-resize", "50%", "{stem}-half.{ext}"),
					emptySet(),
				) as Reading.Right
			).value

		val subject = files("a b.png", "-rf \"\$(x)\".png")

		assertTrue(command.forEach)
		assertEquals(setOf(Need.Real), command.needs)
		assertEquals(
			listOf(
				listOf("magick", "/home/avery/a b.png", "-resize", "50%", "a b-half.png"),
				listOf(
					"magick",
					"/home/avery/-rf \"\$(x)\".png",
					"-resize",
					"50%",
					"-rf \"\$(x)\"-half.png",
				),
			),
			command.fill(subject.filling()),
		)
	}

	@Test
	fun everyFileAtOnceIsAWordEachAndTheCommandRunsOnce() {
		val command =
			(
				Command.read(
					listOf("tar", "-czf", "{to}.tar.gz", "--", "{names}"),
					setOf("to"),
				) as Reading.Right
			).value

		assertFalse(command.forEach)
		assertEquals(
			listOf(listOf("tar", "-czf", "old.tar.gz", "--", "a b.txt", "c.txt")),
			command.fill(files("a b.txt", "c.txt").filling(mapOf("to" to "old"))),
		)
	}

	@Test
	fun aShellsOwnBracesAndAnEscapedOneAreText() {
		val script = "for f; do echo \"\${f%.*}\"; done; awk '{ print \$1 }'; echo \\{path}"

		val command =
			(
				Command.read(
					listOf("sh", "-c", script, "sh", "{paths}"),
					emptySet(),
				) as Reading.Right
			).value

		assertEquals(
			listOf(
				listOf(
					"sh",
					"-c",
					"for f; do echo \"\${f%.*}\"; done; awk '{ print \$1 }'; echo {path}",
					"sh",
					"/home/avery/a.txt",
				),
			),
			command.fill(files("a.txt").filling()),
		)
	}

	@Test
	fun aValuesPartsAreItsFolderItsNameItsStemAndItsExtension() {
		val phrase =
			(
				Phrase.read(
					"{archive.parent}|{archive.name}|{archive.stem}|{archive.ext}",
					setOf("archive"),
				) as Reading.Right
			).value

		assertEquals(
			"/home/avery|backup.tar.gz|backup|tar.gz",
			phrase.fill(
				Filling(emptyList(), null, mapOf("archive" to "/home/avery/backup.tar.gz")),
			),
		)
	}

	@Test
	fun whatACommandCannotSayIsRefusedWithWhy() {
		fun why(vararg words: String, named: Set<String> = emptySet()) =
			(Command.read(words.toList(), named) as Reading.Wrong).why

		assertTrue("one or the other" in why("cp", "{path}", "{paths}"))
		assertTrue("nothing fills “{pth}”" in why("cp", "{pth}"))
		assertTrue("must be a word by itself" in why("cp", "x{paths}"))
		assertTrue("cannot be for both" in why("cp", "{paths}", "{entries}"))
		assertTrue("needs a program" in why())
		assertTrue("{dest}" in why("cp", "{dst}", named = setOf("dest")))
	}

	// ---- reading ----------------------------------------------------------------------------------------------------

	@Test
	fun anActionRunsOrShowsAndNeverBothOrNeither() {
		assertTrue("neither" in wrong("""{"id": "a", "label": "A"}"""))
		assertTrue(
			"both" in
				wrong("""{"id": "a", "label": "A", "run": ["x"], "show": {"folder": "{parent}"}}"""),
		)
		assertTrue(
			"watches" in
				wrong("""{"id": "a", "label": "A", "run": ["x"], "then": {"reread": true}}"""),
		)
		assertTrue(
			"not a place" in wrong("""{"id": "a", "label": "A", "show": {"place": "nowhere"}}"""),
		)
		// A condition written wrong is said, and not taken for no condition at all.
		assertTrue(
			"typs" in
				wrong("""{"id": "a", "label": "A", "run": ["x"], "when": {"typs": ["image/*"]}}"""),
		)
		assertTrue(
			"a count" in
				wrong("""{"id": "a", "label": "A", "run": ["x"], "when": {"count": "two"}}"""),
		)
	}

	@Test
	fun oneActionWrittenWrongDoesNotLoseTheRest() {
		val read =
			read(
				"""[
					{"id": "good", "label": "Good", "run": ["true"]},
					{"id": "bad", "label": "Bad", "run": ["cp", "{nowhere}"]},
					{"label": "No id", "run": ["true"]}
				]""",
			)

		assertEquals(listOf("good"), read.actions.map { it.id })
		assertEquals(setOf("bad", null), read.broken.map { it.id }.toSet())
	}

	@Test
	fun aLaterFilesActionReplacesAnEarlierOneOfItsId() {
		val read =
			Actions.read(
				listOf(
					file(
						"""[{"id": "a", "label": "Theirs", "run": ["true"]}, {"id": "b", "label": "B", "run": ["true"]}]""",
					),
					file("""[{"id": "a", "label": "Mine", "run": ["false"]}]"""),
				),
			)

		assertEquals(listOf("Mine", "B"), read.actions.map { it.label })
	}

	@Test
	fun aPlacesValuesAreNamedOnlyByActionsThatAreOnlyInIt() {
		val places =
			"""[{"id": "archive", "takes": ["archive"], "list": ["tar", "-tf", "{archive}"], "read": "names"}]"""

		assertTrue(
			"nothing fills “{archive}”" in
				wrong("""{"id": "x", "label": "X", "run": ["tar", "-xf", "{archive}"]}""", places),
		)
		assertEquals(
			emptyList<Broken>(),
			read(
				"""[{"id": "x", "label": "X", "when": {"in": ["archive"]}, "run": ["tar", "-xf", "{archive}"]}]""",
				places,
			).broken,
		)
	}

	@Test
	fun aPlacesWayOutMustAskForTheOneFolderADropGives() {
		fun broken(out: String) =
			read(
				"""[{"id": "out", "label": "Out", "when": {"in": ["archive"]}, $out}]""",
				"""[{"id": "archive", "takes": ["archive"], "list": ["true"], "out": "out"}]""",
			).broken.map { it.why }

		assertTrue(
			"exactly one folder" in
				broken(""""run": ["tar", "-xf", "{archive}"], "how": {"watched": true}""").single(),
		)
		assertTrue(
			"watched" in
				broken(
					""""ask": {"dest": {"folder": "To"}}, "run": ["tar", "-xf", "{archive}", "-C", "{dest}"]""",
				).single(),
		)
		assertEquals(
			emptyList<String>(),
			broken(
				""""ask": {"dest": {"folder": "To"}}, "run": ["tar", "-xf", "{archive}", "-C", "{dest}"],
				"how": {"watched": true}""",
			),
		)
	}

	@Test
	fun whatShipsWithPrismReadsAndSoDoThePreferencesOwn() {
		val shipped = Actions.read(listOf(Actions.SHIPPED))

		assertEquals(emptyList<Broken>(), shipped.broken)
		assertTrue(shipped.places.keys.containsAll(listOf("archive.tar", "archive.zip")))

		val own: JsonElement =
			Json.parseToJsonElement(
				"""{"id": "mine", "label": "Mine", "run": ["true"]}""",
			)

		val all =
			Actions.of(
				Preferences(
					actions = listOf(own),
					menu = MenuSpec(hidden = listOf("builtin.copy-path")),
				),
			)

		assertEquals("Mine", all["mine"]?.label)
		assertEquals(setOf("builtin.copy-path"), all.hidden)
		assertTrue(all.actions.size > 1)
	}

	// ---- when an action is offered ----------------------------------------------------------------------------------

	@Test
	fun conditionsAreAboutTheFilesAndAllMustHold() {
		val images =
			action(
				"""{"id": "i", "label": "I", "when": {"types": ["image/*"]}, "run": ["x", "{path}"]}""",
			)

		val text =
			action(
				"""{"id": "t", "label": "T", "when": {"types": ["text/plain"]}, "run": ["x", "{path}"]}""",
			)

		val odd =
			action(
				"""{"id": "o", "label": "O", "when": {"ext": ["foo", "tar.gz"]}, "run": ["x", "{path}"]}""",
			)

		val two =
			action(
				"""{"id": "m", "label": "M", "when": {"count": "many"}, "run": ["x", "{paths}"]}""",
			)

		val folders =
			action(
				"""{"id": "f", "label": "F", "when": {"kind": "folder"}, "run": ["x", "{path}"]}""",
			)

		val world = Known()

		assertTrue(images.offered(files("a.png", "b.png"), world))
		assertFalse(images.offered(files("a.png", "b.kt"), world))
		// A type's lineage counts: Kotlin source is a kind of text.
		assertTrue(text.offered(files("b.kt"), world))
		// Any extension at all, whether prism knows the type or not.
		assertTrue(odd.offered(files("thing.foo", "old.TAR.GZ"), world))
		assertFalse(odd.offered(files("thing.bar"), world))
		assertFalse(two.offered(files("a.png"), world))
		assertTrue(two.offered(files("a.png", "b.png"), world))
		assertFalse(folders.offered(files("a.png"), world))
		assertTrue(
			folders.offered(
				Subject(On.Files, listOf(Thing.folder(home.resolve("Music"))), Where.Folder(home)),
				world,
			),
		)
	}

	@Test
	fun conditionsAreAboutWhereTheWindowIsAndCanBeNegated() {
		val results =
			action(
				"""{"id": "r", "label": "R", "when": {"in": ["results"]}, "run": ["x", "{path}"]}""",
			)

		val elsewhere =
			action(
				"""{"id": "e", "label": "E", "when": {"elsewhere": true}, "show": {"folder": "{parent}"}}""",
			)

		val notResults =
			action(
				"""{"id": "n", "label": "N", "when": {"not": {"in": ["results"]}}, "run": ["x", "{path}"]}""",
			)

		val inFolder = files("a.txt")
		val inResults = inFolder.copy(where = Where.Results)
		val world = Known()

		assertTrue(results.offered(inResults, world))
		assertFalse(results.offered(inFolder, world))
		assertTrue(elsewhere.offered(inResults, world))
		assertFalse(elsewhere.offered(inFolder, world))
		assertTrue(notResults.offered(inFolder, world))
		assertFalse(notResults.offered(inResults, world))
	}

	@Test
	fun conditionsOnProgramsAndOnWhatAFolderHoldsRunNothing() {
		val git =
			action(
				"""{"id": "g", "label": "G", "when": {"kind": "folder", "inside": [".git"], "programs": ["git"]},
				"run": ["git", "-C", "{path}", "log"], "how": {"terminal": true}}""",
			)

		val repo = Path("/src/prism")

		val deep =
			Subject(
				On.Files,
				listOf(Thing.folder(repo.resolve("browser/src"))),
				Where.Folder(repo.resolve("browser")),
			)

		assertTrue(git.offered(deep, Known(setOf("git"), setOf(repo.resolve(".git")))))
		assertFalse(git.offered(deep, Known(emptySet(), setOf(repo.resolve(".git")))))
		assertFalse(git.offered(deep, Known(setOf("git"))))
	}

	@Test
	fun whatAnActionsPlaceholdersNameMustBeThere() {
		val real = action("""{"id": "r", "label": "R", "run": ["x", "{paths}"]}""")
		val entry = Thing(Item.InPlace("src/main.c"), "main.c", ThingKind.File)
		val virtual = Subject(On.Files, listOf(entry), Where.Results)

		// A command that names paths is not offered for an entry that is nowhere on disk; nothing has to say so.
		assertFalse(real.offered(virtual, Known()))
		assertTrue(real.offered(files("a.txt"), Known()))
		// With nothing chosen there is nothing for it to name.
		assertFalse(real.offered(Subject(On.Files, emptyList(), Where.Results), Known()))
	}

	// ---- menus ------------------------------------------------------------------------------------------------------

	private fun Menu<*>.labels(): List<String> =
		entries.map {
			when (it) {
				is MenuEntry.Item -> it.label
				is MenuEntry.Submenu -> "${it.label} ▸ [${it.menu.labels().joinToString()}]"
				MenuEntry.Separator -> "—"
			}
		}

	private val mine =
		"""[
			{"id": "upper", "label": "Upper case", "when": {"ext": ["md"]}, "run": ["x", "{path}"], "key": "Ctrl+U"},
			{"id": "png", "label": "To PNG", "submenu": "Image/Convert", "when": {"ext": ["md"]}, "run": ["x", "{path}"]},
			{"id": "half", "label": "Half size", "submenu": "Image", "when": {"ext": ["md"]}, "run": ["x", "{path}"]},
			{"id": "here", "label": "Serve this folder", "when": {"target": "folder"}, "run": ["x", "{path}"]}
		]"""

	private fun exploring(actions: Actions): BrowserState =
		Fixtures.picker(Choosing.Exploring()).copy(actions = actions)

	private fun rowsOf(state: BrowserState, vararg names: String) =
		Target.Rows(names.map { name -> state.rows.list.single { it.name == name } })

	@Test
	fun theUsersActionsAreAmongAFilesOwnInTheirSubmenus() {
		val state = exploring(read(mine))

		val menu =
			contextMenuOf(
				state,
				rowsOf(state, "notes.md"),
				Bindings.of(Preferences(), state.actions),
				Known(),
			)

		assertEquals(
			listOf(
				"Open",
				"Open with…",
				"—",
				"Upper case",
				"Image ▸ [Half size, Convert ▸ [To PNG]]",
				"—",
				"Cut",
				"Copy",
				"—",
				"Rename",
				"Copy path",
				"—",
				"Move to trash",
				"Delete permanently",
				"—",
				"Properties",
			),
			menu.labels(),
		)

		val upper =
			menu.entries.filterIsInstance<MenuEntry.Item<BrowserEvent>>().single {
				it.label ==
					"Upper case"
			}

		assertEquals("Ctrl+U", upper.keys)
		assertInstanceOf(BrowserEvent.Did::class.java, upper.action)
		// For a file its conditions do not hold, it is not there; nor in a picker, which chooses and does nothing else.
		assertFalse(
			"Upper case" in contextMenuOf(state, rowsOf(state, "Music"), world = Known()).labels(),
		)
		assertFalse(
			"Upper case" in
				Fixtures.picker().copy(actions = state.actions).let {
					contextMenuOf(it, rowsOf(it, "notes.md"), world = Known()).labels()
				},
		)
	}

	@Test
	fun anActionForTheFolderIsInTheFoldersMenuAndEntriesCanBeHidden() {
		val state = exploring(read(mine))

		assertTrue(
			"Serve this folder" in contextMenuOf(state, Target.Folder, world = Known()).labels(),
		)

		val hidden =
			state.copy(
				actions = state.actions.copy(hidden = setOf("upper", "builtin.rename", "half")),
			)

		val menu = contextMenuOf(hidden, rowsOf(hidden, "notes.md"), world = Known()).labels()

		assertFalse("Upper case" in menu)
		assertFalse("Rename" in menu)
		assertTrue("Image ▸ [Convert ▸ [To PNG]]" in menu)
	}

	@Test
	fun aUsersActionIsBoundLikeAnyOtherAndItsChordIsThePreferencesToChange() {
		val actions = read(mine)
		val upper = Custom(actions["upper"]!!)
		val defaults = Bindings.of(Preferences(), actions)

		assertEquals(listOf(Chord.parse("Ctrl+U")), defaults.of(upper))
		assertEquals(listOf<Bindable>(upper), defaults.listings(Chord.parse("Ctrl+U")!!))

		val rebound = defaults.bound(upper, 0, Chord.parse("F9")!!)

		assertEquals(mapOf("action:upper" to listOf("F9")), rebound.differences())
		assertEquals(
			listOf(Chord.parse("F9")),
			Bindings.of(Preferences(bindings = rebound.differences()), actions).of(upper),
		)
	}

	// ---- doing an action --------------------------------------------------------------------------------------------

	private val notes get() = files("notes.md")

	@Test
	fun aWatchedCommandIsRunAndTheFolderReadAgainOnceItHas() {
		val action =
			action(
				"""{"id": "x", "label": "Stamp", "run": ["touch", "{stem}.stamp"], "how": {"watched": true},
				"then": {"reread": true, "choose": "{stem}.stamp"}}""",
			)

		val state = exploring(Actions.NONE)
		val did = step(state, BrowserEvent.Did(action, notes))
		val job = (did.effects.single() as Effect.Run).job

		assertEquals(listOf("Stamp"), did.state.running.map { it.task.label })
		assertEquals(
			Job(
				"Stamp",
				listOf(
					Invocation(
						listOf("touch", "notes.stamp"),
						home,
						mapOf("folder" to home.toString()),
					),
				),
				After.Reread("notes.stamp"),
			),
			job,
		)

		val ran = step(did.state, Answer.Ran(job, null))

		assertEquals(emptyList<Work>(), ran.state.running)
		assertEquals(listOf<Effect>(Effect.Read(home)), ran.effects)
		assertEquals(Landing.On(RowId("notes.stamp"), choose = true), ran.state.landing)

		// A command that fails says what it wrote, and the folder is read again all the same.
		val failed = step(did.state, Answer.Ran(job, "touch: cannot touch"))

		assertEquals(
			Notice.Failed("“Stamp” failed", "touch: cannot touch"),
			failed.state.notice,
		)
		assertEquals(listOf<Effect>(Effect.Read(home)), failed.effects)
	}

	@Test
	fun aCommandNotWatchedIsStartedAndForgotten() {
		val detached = action("""{"id": "c", "label": "Code", "run": ["code", "{paths}"]}""")

		val terminal =
			action(
				"""{"id": "l", "label": "Log", "run": ["git", "log"], "how": {"terminal": true}}""",
			)

		val state = exploring(Actions.NONE)

		assertEquals(
			false,
			(
				step(
					state,
					BrowserEvent.Did(detached, notes),
				).effects.single() as Effect.Launch
			).terminal,
		)
		assertEquals(
			true,
			(
				step(
					state,
					BrowserEvent.Did(terminal, notes),
				).effects.single() as Effect.Launch
			).terminal,
		)
	}

	@Test
	fun aLineAskedForIsTypedBeforeAnythingRuns() {
		val action =
			action(
				"""{"id": "z", "label": "Compress", "ask": {"to": {"text": "Name of the archive", "start": "{stem}"}},
				"run": ["tar", "-czf", "{to}.tar.gz", "--", "{names}"], "how": {"watched": true}}""",
			)

		val asked = step(exploring(Actions.NONE), BrowserEvent.Did(action, notes))

		assertEquals(Notice.Asking("Name of the archive", "notes"), asked.state.notice)
		assertEquals(emptyList<Effect>(), asked.effects)

		val typed = step(asked.state, BrowserEvent.AnswerTyped("old notes")).state
		val given = step(typed, BrowserEvent.Confirmed)

		assertNull(given.state.notice)
		assertNull(given.state.pending)
		assertEquals(
			listOf("tar", "-czf", "old notes.tar.gz", "--", "notes.md"),
			(given.effects.single() as Effect.Run)
				.job.invocations
				.single()
				.words,
		)
		// What was typed is the command's to read from its environment too.
		assertEquals(
			"old notes",
			(given.effects.single() as Effect.Run)
				.job.invocations
				.single()
				.environment["to"],
		)

		// Escape lets go of the action with the question.
		val dismissed = step(typed, BrowserEvent.Dismissed)

		assertNull(dismissed.state.pending)
		assertEquals(emptyList<Effect>(), dismissed.effects)
	}

	@Test
	fun aFolderAskedForIsChosenInAPickerAndCancellingItRunsNothing() {
		val action =
			action(
				"""{"id": "e", "label": "Extract to…", "ask": {"dest": {"folder": "Extract to"}},
				"run": ["tar", "-xf", "{path}", "-C", "{dest}"], "how": {"watched": true}}""",
			)

		val asked =
			step(exploring(Actions.NONE), BrowserEvent.Did(action, files("backup.tar.gz")))

		assertEquals(
			listOf<Effect>(Effect.Ask(Request.PickFolder("Extract to", home))),
			asked.effects,
		)

		val chosen = step(asked.state, BrowserEvent.Answered(Path("/tmp/out")))

		assertEquals(
			listOf("tar", "-xf", "/home/avery/backup.tar.gz", "-C", "/tmp/out"),
			(chosen.effects.single() as Effect.Run)
				.job.invocations
				.single()
				.words,
		)

		val cancelled = step(asked.state, BrowserEvent.Answered(null))

		assertNull(cancelled.state.pending)
		assertEquals(emptyList<Effect>(), cancelled.effects)
	}

	@Test
	fun anActionThatWantsToBeConfirmedIsNotDoneUntilItIs() {
		val action =
			action(
				"""{"id": "w", "label": "Wipe", "run": ["shred", "{path}"],
				"how": {"watched": true, "confirm": "This cannot be undone."}}""",
			)

		val asked = step(exploring(Actions.NONE), BrowserEvent.Did(action, notes))

		assertEquals(Notice.ConfirmAction("Wipe", "This cannot be undone."), asked.state.notice)
		assertEquals(emptyList<Effect>(), asked.effects)
		assertInstanceOf(
			Effect.Run::class.java,
			step(asked.state, BrowserEvent.Confirmed).effects.single(),
		)
		assertEquals(emptyList<Effect>(), step(asked.state, BrowserEvent.Dismissed).effects)
	}

	@Test
	fun anActionThatShowsAFolderGoesThereOrOpensAWindowOnIt() {
		val here =
			action(
				"""{"id": "c", "label": "Open containing folder", "show": {"folder": "{parent}", "choose": "{name}"}}""",
			)

		val window =
			action(
				"""{"id": "w", "label": "In a window", "show": {"folder": "{parent}", "in": "window"}}""",
			)

		val found =
			Subject(
				On.Files,
				listOf(Thing.file(home.resolve("Documents/letter.odt"))),
				Where.Results,
			)

		val state = exploring(Actions.NONE)
		val went = step(state, BrowserEvent.Did(here, found))

		assertEquals(Location.Folder(home.resolve("Documents")), went.state.at)
		assertEquals(Landing.On(RowId("letter.odt"), choose = true), went.state.landing)
		assertEquals(
			listOf<Effect>(Effect.Ask(Request.NewWindow(home.resolve("Documents")))),
			step(state, BrowserEvent.Did(window, found)).effects,
		)
	}

	@Test
	fun whatIsRunningIsToldWhatItWritesAndStoppedWithoutBeingSaidToHaveFailed() {
		val action =
			action(
				"""{"id": "x", "label": "Pack", "run": ["tar", "-cvf", "a.tar", "."], "how": {"watched": true}}""",
			)

		val did = step(exploring(Actions.NONE), BrowserEvent.Did(action, notes))
		val task =
			did.state.running
				.single()
				.task
		val job = (did.effects.single() as Effect.Run).job

		assertEquals(Task.Running(job), task)

		val told =
			step(
				step(did.state, Answer.Said(task, "./a")).state,
				Answer.Said(task, "./b"),
			).state

		assertEquals(listOf("./a", "./b"), told.running.single().said)

		// Stopped by the user, its command is ended; that it then ends is nothing to tell them of.
		val stopping = step(told, BrowserEvent.Stopped(task))

		assertEquals(listOf<Effect>(Effect.Stop(task)), stopping.effects)
		assertTrue(
			stopping.state.running
				.single()
				.stopping,
		)

		val ended = step(stopping.state, Answer.Ran(job, ProcessCommands.STOPPED)).state

		assertEquals(emptyList<Work>(), ended.running)
		assertNull(ended.notice)
		// Something not under way is not there to stop.
		assertEquals(emptyList<Effect>(), step(ended, BrowserEvent.Stopped(task)).effects)
	}

	@Test
	fun aCommandThatWorksInAFolderOfItsOwnIsGivenOneWhichIsItsJobsToBeRidOf() {
		val action =
			action(
				"""{"id": "s", "label": "Sort", "run": ["sh", "-c", "sort \"$2\" > \"$1/sorted\"", "sh", "{temp}", "{path}"],
				"how": {"watched": true}}""",
			)

		assertTrue(action.scratch)

		val asked = step(exploring(Actions.NONE), BrowserEvent.Did(action, notes))
		val pending = (asked.effects.single() as Effect.Scratch).pending
		val made = step(asked.state, Answer.ScratchMade(home.resolve("scratch"), pending))
		val job = (made.effects.single() as Effect.Run).job

		assertEquals(home.resolve("scratch"), job.scratch)
		assertEquals("/home/avery/scratch", job.invocations.single().words[4])
	}

	// ---- several ways, and programs ---------------------------------------------------------------------------------

	private val packed =
		"""{"id": "pack", "label": "Pack", "ways": [
			{"programs": ["zip"], "run": ["zip", "-r", "out.zip", "{names}"]},
			{"programs": ["7zz|7z"], "run": ["{which:7zz|7z}", "a", "out.zip", "{names}"]}
		]}"""

	private fun readWith(vararg programs: String): Actions =
		Actions.read(listOf(file("[$packed]"))) { it in programs }

	private fun wordsOf(actions: Actions): List<String> =
		((actions["pack"]!!.deed as Deed.Run).command.fill(files("a.txt").filling())!!).single()

	@Test
	fun anActionIsReadAsTheFirstOfItsWaysWhoseProgramsAreInstalled() {
		assertEquals(listOf("zip", "-r", "out.zip", "a.txt"), wordsOf(readWith("zip", "7z")))
		// Without the first way's program, the next; and of the programs that way can use, the first there is.
		assertEquals(listOf("7z", "a", "out.zip", "a.txt"), wordsOf(readWith("7z")))
		assertEquals(listOf("7zz", "a", "out.zip", "a.txt"), wordsOf(readWith("7z", "7zz")))
	}

	@Test
	fun anActionNoneOfWhoseWaysCanBeRunIsLeftOutAndIsNotWrong() {
		val none = readWith()

		assertEquals(emptyList<UserAction>(), none.actions)
		assertEquals(emptyList<Broken>(), none.broken)
		assertEquals(setOf("pack"), none.unavailable)

		// One written wrong is wrong on every machine, whichever of its ways would be run.
		val wrong =
			Actions.read(
				listOf(
					file(
						"""[{"id": "w", "label": "W", "ways": [
							{"programs": ["zip"], "run": ["zip", "{names}"]},
							{"programs": ["never"], "run": ["never", "{nowhere}"]}
						]}]""",
					),
				),
			) { it == "zip" }

		assertEquals(listOf("w"), wrong.broken.map { it.id })
		assertTrue(
			"both" in
				wrong("""{"id": "b", "label": "B", "run": ["x"], "ways": [{"run": ["y"]}]}"""),
		)
	}

	@Test
	fun aConditionOnProgramsTakesEitherOfSeveral() {
		val either =
			action(
				"""{"id": "e", "label": "E", "when": {"programs": ["7zz|7z", "tar"]}, "run": ["x", "{path}"]}""",
			)

		assertTrue(either.offered(files("a.txt"), Known(setOf("7z", "tar"))))
		assertTrue(either.offered(files("a.txt"), Known(setOf("7zz", "tar"))))
		assertFalse(either.offered(files("a.txt"), Known(setOf("tar"))))
		assertFalse(either.offered(files("a.txt"), Known(setOf("7z"))))
	}

	@Test
	fun whatACommandMadeIsBroughtToWhereItIsWantedByName() {
		val places = """[{"id": "box", "takes": ["box"], "list": ["true"], "read": "names", "out": "out"}]"""

		val out =
			read(
				"""[{"id": "out", "label": "Out", "when": {"in": ["box"]}, "ask": {"dest": {"folder": "To"}},
				"run": ["unbox", "{box}", "{temp}", "{entries}"], "how": {"watched": true},
				"then": {"bring": {"from": "{temp}", "to": "{dest}"}}}]""",
				places,
			)

		val action = out.actions.single()
		val placed = Placed(out.places.getValue("box"), mapOf("box" to "/b.box"))

		val subject =
			Subject(
				On.Files,
				listOf(
					Thing(Item.InPlace("a/b/c.txt"), "c.txt", ThingKind.File),
					Thing(Item.InPlace("a/d/"), "d", ThingKind.Folder),
				),
				Where.Place(placed, listOf("a")),
			)

		assertTrue(action.scratch)

		val asked =
			step(
				exploring(out),
				BrowserEvent.Did(
					action,
					subject,
					mapOf("dest" to "/tmp/dest"),
				),
			)

		val made =
			step(
				asked.state,
				Answer.ScratchMade(
					Path("/tmp/prism/1-x"),
					(asked.effects.single() as Effect.Scratch).pending,
				),
			)

		val job = (made.effects.single() as Effect.Run).job

		assertEquals(
			listOf("unbox", "/b.box", "/tmp/prism/1-x", "a/b/c.txt", "a/d/"),
			job.invocations.single().words,
		)
		// Each entry from where the command put it, under the folders it sits in, to the folder by its own name.
		assertEquals(
			Delivery(
				Path("/tmp/prism/1-x"),
				Path("/tmp/dest"),
				listOf(
					"a/b/c.txt" to "c.txt",
					"a/d/" to "d",
				),
			),
			job.bring,
		)
		assertEquals(Path("/tmp/prism/1-x"), job.scratch)
	}

	@Test
	fun anActionThatIsADefaultIsWhatOpenDoesAndIsNotOfferedTwice() {
		val opener =
			"""[{"id": "look", "label": "Look inside", "default": true, "when": {"ext": ["md"], "count": "one"},
			"run": ["look", "{path}"]}]"""
		val state = exploring(read(opener))
		val menu = contextMenuOf(state, rowsOf(state, "notes.md"), world = Known())
		val open = menu.entries.filterIsInstance<MenuEntry.Item<BrowserEvent>>().first()

		assertEquals("Open", open.label)
		assertEquals("look", (open.action as BrowserEvent.Did).action.id)
		assertFalse("Look inside" in menu.labels())
		// Another application can be chosen for it this once, and not made the default of its type.
		assertEquals(
			Request.OpenWith(listOf(home.resolve("notes.md")), settable = false),
			menu.entries
				.filterIsInstance<MenuEntry.Item<BrowserEvent>>()
				.single { it.label == "Open with…" }
				.let { (it.action as BrowserEvent.Requested).request },
		)
		// A file it is not for is opened as ever.
		assertInstanceOf(
			BrowserEvent.Picked::class.java,
			contextMenuOf(state, rowsOf(state, "main.kt"), world = Known())
				.entries
				.filterIsInstance<MenuEntry.Item<BrowserEvent>>()
				.first()
				.action,
		)
	}
}
