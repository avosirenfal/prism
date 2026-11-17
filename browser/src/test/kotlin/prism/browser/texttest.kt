package prism.browser

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.XdgPlaces

/**
 * Text searches (docs/text.md), with the machine's own `rg`, `grep` and `strings` against real files: the search that
 * ships opened on what a window shows, what is typed looked for and what it finds listed under the file each line is
 * in, the file shown beside them, a line opened in the editor; and all of it drawn.
 */
class TextTest {
	@TempDir
	lateinit var root: Path

	@TempDir
	lateinit var bin: Path

	private val services by lazy { Services(JvmDisk(trash = Trash(bin)), home = root) }
	private val world get() = services.world

	private fun shipped(environment: (String) -> String? = { null }) =
		Actions.read(
			listOf(Actions.SHIPPED),
			installed = world::installed,
			environment = environment,
		)

	private fun has(vararg programs: String) =
		assumeTrue(programs.all(world::installed), "needs ${programs.joinToString()}")

	/** A small project: sources in two folders, and notes beside them. */
	private fun project() {
		root.resolve("launcher").createDirectories()
		root.resolve("linux").createDirectories()
		root.resolve("launcher/step.kt").writeText(
			"""
			package prism.launcher

			/** What the launcher is told, one event at a time. */
			sealed interface Event {
				/** A letter typed into the field. */
				data class Typed(
					val text: String,
				) : Event

				/** The cursor moved among what was found. */
				data class Moved(
					val by: Int,
				) : Event

				data class Chosen(
					val row: Int,
				) : Event

				data object Closed : Event
			}

			fun step(state: State, event: Event): State =
				when (event) {
					is Event.Typed -> state.copy(text = event.text)
					is Event.Moved -> state.copy(cursor = state.cursor + event.by)
					is Event.Chosen -> state.copy(chosen = event.row)
					Event.Closed -> state
				}
			""".trimIndent() + "\n",
		)
		root.resolve("launcher/actions.kt").writeText(
			"""
			package prism.launcher

			sealed interface Entry {
				data class Run(
					val words: List<String>,
				) : Entry

				data class Reveal(
					val path: String,
				) : Entry
			}

			/** What an entry's menu offers. */
			data class EntryMenu(
				val entries: List<Entry>,
			)
			""".trimIndent() + "\n",
		)
		root.resolve("linux/epoll.kt").writeText(
			"""
			package prism.linux

			/** How long a wait waits: not at all, a bound, or until something is ready. */
			sealed interface Wait {
				val millis: Int

				data object Never : Wait {
					override val millis get() = 0
				}

				data class AtMost(
					override val millis: Int,
				) : Wait {
					init {
						require(millis > 0) { "a bounded wait is positive; Never is the zero one" }
					}
				}

				data object Forever : Wait {
					override val millis get() = -1
				}
			}

			class Epoll private constructor(
				val fd: Int,
			) : AutoCloseable {
				override fun close() = Unit
			}
			""".trimIndent() + "\n",
		)
		root.resolve("notes.md").writeText("# Notes\n\nNothing here is of a kind.\n")
	}

	/** An explorer on [folder], with what ships, settled. */
	private fun exploring(folder: Path = root, actions: Actions = shipped()): BrowserState =
		step(
			opened(
				Choosing.Exploring(),
				folder,
				root,
				sidebarOf(XdgPlaces(), root),
			).settled(services),
			Answer.Configured(actions),
		).state

	private fun BrowserState.then(input: Input): BrowserState =
		step(this, input).settled(services)

	/** The text search that ships, opened on everything [this] shows, and [query] typed into it. */
	private fun BrowserState.searching(query: String): BrowserState =
		then(BrowserEvent.SearchedText(actions.search("text.search")!!))
			.then(BrowserEvent.TextTyped(query))

	private fun BrowserState.lines(): List<String> =
		text!!.rows.let { rows ->
			(0 until rows.size).map { index ->
				when (val row = rows[index]) {
					is TextRow.File -> "${root.relativize(row.id.path)} (${row.lines})"
					is TextRow.Line -> "  ${row.hit.position}: ${row.hit.text.trim()}"
				}
			}
		}

	/** [png] kept where `-Pbrowser.screenshots=DIR` says, for a person to look at. */
	private fun kept(name: String, png: ByteArray) {
		assertTrue(png.size > 1000, name)
		System.getProperty("browser.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
			Files.createDirectories(Path.of(dir))
			Files.write(Path.of(dir, "$name.png"), png)
		}
	}

	@Test
	fun whatIsTypedIsLookedForInEveryFileShownAndListedUnderItsFile() {
		has("rg", "xargs")
		project()

		val found = exploring().searching("data class")

		assertEquals(
			listOf(
				"launcher/actions.kt (3)",
				"  4: data class Run(",
				"  8: data class Reveal(",
				"  14: data class EntryMenu(",
				"launcher/step.kt (3)",
				"  6: data class Typed(",
				"  11: data class Moved(",
				"  15: data class Chosen(",
				"linux/epoll.kt (1)",
				"  11: data class AtMost(",
			),
			// Files come in the order the program found them, which is not said: by name, here.
			found.byFile(),
		)
	}

	/** The files in the order they are listed, each with its lines: the order a program finds files in is not said. */
	private fun BrowserState.byFile(): List<String> =
		lines().let { lines ->
			val files = lines.indices.filter { !lines[it].startsWith("  ") } + lines.size

			files
				.zipWithNext { from, to -> lines.subList(from, to) }
				.sortedBy { it.first() }
				.flatten()
		}

	private fun BrowserState.row(name: String): Row = rows.list.single { it.name == name }

	private fun Menu<BrowserEvent>.labels(): List<String> =
		entries.flatMap {
			when (it) {
				is MenuEntry.Item -> listOf(it.label)
				is MenuEntry.Submenu -> it.menu.labels().map { label -> "${it.label} ▸ $label" }
				MenuEntry.Separator -> emptyList()
			}
		}

	// ---- looking -----------------------------------------------------------------------------------------------------

	@Test
	fun grepFindsTheSameWhereThereIsNoRipgrep_AndWhatItColoursIsWhatMatched() {
		has("grep", "xargs")
		project()

		val grep = Actions.read(listOf(Actions.SHIPPED)) { it != "rg" && world.installed(it) }
		val found = exploring(actions = grep).searching("data class")

		assertEquals(
			listOf("xargs", "-0", "-r", "grep"),
			grep
				.search("text.search")!!
				.run.words
				.take(4)
				.map { ((it as Word.One).phrase.pieces.single() as Piece.Text).text },
		)
		assertEquals(7, found.text!!.rows.lines)
		assertEquals(
			listOf(
				"launcher/step.kt (3)",
				"  6: data class Typed(",
				"  11: data class Moved(",
				"  15: data class Chosen(",
			),
			found.byFile().dropWhile { !it.startsWith("launcher/step.kt") }.take(4),
		)

		val line =
			found.text!!.rows.let { rows ->
				(0 until rows.size).firstNotNullOf {
					rows[it] as? TextRow.Line
				}
			}

		assertEquals(
			listOf("data class"),
			line.hit.marks.map { line.hit.text.substring(it.first, it.last + 1) },
		)
	}

	@Test
	fun anOptionTurnedIsGivenToTheCommand_WhichIsRunAgain() {
		has("rg", "xargs")
		project()

		val loose = exploring().searching("never")
		val exact = loose.then(BrowserEvent.TextOptioned("case"))
		val whole = exploring().searching("wait").then(BrowserEvent.TextOptioned("word"))

		// Without case until "Match case" is on: "Never" twice, and then no "never" at all.
		assertEquals(2, loose.text!!.rows.lines)
		assertEquals(setOf("case"), exact.text!!.on)
		assertEquals(0, exact.text!!.rows.lines)
		// "waits" is no "wait", as a whole word.
		assertTrue(whole.lines().none { "waits:" in it && "wait " !in it.lowercase() })
		assertEquals(
			listOf("--word-regexp", "--ignore-case", "--fixed-strings"),
			whole.text!!
				.search
				.words("wait", setOf("word"), root)!!
				.filter { it.startsWith("--") && it != "--json" && it != "--" }
				.sortedBy { it != "--word-regexp" },
		)
	}

	@Test
	fun whatIsLookedInIsWhatTheWindowShowed_OrWhatWasChosenOfIt() {
		has("rg", "xargs")
		project()

		val search = exploring().actions.search("text.search")!!
		val folder = exploring()
		// Narrowed to what has "lin" in its name, the folder shows `linux` alone.
		val narrowed = folder.then(BrowserEvent.Searched("lin"))

		val chosen =
			folder.then(
				BrowserEvent.SearchedText(search, Target.Rows(listOf(folder.row("launcher")))),
			)

		fun BrowserState.files(): Set<String> =
			then(BrowserEvent.TextTyped("class"))
				.text!!
				.rows.paths
				.mapTo(HashSet()) { root.relativize(it).toString() }

		assertEquals(
			setOf("launcher/step.kt", "launcher/actions.kt", "linux/epoll.kt"),
			folder.then(BrowserEvent.SearchedText(search)).files(),
		)
		assertEquals(
			setOf("linux/epoll.kt"),
			narrowed.then(BrowserEvent.SearchedText(search)).files(),
		)
		assertEquals(setOf("launcher/step.kt", "launcher/actions.kt"), chosen.files())
		assertEquals("launcher", chosen.text!!.scope.said)
	}

	@Test
	fun stringsAreFoundOnce_AndWhatIsTypedNarrowsThem() {
		has("strings", "xargs")
		Files.write(
			root.resolve("tool.bin"),
			byteArrayOf(0, 1, 2) + "usage: tool [options]".toByteArray() + byteArrayOf(0, 7, 0) +
				"error: no such option".toByteArray() + byteArrayOf(0),
		)

		val folder = exploring()
		val search = folder.actions.search("text.strings")!!
		val found = folder.then(BrowserEvent.SearchedText(search))
		val narrowed = found.then(BrowserEvent.TextTyped("OPTION no"))

		// Run as it is opened, with nothing typed: every string, each at the byte it starts at.
		assertEquals(
			listOf("tool.bin (2)", "  3: usage: tool [options]", "  27: error: no such option"),
			found.lines(),
		)
		// Typed, it narrows what was found, without case, by every word; the command is not run again.
		assertEquals(listOf("tool.bin (1)", "  27: error: no such option"), narrowed.lines())
		assertTrue(
			narrowed.text!!
				.stages[0]
				.done!!
				.raw ===
				found.text!!
					.stages[0]
					.done!!
					.raw,
		)
		assertEquals("1 line in 1 file", textStatus(narrowed.text!!))
		// A place in a file that is a byte of it is what its preview is given.
		assertEquals("27", narrowed.text!!.valuesOf(narrowed.text!!.cursor!!)["offset"])
		assertEquals("1 line in 1 file", textStatus(narrowed.text!!))
	}

	// ---- the reducer -------------------------------------------------------------------------------------------------

	/** An explorer on a text search that reads `lines`, opened on the folder, with nothing run. */
	private fun texting(query: QueryUse = QueryUse.Typed): BrowserState {
		// Something to look in: a search is not opened on nothing.
		root.resolve("x.txt").writeText("x")

		val search =
			Actions
				.read(
					listOf(
						prism.preferences.ActionsFile.parse(
							"""{"textSearches": [{"id": "t", "label": "Find…", "query": "${query.word}",
							   "run": ["find-it", "{query}"]}]}""",
						),
					),
				).search("t")!!

		return step(exploring(), BrowserEvent.SearchedText(search)).state
	}

	private fun hit(file: String, at: Long, text: String = "line $at") =
		FileHit(root.resolve(file), Hit(at, text))

	@Test
	fun eachLetterTypedTakesThePlaceOfTheRunBeforeIt_WhoseLinesAreThenDropped() {
		val opened = texting()

		// Nothing typed: nothing is run.
		assertEquals(null, opened.text!!.running)

		val first = step(opened, BrowserEvent.TextTyped("a"))
		val a = (first.effects.single() as Effect.FindText).run!!
		val second = step(first.state, BrowserEvent.TextTyped("ab"))
		val ab = (second.effects.single() as Effect.FindText).run!!

		assertEquals("a" to "ab", a.query to ab.query)

		// What the run overtaken found is of no run the window knows.
		val late = step(second.state, Answer.TextFound(a, listOf(hit("x.txt", 1))))

		assertEquals(
			0,
			late.state.text!!
				.rows.lines,
		)

		val found =
			step(late.state, Answer.TextFound(ab, listOf(hit("x.txt", 1), hit("y.txt", 2))))
		val more = step(found.state, Answer.TextFound(ab, listOf(hit("x.txt", 5))))

		// The lines of a file are under it, whenever they came; the cursor is on the first, and its file is asked for.
		assertEquals(
			listOf("x.txt (2)", "  1: line 1", "  5: line 5", "y.txt (1)", "  2: line 2"),
			more.state.lines(),
		)
		assertEquals(TextRowId.Line(root.resolve("x.txt"), 0), more.state.text!!.cursor)
		assertEquals(root.resolve("x.txt"), (found.effects.single() as Effect.Preview).ask.path)
		// The same file again asks for nothing more.
		assertEquals(emptyList<Effect>(), more.effects)

		// What was found stays while the next run finds nothing yet, and goes when it ends having found none.
		val third = step(more.state, BrowserEvent.TextTyped("abc"))
		val abc = (third.effects.single() as Effect.FindText).run!!

		assertEquals(
			3,
			third.state.text!!
				.rows.lines,
		)
		assertEquals("3 lines in 2 files · searching…", textStatus(third.state.text!!))

		val ended = step(third.state, Answer.TextEnded(abc, TextEnding.Done())).state

		assertEquals(0, ended.text!!.rows.lines)
		assertEquals(null, ended.text!!.running)
		assertEquals("No results", textStatus(ended.text!!))

		// Emptied, it runs nothing, and what was under way is stopped.
		val emptied = step(third.state, BrowserEvent.TextTyped(""))

		assertEquals(listOf(Effect.FindText(null)), emptied.effects)
		assertEquals(null, emptied.state.text!!.running)
	}

	@Test
	fun theCursorMovesAmongFilesAndLines_AndAFileFoldedKeepsItOnTheFile() {
		val opened = step(texting(), BrowserEvent.TextTyped("a"))
		val run = opened.state.text!!.running!!

		val found =
			step(
				opened.state,
				Answer.TextFound(run, listOf(hit("x.txt", 1), hit("x.txt", 5), hit("y.txt", 2))),
			).state

		fun BrowserState.after(vararg events: BrowserEvent): BrowserState =
			events.fold(this) { state, event -> step(state, event).state }

		val x = root.resolve("x.txt")
		val y = root.resolve("y.txt")

		assertEquals(
			TextRowId.Line(x, 1),
			found.after(BrowserEvent.TextMoved(Move.By(1))).text!!.cursor,
		)
		assertEquals(
			TextRowId.File(y),
			found
				.after(
					BrowserEvent.TextMoved(Move.By(1)),
					BrowserEvent.TextMoved(Move.By(1)),
				).text!!
				.cursor,
		)
		assertEquals(
			TextRowId.Line(y, 0),
			found.after(BrowserEvent.TextMoved(Move.Last)).text!!.cursor,
		)
		assertEquals(
			TextRowId.File(x),
			found.after(BrowserEvent.TextMoved(Move.By(-9))).text!!.cursor,
		)

		val folded = found.after(BrowserEvent.TextFolded(x))

		assertEquals(listOf("x.txt (2)", "y.txt (1)", "  2: line 2"), folded.lines())
		assertEquals(TextRowId.File(x), folded.text!!.cursor)
		assertEquals(found.lines(), folded.after(BrowserEvent.TextFolded(x)).lines())
		assertEquals(
			listOf("x.txt (2)", "y.txt (1)"),
			found.after(BrowserEvent.TextFoldedAll(true)).lines(),
		)

		// Stopped, what it found is what it found; and Escape leaves the search as Back does, to be come back to.
		val stopped = step(found, BrowserEvent.TextStopped)

		assertEquals(listOf(Effect.FindText(null)), stopped.effects)
		assertEquals("3 lines in 2 files · stopped", textStatus(stopped.state.text!!))

		val left = step(found, BrowserEvent.ClickedNowhere)

		assertEquals(Location.Folder(root), left.state.at)
		assertTrue(Effect.FindText(null) in left.effects)
		assertEquals(
			3,
			(
				left.state.history.forward
					.single() as Location.Text
			).rows.lines,
		)
	}

	@Test
	fun aLineIsOpenedInTheEditor_AtItsLine_OrByTheFilesApplicationWhereThereIsNone() {
		fun opening(
			environment: (String) -> String?,
			written: String = "{}",
			event: BrowserEvent = BrowserEvent.TextOpened(),
		): Effect {
			val actions =
				Actions.read(
					listOf(
						prism.preferences.ActionsFile.parse(
							"""{"textSearches": [{"id": "t", "label": "Find…", "run": ["find-it", "{query}"]}]}""",
						),
						prism.preferences.ActionsFile.parse(written),
					),
					environment = environment,
				)

			root.resolve("a b.txt").writeText("the line")

			val opened =
				step(
					exploring(actions = actions),
					BrowserEvent.SearchedText(actions.search("t")!!),
				).state

			val typed = step(opened, BrowserEvent.TextTyped("a")).state

			val found =
				step(
					typed,
					Answer.TextFound(typed.text!!.running!!, listOf(hit("a b.txt", 12, "the line"))),
				).state

			assertEquals(emptyList<Broken>(), actions.broken)

			return step(found, event).effects.single()
		}

		fun Effect.words(): Pair<List<String>, Boolean> =
			(this as Effect.Launch).let {
				it.job.invocations
					.single()
					.words to it.terminal
			}

		val file = root.resolve("a b.txt").toString()

		// The environment's editor, in a terminal, told the line as most are.
		assertEquals(
			listOf("nvim", "+12", file) to true,
			opening({ "nvim".takeIf { _ -> it == "EDITOR" } }).words(),
		)
		// VISUAL before EDITOR; one known to have a window is given the line its own way, and no terminal.
		assertEquals(
			listOf("code", "--wait", "--goto", "$file:12") to false,
			opening({ mapOf("VISUAL" to "code --wait", "EDITOR" to "vi")[it] }).words(),
		)
		// The preferences' editor before either.
		assertEquals(
			listOf("zed", "$file:12") to false,
			opening({ "vi" }, """{"editor": {"run": ["zed", "{path}:{line}"]}}""").words(),
		)
		// A line of the file shown, which need be none found, and the file itself, at its top.
		assertEquals(
			listOf("vi", "+40", file) to true,
			opening(
				{
					"vi"
				},
				event =
					BrowserEvent.TextOpened(
						TextRowId.File(root.resolve("a b.txt")),
						40,
					),
			).words(),
		)
		assertEquals(
			listOf("vi", "+1", file) to true,
			opening({
				"vi"
			}, event = BrowserEvent.TextOpened(TextRowId.File(root.resolve("a b.txt")))).words(),
		)
		// With no editor at all, the file's application, which is not told the line.
		assertEquals(Effect.Open(listOf(root.resolve("a b.txt"))), opening({ null }))
	}

	// ---- steps, one after another -------------------------------------------------------------------------------------

	/** A step added: the search called [id], or a query of the files' names where it is null. */
	private fun BrowserState.adding(id: String?): BrowserState =
		then(BrowserEvent.TextStepAdded(id?.let { actions.search(it)!! }))

	private fun BrowserState.typing(text: String): BrowserState =
		then(BrowserEvent.TextTyped(text))

	private fun BrowserState.files(): List<String> =
		text!!
			.rows.paths
			.map { root.relativize(it).toString() }
			.sorted()

	/** How many files each step gave, in order. */
	private fun BrowserState.gave(): List<Int> =
		text!!.stages.map {
			it.done!!
				.out.hits.groups.size
		}

	@Test
	fun aSecondSearchLooksInTheFilesTheFirstFound_WhichIsTheFilesThatHaveBoth() {
		has("rg", "xargs")
		project()

		// Every file with "class" in it; and then, of those, the ones with "Forever" in them, on whatever line.
		val first = exploring().searching("class")
		val both = first.adding("text.search").typing("Forever")

		assertEquals(
			listOf("launcher/actions.kt", "launcher/step.kt", "linux/epoll.kt"),
			first.files(),
		)
		assertEquals(listOf("linux/epoll.kt"), both.files())
		// What is listed is what the last step found: its lines, in the files that passed the first.
		assertEquals(
			listOf("linux/epoll.kt (1)", "  19: data object Forever : Wait {"),
			both.lines(),
		)
		assertEquals(listOf(3, 1), both.gave())
		assertEquals("“class” › “Forever”", both.text!!.said)

		// Just added, with nothing typed for it, a step gives what it was given: the list is not emptied.
		assertEquals(first.lines(), first.adding("text.search").lines())
		// The search's own key, beside what one found, is that step added.
		assertEquals(
			2,
			first
				.then(
					BrowserEvent.SearchedText(first.actions.search("text.search")!!),
				).text!!
				.stages.size,
		)
	}

	@Test
	fun aStepTurnedAboutGivesTheFilesItFoundNothingIn_WithTheLinesTheyCameWith() {
		has("rg", "xargs")
		project()

		val without =
			exploring()
				.searching("class")
				.adding("text.search")
				.typing("Forever")
				.then(BrowserEvent.TextWithout)

		// The files with "class" and no "Forever": and what is listed of them is still where "class" is.
		assertEquals(listOf("launcher/actions.kt", "launcher/step.kt"), without.files())
		assertEquals(6, without.text!!.rows.lines)
		assertTrue(without.lines().all { !it.startsWith("  ") || "class" in it })
		assertEquals("“class” › not “Forever”", without.text!!.said)
		assertEquals("text.search", without.text!!.search.id)

		// The first step is given what the window showed, folders among it: it cannot be turned about.
		val first = exploring().searching("class")

		assertEquals(first.text!!.stages, first.then(BrowserEvent.TextWithout).text!!.stages)
	}

	@Test
	fun aStepChangedIsRunAgainWithThoseAfterIt_AndOneTakenOutLeavesTheRestToFollow() {
		has("rg", "xargs")
		project()

		val chained =
			exploring()
				.searching("class")
				.adding(null)
				.typing("dir:launcher")
				.adding("text.search")
				.typing("Typed")

		// Lines with "class"; of those files, the ones under `launcher`; and in those, "Typed".
		assertEquals(listOf(3, 2, 1), chained.gave())
		assertEquals(listOf("launcher/step.kt"), chained.files())
		assertEquals(2, chained.text!!.at)

		// The first step chosen, what is typed is typed for it: it and those after it are run again.
		val edited = chained.then(BrowserEvent.TextStepChosen(0)).typing("Epoll")

		assertEquals("Epoll", edited.text!!.query)
		assertEquals(listOf(1, 0, 0), edited.gave())
		assertEquals(emptyList<String>(), edited.files())
		assertEquals("No results", textStatus(edited.text!!))

		// The step of names taken out, the last is given what the first gives.
		val removed = chained.then(BrowserEvent.TextStepRemoved(1))

		assertEquals(listOf(3, 1), removed.gave())
		assertEquals(1, removed.text!!.at)
		assertEquals("“class” › “Typed”", removed.text!!.said)
		// And the last taken out, what the one before it gave is what is listed.
		assertEquals(
			chained
				.then(BrowserEvent.TextStepChosen(0))
				.text!!
				.stages[0]
				.done!!
				.out.hits.count,
			removed
				.then(BrowserEvent.TextStepRemoved(1))
				.text!!
				.rows.lines,
		)
		// The first is what the search is of: it is not taken out.
		assertEquals(
			3,
			chained
				.then(BrowserEvent.TextStepRemoved(0))
				.text!!
				.stages.size,
		)
	}

	@Test
	fun theFilesFoundAreNarrowedByAQueryOfTheirNames_AsASearchOfTheIndexIsTyped() {
		has("rg", "xargs")
		project()

		val found = exploring().searching("class").adding(null)

		fun narrowed(query: String) = found.typing(query)

		// Added and empty, it narrows nothing.
		assertEquals(
			listOf("launcher/actions.kt", "launcher/step.kt", "linux/epoll.kt"),
			found.files(),
		)
		// A word is in a file's name; `dir:` is a folder it is under; a glob is found in its name, as a word is.
		assertEquals(listOf("launcher/step.kt"), narrowed("step").files())
		assertEquals(listOf("linux/epoll.kt"), narrowed("dir:linux").files())
		assertEquals(
			listOf("launcher/actions.kt", "linux/epoll.kt"),
			narrowed("*o*.kt").files(),
		)
		assertEquals(emptyList<String>(), narrowed("ext:md").files())
		// The lines are those of the files left: it runs no command, and looks for nothing.
		assertEquals(3, narrowed("step").text!!.rows.lines)
		assertEquals("3 lines in 1 file", textStatus(narrowed("step").text!!))
		assertEquals(3, narrowed("step").typing("").files().size)
		// Asked for again with the last step that already, and empty, none is added.
		assertEquals(
			2,
			found
				.adding(null)
				.text!!
				.stages.size,
		)
	}

	@Test
	fun whatAProgramPrintedIsLookedThroughByAnother_EachLineStillOfItsFileAndAtItsPlace() {
		has("strings", "xargs")
		assumeTrue(world.installed("rg") || world.installed("grep"), "needs rg or grep")
		Files.write(
			root.resolve("tool.bin"),
			byteArrayOf(0, 1, 2) + "usage: tool [options]".toByteArray() + byteArrayOf(0, 7, 0) +
				"error: no such option".toByteArray() + byteArrayOf(0) +
				"tool version 1.2.3".toByteArray() + byteArrayOf(0),
		)

		// Every string of the binary; and then, of those lines, the ones a pattern finds.
		val strings =
			exploring().let {
				it.then(BrowserEvent.SearchedText(it.actions.search("text.strings")!!))
			}

		val versions =
			strings
				.adding("text.lines")
				.then(BrowserEvent.TextOptioned("regex"))
				.typing("[0-9]+\\.[0-9]+")

		assertEquals(3, strings.text!!.rows.lines)
		// A line kept is the line it was: of its binary, at the byte it starts at, which is no number `rg` counted.
		assertEquals(listOf("tool.bin (1)", "  49: tool version 1.2.3"), versions.lines())

		val text = versions.text!!
		val line = text.rows[text.cursor!!] as TextRow.Line

		assertEquals(
			listOf("1.2"),
			line.hit.marks.map {
				line.hit.text.substring(
					it.first,
					it.last + 1,
				)
			},
		)
		// Whose lines they are is the search that found them in files: what a line's number is, and how its file is shown.
		assertEquals(
			"text.strings" to Positions.Offsets,
			text.search.id to text.search.positions,
		)
		assertEquals("49", text.valuesOf(line.id)["offset"])
		// Turned about, it gives the lines it was given and did not keep.
		assertEquals(
			listOf("tool.bin (2)", "  3: usage: tool [options]", "  27: error: no such option"),
			versions.then(BrowserEvent.TextWithout).lines(),
		)
		// A search of lines follows lines: it is no step where none have been found, and no search to open on files.
		assertTrue(
			"Search in these lines…" in
				stepMenuOf(strings, strings.text!!).entries.mapNotNull {
					(it as? MenuEntry.Item)?.label
				},
		)
		assertTrue(
			"Search in these lines…" !in
				contextMenuOf(exploring(), Target.Folder, world = world).labels(),
		)
	}

	@Test
	fun theLinesThatLackSomethingAreFound_AsTheTodosThatNameNobody() {
		has("rg", "xargs")
		root.resolve("a.kt").writeText("// TODO(avery): one\nval a = 1\n// TODO: two\n")
		root.resolve("b.kt").writeText("// TODO(sam): three\n")

		val unowned =
			exploring()
				.searching("TODO")
				.adding("text.lines")
				.then(BrowserEvent.TextOptioned("regex"))
				.typing("TODO\\(\\w+\\)")
				.then(BrowserEvent.TextWithout)

		// Each still at its line of its file, though what was looked through was three lines of text.
		assertEquals(listOf("a.kt (1)", "  3: // TODO: two"), unowned.lines())
	}

	@Test
	fun theFilesThatLackSomethingAreFound_AsThoseWithNoLicence() {
		has("rg", "xargs")
		root.resolve("src").createDirectories()
		root.resolve("src/a.kt").writeText("// Licensed under the MIT license\nval a = 1\n")
		root.resolve("src/b.kt").writeText("val b = 2\n")
		root.resolve("src/c.kt").writeText("// licensed under Apache-2.0\n")
		root.resolve("notes.md").writeText("nothing\n")

		// Every file under what is shown, as the program that searches would look in them; then those with no licence.
		val listed =
			exploring().let {
				it.then(BrowserEvent.SearchedText(it.actions.search("text.files")!!))
			}

		val bare =
			listed
				.adding(
					"text.search",
				).typing("licensed under")
				.then(BrowserEvent.TextWithout)

		assertEquals(listOf("notes.md", "src/a.kt", "src/b.kt", "src/c.kt"), listed.files())
		// Files, and no lines of them.
		assertEquals(0, listed.text!!.rows.lines)
		assertEquals("0 lines in 4 files", textStatus(listed.text!!))
		assertEquals(listOf("notes.md", "src/b.kt"), bare.files())
		// What is typed for a list of files narrows it by where each is.
		assertEquals(
			listOf("src/a.kt", "src/b.kt", "src/c.kt"),
			listed.typing("src kt").files(),
		)
		// With no lines found anywhere, nothing can look through lines.
		assertTrue(
			"Search in these lines…" !in
				stepMenuOf(listed, listed.text!!).entries.mapNotNull {
					(it as? MenuEntry.Item)?.label
				},
		)
	}

	@Test
	fun aProgramRunOnceFeedsItselfAgain_AsTheStringsOfTheBinariesThatNameALibrary() {
		has("strings", "xargs")

		fun binary(name: String, vararg strings: String) =
			Files.write(
				root.resolve(name),
				strings.fold(byteArrayOf(0, 1)) { bytes, each ->
					bytes + each.toByteArray() +
						byteArrayOf(0, 2, 0)
				},
			)

		binary("client", "libssl.so.3", "OpenSSL 3.0.2 built", "connect failed")
		binary("packer", "libz.so.1", "zlib 1.2.13", "inflate failed")

		// The strings that name the library, which are in one binary; and then every string of that binary with "failed".
		val linked =
			exploring()
				.let {
					it.then(BrowserEvent.SearchedText(it.actions.search("text.strings")!!))
				}.typing("libssl")
		val theirs = linked.adding("text.strings").typing("failed")

		assertEquals(listOf("client"), linked.files())
		assertEquals(listOf("client (1)", "  38: connect failed"), theirs.lines())
		// The first step's command was run once, and typing for it narrows what it found; the second was given one file.
		assertEquals(listOf(1, 1), theirs.gave())
		assertEquals(
			6,
			theirs.text!!
				.stages[0]
				.done!!
				.raw.count,
		)
		assertEquals(
			3,
			theirs.text!!
				.stages[1]
				.done!!
				.raw.count,
		)
	}

	@Test
	fun theFilesFoundAreShownAsAListingOfFiles_WhereTheyAreFilesToChooseAndActOn() {
		has("rg", "xargs")
		project()

		val own =
			prism.preferences.ActionsFile.parse(
				"""{"actions": [{"id": "count", "label": "Count lines", "run": ["wc", "-l", "{paths}"]}]}""",
			)

		val actions = Actions.read(listOf(Actions.SHIPPED, own), installed = world::installed)

		val both =
			exploring(
				actions = actions,
			).searching("class").adding("text.search").typing("data class")

		val listed = both.then(BrowserEvent.TextListed)

		// Every file found, all together, each the file it is on disk: with a size, and where it is.
		assertEquals(null, listed.text)
		assertEquals(
			listOf("actions.kt", "epoll.kt", "step.kt"),
			listed.rows.list
				.map { it.name }
				.sorted(),
		)
		assertTrue(listed.rows.list.all { it.item is Item.OnDisk && it.size != null })
		assertEquals("“class” › “data class”", listed.inPlace!!.placed.title)

		// Chosen, they are given to whatever is done to files, every one.
		val menu = contextMenuOf(listed, Target.Rows(listed.rows.list), world = world)
		val count =
			menu.entries.filterIsInstance<MenuEntry.Item<BrowserEvent>>().single {
				it.id ==
					"count"
			}

		assertEquals(
			listOf("wc", "-l") +
				listed.rows.list.map { it.path.toString() },
			(step(listed, count.action).effects.single() as Effect.Launch)
				.job.invocations
				.single()
				.words,
		)
		assertTrue(menu.labels().containsAll(listOf("Copy", "Move to trash", "Properties")))
		// Read again, it is what it was: no command lists it. And Back is the search, as it was left.
		assertEquals(3, listed.then(BrowserEvent.Refreshed).rows.size)
		assertEquals(both.lines(), listed.then(BrowserEvent.Back).lines())
	}

	@Test
	fun aStepThatFailsSaysSo_AndNothingAfterItIsRun() {
		has("rg", "xargs")
		project()

		val failed =
			exploring()
				.searching("class")
				.adding("text.search")
				.then(BrowserEvent.TextOptioned("regex"))
				.typing("Forever(")
				.adding(null)
				.typing("epoll")

		assertEquals(1, failed.text!!.failed!!.first)
		assertTrue("unclosed group" in failed.text!!.failed!!.second)
		assertEquals(null, failed.text!!.stages[2].done)
		assertEquals(0, failed.text!!.rows.size)
		assertEquals("“Search in files” failed", textStatus(failed.text!!))

		// Put right, it and the step after it run.
		val mended = failed.then(BrowserEvent.TextStepChosen(1)).typing("Forever")

		assertEquals(null, mended.text!!.failed)
		assertEquals(listOf(3, 1, 1), mended.gave())
	}

	@Test
	fun whatARunOvertakenFoundIsDropped_AtWhicheverStepItWas() {
		val opened = step(texting(), BrowserEvent.TextTyped("a"))
		val first = opened.state.text!!.running!!
		val found = step(opened.state, Answer.TextFound(first, listOf(hit("x.txt", 1)))).state
		val ended = step(found, Answer.TextEnded(first, TextEnding.Done()))
		val added = step(ended.state, BrowserEvent.TextStepAdded(ended.state.text!!.first))
		val typed = step(added.state, BrowserEvent.TextTyped("b"))
		val second = (typed.effects.single { it is Effect.FindText } as Effect.FindText).run!!

		// The second step's run looks in the file the first gave, and nowhere else.
		assertEquals(listOf(root.resolve("x.txt")), second.scope.paths.toList())
		assertEquals(
			1,
			typed.state.text!!
				.under!!
				.index,
		)
		// What the first found stays listed while the second has found nothing yet.
		assertEquals(listOf("x.txt (1)", "  1: line 1"), typed.state.lines())

		// The first step typed for again: the second's run is stopped, and what it finds late is dropped.
		val again =
			step(
				step(typed.state, BrowserEvent.TextStepChosen(0)).state,
				BrowserEvent.TextTyped("ab"),
			)

		val rerun = (again.effects.last { it is Effect.FindText } as Effect.FindText).run!!

		assertEquals(1, again.effects.count { it is Effect.FindText })
		assertEquals(
			0 to "ab",
			again.state.text!!
				.under!!
				.index to rerun.query,
		)
		assertEquals(
			again.state.text!!.shown,
			step(
				again.state,
				Answer.TextFound(second, listOf(hit("y.txt", 9))),
			).state.text!!.shown,
		)

		// It ends, and the second is run again with what it now gives.
		val onward =
			step(
				step(again.state, Answer.TextFound(rerun, listOf(hit("z.txt", 3)))).state,
				Answer.TextEnded(rerun, TextEnding.Done()),
			)

		val third = (onward.effects.single { it is Effect.FindText } as Effect.FindText).run!!

		assertEquals(listOf(root.resolve("z.txt")), third.scope.paths.toList())
		assertEquals("b", third.query)
	}

	@Test
	fun whatIsLookedForInTheFileShownIsFoundThere_WhateverTheSearchLooksFor() {
		has("rg", "xargs")
		project()

		// The cursor is on the first line found in a file: here, line 11 of epoll.kt, for "AtMost".
		val found = exploring().searching("AtMost")
		val text = found.text!!

		assertEquals(
			"epoll.kt",
			text.previewed!!
				.ask.path.fileName
				.toString(),
		)

		val opened = found.then(BrowserEvent.PreviewFinding(true))
		val typed = opened.then(BrowserEvent.PreviewFindTyped("millis"))
		val places = typed.text!!.occurrences

		assertEquals(PreviewFind(), opened.text!!.find)
		// Every place, without case, by the line of the file shown it is in (counted from none) and where in it.
		assertEquals(listOf(4, 7, 11, 14, 19), places.map { it.line })
		assertEquals(
			listOf("millis"),
			places
				.map { place ->
					(typed.text!!.previewed!!.preview as Preview.Shown)
						.lines[place.line]
						.text
						.substring(place.range.first, place.range.last + 1)
				}.distinct(),
		)
		// The place gone to first is the first at or after the line the file is shown at, which is its eleventh.
		assertEquals(
			11,
			typed.text!!
				.find!!
				.current(places)!!
				.line,
		)
		// On to the next, round past the last to the first, and back.
		assertEquals(
			listOf(14, 19, 4, 7),
			(1..4).map { steps ->
				(1..steps)
					.fold(typed) { state, _ -> state.then(BrowserEvent.PreviewFindStepped(1)) }
					.text!!
					.let { it.find!!.current(it.occurrences)!!.line }
			},
		)
		assertEquals(
			7,
			typed
				.then(BrowserEvent.PreviewFindStepped(-1))
				.text!!
				.let { it.find!!.current(it.occurrences)!!.line },
		)
		// It changes nothing of what the search looks for, or found.
		assertEquals("AtMost" to 1, typed.text!!.query to typed.text!!.rows.lines)
		assertEquals(null, typed.then(BrowserEvent.PreviewFinding(false)).text!!.find)
		assertEquals(
			emptyList<Occurrence>(),
			typed.then(BrowserEvent.PreviewFindTyped("")).text!!.occurrences,
		)
	}

	// ---- what is declared --------------------------------------------------------------------------------------------

	private fun read(search: String, more: String = ""): Actions =
		Actions.read(
			listOf(prism.preferences.ActionsFile.parse("""{"textSearches": [$search]$more}""")),
		)

	@Test
	fun aSearchWrittenWrongIsSaidToBe_AndWhy() {
		fun wrong(search: String): String =
			read(search).let {
				assertEquals(emptyList<TextSearch>(), it.searches)
				it.broken.single().toString()
			}

		val start = """"id": "t", "label": "Find""""

		assertEquals("text search “t”: It needs a “run” command", wrong("{$start}"))
		assertTrue("unknown" in wrong("""{$start, "run": ["rg", "{quary}"]}""").lowercase())
		assertTrue(
			"standard input" in wrong("""{$start, "run": ["rg", "{query}", "{paths}"]}"""),
		)
		assertTrue(
			"argument on its own" in wrong("""{$start, "run": ["rg", "-{options}", "{query}"]}"""),
		)
		assertTrue("“grep”" in wrong("""{$start, "run": ["rg", "{query}"], "read": "grep"}"""))
		assertTrue(
			"{path}" in
				wrong("""{$start, "run": ["rg", "{query}"], "preview": ["cat", "{line}"]}"""),
		)
		// A place in a file is a line of it or a byte of it, and a command names it as what it is.
		assertTrue(
			"{offset}" in
				wrong(
					"""{$start, "run": ["rg", "{query}"], "preview": ["xxd", "-s", "{offset}", "{path}"]}""",
				),
		)
		assertTrue(
			"typs" in
				wrong("""{$start, "run": ["rg", "{query}"], "when": {"typs": ["text/plain"]}}"""),
		)
	}

	@Test
	fun aSearchIsOfferedInTheMenus_HasAKey_AndItsLinesTheActionsThatAreForThem() {
		has("rg", "xargs")
		project()

		val own =
			prism.preferences.ActionsFile.parse(
				"""{"actions": [
					{"id": "blame", "label": "Blame this line", "when": {"in": ["text.search"]},
					 "run": ["git", "blame", "-L", "{line},{line}", "{path}"], "how": {"terminal": true}},
					{"id": "wc", "label": "Count lines", "when": {"ext": ["kt"]}, "run": ["wc", "-l", "{path}"]}
				]}""",
			)

		val actions = Actions.read(listOf(Actions.SHIPPED, own), installed = world::installed)
		val folder = exploring(actions = actions)
		val bindings = Bindings.of(prism.preferences.Preferences(), actions = actions)
		val search = actions.search("text.search")!!

		assertEquals(emptyList<Broken>(), actions.broken)

		// In the folder's own menu, with its key; and in a row's, where it looks in that row alone.
		val space = contextMenuOf(folder, Target.Folder, bindings, world)
		val row =
			contextMenuOf(folder, Target.Rows(listOf(folder.row("launcher"))), bindings, world)

		fun Menu<BrowserEvent>.searching() =
			entries.filterIsInstance<MenuEntry.Item<BrowserEvent>>().single {
				it.id ==
					"text.search"
			}

		assertEquals("Ctrl+Shift+G", space.searching().keys)
		assertEquals(BrowserEvent.SearchedText(search, null), space.searching().action)
		assertEquals(null, row.searching().keys)
		assertEquals(
			Target.Rows(listOf(folder.row("launcher"))),
			(row.searching().action as BrowserEvent.SearchedText).target,
		)
		assertEquals(Searching(search), bindings.searching(Chord.parse("Ctrl+Shift+G")!!))
		assertTrue("Strings in files…" in space.labels())
		assertTrue("Strings in files…" !in row.labels())

		// A picker chooses for another application, and looks into nothing.
		val picker =
			step(
				opened(
					Choosing.OneFile(),
					root,
					root,
					sidebarOf(XdgPlaces(), root),
				).settled(services),
				Answer.Configured(actions),
			).state

		assertTrue(
			"Search in files…" !in contextMenuOf(picker, Target.Folder, bindings, world).labels(),
		)

		// A line found is offered what is for this search's lines, and no more of the user's; its file, what is for files.
		val found = folder.searching("data class Typed")
		val text = found.text!!
		val line = text.rows[text.cursor!!] as TextRow.Line
		val file = text.rows[TextRowId.File(line.id.path)]!!

		assertEquals(
			listOf(
				"Open in editor",
				"Blame this line",
				"Open containing folder",
				"Copy line",
				"Copy path",
			),
			textMenuOf(found, line, bindings, world).labels(),
		)
		// A file is a file wherever it is shown: what ships for one shown outside its folder is offered too.
		assertEquals(
			listOf(
				"Open in editor",
				"Open with…",
				"Open containing folder",
				"Open containing folder in new window",
				"Blame this line",
				"Count lines",
			),
			textMenuOf(found, file, bindings, world).labels().take(6),
		)

		val blame =
			textMenuOf(found, line, bindings, world)
				.entries
				.filterIsInstance<MenuEntry.Item<BrowserEvent>>()
				.single { it.id == "blame" }
				.action

		assertEquals(
			listOf("git", "blame", "-L", "6,6", root.resolve("launcher/step.kt").toString()),
			(step(found, blame).effects.single() as Effect.Launch)
				.job.invocations
				.single()
				.words,
		)
	}

	// ---- what a command prints, read ---------------------------------------------------------------------------------

	@Test
	fun linesAreReadAsGrepPrintsThem_AndWhatIsColouredInOneIsWhatMatched() {
		val esc = "\u001B"
		val plain = HitReader(HitRead.Lines, nul = false, root)
		val nul = HitReader(HitRead.Lines, nul = true, root)

		assertEquals(
			FileHit(root.resolve("src/a.kt"), Hit(12, "  val x = 1")),
			plain.read("src/a.kt:12:  val x = 1"),
		)
		// A colon in the text is the text's; an absolute path is itself.
		assertEquals(
			FileHit(
				java.nio.file.Path
					.of("/etc/hosts"),
				Hit(3, "::1 localhost"),
			),
			plain.read("/etc/hosts:3:::1 localhost"),
		)
		// As `strings -f -t d` prints.
		assertEquals(
			FileHit(root.resolve("a.out"), Hit(4096, "GLIBC_2.34")),
			plain.read("a.out:    4096 GLIBC_2.34"),
		)
		// As `grep --color=always -Z` prints: the name and the number coloured too, and only the text's colour a mark.
		assertEquals(
			FileHit(root.resolve("a:b.txt"), Hit(7, "one two one", listOf(0..2, 8..10))),
			nul.read(
				"$esc[35m$esc[Ka:b.txt$esc[m$esc[K\u0000$esc[32m$esc[K7$esc[m$esc[K" +
					"$esc[36m$esc[K:$esc[m$esc[K" +
					"$esc[01;31m$esc[Kone$esc[m$esc[K two $esc[01;31m$esc[Kone$esc[m$esc[K",
			),
		)
		// With no number at all, where a NUL says where the name ends.
		assertEquals(
			FileHit(root.resolve("a.txt"), Hit(null, "12 monkeys")),
			nul.read("a.txt\u000012 monkeys"),
		)
		assertEquals(null, plain.read("Binary file a.out matches"))
		assertEquals(null, plain.read(""))
	}

	@Test
	fun ripgrepsJsonIsRead_ItsMatchesSaidInBytesMarkedByCharacters() {
		val reader = HitReader(HitRead.RgJson, nul = false, root)

		fun match(lines: String, start: Int, end: Int, path: String = """{"text":"a.txt"}""") =
			"""{"type":"match","data":{"path":$path,"lines":$lines,"line_number":3,""" +
				""""absolute_offset":0,"submatches":[{"match":{"text":"x"},"start":$start,"end":$end}]}}"""

		// "héllo " is seven bytes and six characters.
		assertEquals(
			FileHit(root.resolve("a.txt"), Hit(3, "héllo wörld", listOf(6..10))),
			reader.read(match("""{"text":"héllo wörld\n"}""", 7, 13)),
		)
		// What is not UTF-8 is said in base 64: "caf\xe9 au lait".
		assertEquals(
			FileHit(root.resolve("a.txt"), Hit(3, "caf� au lait", listOf(5..6))),
			reader.read(match("""{"bytes":"Y2Fm6SBhdSBsYWl0"}""", 5, 7)),
		)
		assertEquals(null, reader.read("""{"type":"begin","data":{"path":{"text":"a.txt"}}}"""))
		assertEquals(null, reader.read("""{"type":"summary","data":{}}"""))
		assertEquals(null, reader.read("not json"))
	}

	@Test
	fun aScriptsOwnJsonIsRead() {
		val reader = HitReader(HitRead.Json, nul = false, root)

		assertEquals(
			FileHit(root.resolve("sub/a.srt"), Hit(125, "I'll be back", listOf(5..11))),
			reader.read(
				"""{"path": "sub/a.srt", "at": 125, "text": "I'll be back", "marks": [[5, 12]]}""",
			),
		)
		assertEquals(
			FileHit(root.resolve("a"), Hit(null, "")),
			reader.read("""{"path": "a"}"""),
		)
		assertEquals(null, reader.read("""{"text": "no file"}"""))
	}

	@Test
	fun filesAloneAreRead_AndLinesKeptAreReadByTheirNumbers() {
		val esc = "\u001B"
		val paths = HitReader(HitRead.Paths, nul = true, root)
		val lines = HitReader(HitRead.Lines, nul = false, root)
		val json = HitReader(HitRead.RgJson, nul = false, root)

		assertEquals(FileHit(root.resolve("src/a.kt"), null), paths.read("src/a.kt"))
		assertEquals(null, paths.read(" "))
		// As `grep -n --color=always` prints a line of its standard input: its number among them, and the line.
		assertEquals(
			3L to Hit(3, "a TODO here", listOf(2..5)),
			lines.kept(
				"$esc[32m$esc[K3$esc[m$esc[K$esc[36m$esc[K:$esc[m$esc[Ka $esc[01;31m$esc[KTODO$esc[m$esc[K here",
			),
		)
		assertEquals(null, lines.kept("no number"))
		// As `rg --json` says one, of a file it calls its standard input.
		assertEquals(
			7L to Hit(7, "a TODO here", listOf(2..5)),
			json.kept(
				"""{"type":"match","data":{"path":{"text":"<stdin>"},""" +
					""""lines":{"text":"a TODO here\n"},"line_number":7,"absolute_offset":0,""" +
					""""submatches":[{"match":{"text":"TODO"},"start":2,"end":6}]}}""",
			),
		)
	}

	@Test
	fun aRunGivenLinesKeepsSomeOfThem_AndRunWithoutGivesWhatItDidNotFind() {
		fun search(takes: String, read: String) =
			Actions
				.read(
					listOf(
						prism.preferences.ActionsFile.parse(
							"""{"textSearches": [{"id": "t", "label": "T", "takes": "$takes", "read": "$read",
							   "run": ["keep", "{query}"]}]}""",
						),
					),
				).search("t")!!

		val scope =
			TextScope(
				"here",
				root,
				listOf(root.resolve("a"), root.resolve("b"), root.resolve("c")),
			)

		val given =
			listOf(
				FileHit(root.resolve("a"), Hit(10, "one")),
				FileHit(root.resolve("a"), Hit(20, "two")),
				FileHit(root.resolve("b"), Hit(5, "three")),
			)

		fun ran(run: TextRun, printed: Ran): Pair<TextEnding, List<FileHit>> {
			val told = ArrayList<FileHit>()

			return found(run, { _, _ -> printed }, found = told::add) to told
		}

		val lines = search("lines", "lines")
		val kept = Ran(0, "1:one\n3:three\n9:nothing was given ninth\n", "")

		// Kept lines are the lines they were, by their numbers; a number of none given is no line.
		assertEquals(
			TextEnding.Done() to listOf(given[0], given[2]),
			ran(TextRun(lines, scope, "x", emptySet(), given), kept),
		)
		assertEquals(
			TextEnding.Done() to listOf(given[1]),
			ran(TextRun(lines, scope, "x", emptySet(), given, without = true), kept),
		)

		// Of files: those it found nothing in, of those it was given.
		val files = search("files", "lines")

		assertEquals(
			TextEnding.Done() to
				listOf(FileHit(root.resolve("a"), null), FileHit(root.resolve("c"), null)),
			ran(TextRun(files, scope, "x", emptySet(), without = true), Ran(0, "b:1:found\n", "")),
		)

		// A command that failed found nothing because it failed: that is not every file being without.
		val failed =
			ran(TextRun(files, scope, "x", emptySet(), without = true), Ran(2, "", "bad pattern"))

		assertEquals(TextEnding.Failed("bad pattern") to emptyList<FileHit>(), failed)
		// And what it is, declared, is checked: lines are not kept by a list of files.
		assertTrue(
			"paths" in
				Actions
					.read(
						listOf(
							prism.preferences.ActionsFile.parse(
								"""{"textSearches": [{"id": "t", "label": "T", "takes": "lines", "read": "paths",
								   "run": ["keep"]}]}""",
							),
						),
					).broken
					.single()
					.why,
		)
	}

	@Test
	fun aTerminalsColoursAreReadOutOfTheText() {
		val esc = "\u001B"
		val (line, left) = inked("$esc[1;34mfun$esc[0m main() $esc[38;5;208m{$esc[38;2;1;2;3m}")

		assertEquals("fun main() {}", line.text)
		assertEquals(
			listOf(
				Inked(0..2, Ink(AnsiColor.Indexed(4), bold = true)),
				Inked(11..11, Ink(AnsiColor.Indexed(208))),
				Inked(12..12, Ink(AnsiColor.Rgb(1, 2, 3))),
			),
			line.runs,
		)
		// A colour not put back is the next line's too.
		assertEquals(Ink(AnsiColor.Rgb(1, 2, 3)), left)
		assertEquals(
			listOf(
				InkedLine("a", listOf(Inked(0..0, Ink(AnsiColor.Indexed(2))))),
				InkedLine(
					"b",
					listOf(
						Inked(0..0, Ink(AnsiColor.Indexed(2))),
					),
				),
				InkedLine("c"),
			),
			inkedLines(sequenceOf("$esc[32ma", "b$esc[m", "c")).toList(),
		)
		// What moves the cursor or sets the title says nothing, and is not text.
		assertEquals(InkedLine("ab"), inked("a$esc[K$esc]0;title\u0007b").first)
	}

	@Test
	fun aLineTooLongToDrawIsShownInPart_AboutWhatWasFoundInIt() {
		val short = InkedLine("val x = 1", listOf(Inked(4..4, Ink(bold = true))))

		assertEquals(LineSight(short), short.sight(4))
		assertEquals(4..4, short.sight(4).at(4..4))

		val long =
			InkedLine(
				"a".repeat(5000) + "needle" + "b".repeat(5000),
				listOf(Inked(0..9, Ink(bold = true)), Inked(5000..5005, Ink(italic = true))),
			)

		val part = long.sight(5000, most = 100)

		// A hundred characters of it, a quarter of them before what was found, and dots at both cuts.
		assertEquals(102, part.line.text.length)
		assertTrue(part.line.text.startsWith("…a") && part.line.text.endsWith("b…"))
		assertEquals(26..31, part.at(5000..5005))
		assertEquals("needle", part.line.text.substring(26, 32))
		// What is drawn otherwise goes with it; what is out of sight is not there, here or as a place.
		assertEquals(listOf(Inked(26..31, Ink(italic = true))), part.line.runs)
		assertEquals(null, part.at(0..9))
		// What straddles a cut is as much of it as is shown.
		assertEquals(1..5, part.at(4000..4979))

		// With nothing found in it, it is shown from its start, cut only at its end.
		long.sight(null, most = 100).let {
			assertEquals("a".repeat(100) + "…", it.line.text)
			assertEquals(0..9, it.at(0..9))
		}
		// And one whose end is in sight is cut only at its start.
		assertTrue(
			long
				.sight(10_005, most = 100)
				.line.text
				.endsWith("b"),
		)
	}

	@Test
	fun aLineIsShownFromItsFirstLetter_AndALongOneFromNearWhatMatched() {
		assertEquals(
			Hit(1, "val x = 1", listOf(4..4)),
			Hit(1, "\t\tval x = 1", listOf(6..6)).excerpt(),
		)

		val long =
			Hit(
				1,
				"a".repeat(500) + "needle" + "b".repeat(500),
				listOf(500..505),
			).excerpt(100)

		assertEquals(102, long.text.length)
		assertEquals(
			"needle",
			long.text.substring(
				long.marks.single().first,
				long.marks.single().last + 1,
			),
		)
		assertTrue(long.text.startsWith("…a") && long.text.endsWith("b…"))

		val (text, at) = untabbed("\tif (x)\t{")

		assertEquals("    if (x)  {", text)
		assertEquals(listOf(0, 4, 5, 12, 13), listOf(0, 1, 2, 8, 9).map(at))
	}

	// ---- the command, run --------------------------------------------------------------------------------------------

	@Test
	fun aCommandThatFindsTooMuchIsStopped_AndOneThatFailsSaysWhy() {
		val search = texting().text!!.search
		val scope = TextScope("here", root, listOf(root.resolve("a.txt")))
		val run = TextRun(search, scope, "x", emptySet())

		fun ending(ran: Ran): Pair<TextEnding, Int> {
			var count = 0

			return found(run, { _, _ -> ran }) { count++ } to count
		}

		val many = (1..Hits.LIMIT + 50).joinToString("\n") { "a.txt:$it:x" }

		assertEquals(TextEnding.Capped to Hits.LIMIT, ending(Ran(0, many, "")))
		assertEquals(TextEnding.Done() to 2, ending(Ran(0, "a.txt:1:x\na.txt:2:x\n", "")))
		// Nothing found is no failure, whatever the status: grep's is 1, and xargs' 123.
		assertEquals(TextEnding.Done() to 0, ending(Ran(123, "", "")))
		assertInstance<TextEnding.Failed>(ending(Ran(2, "", "rg: regex parse error")).first)
		// What it found is found, whatever it could not read beside.
		assertEquals(
			TextEnding.Done("rg: b.txt: Permission denied") to 1,
			ending(Ran(2, "a.txt:1:x\n", "rg: b.txt: Permission denied\n")),
		)
	}

	private inline fun <reified T> assertInstance(value: Any?) =
		assertTrue(value is T, "$value")

	@Test
	fun theFilesAreGivenOnStandardInput_AndALongFileIsReadAboutTheLineWanted() {
		has("sh", "tr")
		root.resolve("a b.txt").writeText("x")

		val listed =
			ProcessCommands
				.stream(
					Invocation(listOf("sh", "-c", "tr '\\0' '\\n'"), root),
					{ it.write("${root.resolve("a b.txt")}\u0000/tmp/second\u0000".toByteArray()) },
					Watch.NONE,
				) {}
				.let { it.status }

		assertEquals(0, listed)

		val lines = ArrayList<String>()

		ProcessCommands.stream(
			Invocation(listOf("sh", "-c", "tr '\\0' '\\n'"), root),
			{ it.write("${root.resolve("a b.txt")}\u0000/tmp/second\u0000".toByteArray()) },
			Watch.NONE,
			lines::add,
		)
		assertEquals(listOf(root.resolve("a b.txt").toString(), "/tmp/second"), lines)

		// A file read by prism: whole, where it is short; about the line wanted, where it is not.
		val long = root.resolve("long.txt")

		long.writeText((1..Preview.LINES + 30_000).joinToString("\n") { "line $it" })

		val head = previewOf(PreviewAsk(long, null, root, 10), ProcessCommands) as Preview.Shown

		val far =
			previewOf(
				PreviewAsk(long, null, root, 40_000),
				ProcessCommands,
			) as Preview.Shown

		assertEquals(
			Triple(1L, Preview.LINES, false),
			Triple(head.first, head.lines.size, head.whole),
		)
		assertEquals("line 1", head.lines.first().text)
		assertEquals(40_000L - Preview.BEFORE, far.first)
		assertEquals("line ${far.first}", far.lines.first().text)
		assertTrue(far.covers(40_000) && !head.covers(40_000))
		assertEquals(
			Preview.Unshown("This file isn't text."),
			previewOf(
				PreviewAsk(
					root.resolve("b.bin").also {
						Files.write(it, byteArrayOf(1, 0, 2))
					},
					null,
					root,
					null,
				),
				ProcessCommands,
			),
		)
	}

	@Test
	fun theSearchIsDrawn() {
		has("rg", "xargs")
		project()
		// A file with more in it than fits, so that the file shown is seen scrolled to the line found.
		root.resolve("linux/files.kt").writeText(
			(1..120).joinToString("\n") { "// line $it of the file" } +
				"\n\tclass Far(val away: Int) // " + "and on ".repeat(40) + "\n" +
				(1..40).joinToString("\n") { "// after $it" } + "\n",
		)

		val folder = exploring()

		val opened =
			folder.then(
				BrowserEvent.SearchedText(folder.actions.search("text.search")!!),
			)

		val found = opened.then(BrowserEvent.TextTyped("class"))
		val bindings = Bindings.of(prism.preferences.Preferences(), actions = folder.actions)
		val text = found.text!!
		val far = TextRowId.Line(root.resolve("linux/files.kt"), 0)
		val scrolled =
			found
				.then(BrowserEvent.TextFolded(root.resolve("launcher/actions.kt")))
				.then(BrowserEvent.TextPointed(far))

		kept("text-light", renderedPng(found, 1280, 760, Palette.Light, scale = 2f))
		kept("text-dark", renderedPng(found, 1280, 760, Palette.Dark, scale = 2f))
		kept("text-opened", renderedPng(opened, 1280, 500, Palette.Light, scale = 2f))
		kept("text-scrolled", renderedPng(scrolled, 1280, 600, Palette.Dark, scale = 2f))
		kept("text-narrow", renderedPng(scrolled, 760, 600, Palette.Light, scale = 2f))
		kept(
			"text-filtered",
			renderedPng(
				found.adding(null).typing("dir:linux *.kt"),
				1280,
				600,
				Palette.Light,
				scale = 2f,
			),
		)
		kept(
			"text-as-files",
			renderedPng(
				found.adding("text.search").typing("data class").then(BrowserEvent.TextListed),
				1100,
				420,
				Palette.Light,
				scale = 2f,
			),
		)
		kept(
			"text-steps",
			renderedPng(
				found
					.adding("text.search")
					.typing("Forever")
					.then(BrowserEvent.TextWithout)
					.adding(null)
					.typing("dir:launcher"),
				1280,
				600,
				Palette.Dark,
				scale = 2f,
			),
		)
		kept(
			"text-find",
			renderedPng(
				found
					.then(BrowserEvent.TextPointed(TextRowId.Line(root.resolve("launcher/step.kt"), 0)))
					.then(BrowserEvent.PreviewFinding(true))
					.then(BrowserEvent.PreviewFindTyped("event")),
				1280,
				760,
				Palette.Dark,
				scale = 2f,
			),
		)
		kept(
			"text-nothing",
			renderedPng(
				opened.then(BrowserEvent.TextTyped("no such thing")),
				1280,
				500,
				Palette.Light,
				scale = 2f,
			),
		)
		kept(
			"text-failed",
			renderedPng(
				opened
					.then(BrowserEvent.TextOptioned("regex"))
					.then(BrowserEvent.TextTyped("class(")),
				1280,
				500,
				Palette.Dark,
				scale = 2f,
			),
		)
		kept(
			"text-menu-line",
			renderedPng(1280, 600, Palette.Light, scale = 2f) {
				FileBrowser(found)
				MenuPopup(
					textMenuOf(found, text.rows[text.cursor!!]!!, bindings, world),
					MenuAnchor.At(
						androidx.compose.ui.unit
							.IntOffset(300, 150),
					),
					{},
					{},
				)
			},
		)
		kept(
			"text-menu-file",
			renderedPng(1280, 760, Palette.Dark, scale = 2f) {
				FileBrowser(found)
				MenuPopup(
					textMenuOf(found, text.rows[TextRowId.File(text.cursor!!.path)]!!, bindings, world),
					MenuAnchor.At(
						androidx.compose.ui.unit
							.IntOffset(300, 110),
					),
					{},
					{},
				)
			},
		)
		kept(
			"text-menu-folder",
			renderedPng(1000, 640, Palette.Light, scale = 2f) {
				FileBrowser(folder)
				MenuPopup(
					contextMenuOf(folder, Target.Folder, bindings, world),
					MenuAnchor.At(
						androidx.compose.ui.unit
							.IntOffset(420, 150),
					),
					{},
					{},
				)
			},
		)
	}

	@Test
	fun theStringsOfBinariesAreDrawn() {
		has("strings", "xargs")
		Files.copy(Path.of("/bin/true"), root.resolve("true"))
		Files.copy(Path.of("/bin/sleep"), root.resolve("sleep"))

		val folder = exploring()

		val found =
			folder.then(
				BrowserEvent.SearchedText(folder.actions.search("text.strings")!!),
			)

		val narrowed = found.then(BrowserEvent.TextTyped("version"))

		if (world.installed("rg") || world.installed("grep"))
			kept(
				"strings-searched",
				renderedPng(
					found
						.adding("text.lines")
						.then(BrowserEvent.TextOptioned("regex"))
						.typing("GLIBC_[0-9.]+"),
					1280,
					600,
					Palette.Light,
					scale = 2f,
				),
			)

		assertTrue(found.text!!.rows.lines > 20)
		kept("strings-light", renderedPng(found, 1280, 700, Palette.Light, scale = 2f))
		kept("strings-narrowed", renderedPng(narrowed, 1280, 700, Palette.Dark, scale = 2f))
	}
}
