package prism.explorer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.BrowserTheme
import prism.browser.Commands
import prism.browser.Palette
import prism.browser.Ran
import prism.browser.Thing
import prism.browser.World
import prism.browser.renderedPng
import prism.preferences.ActionSpec
import prism.preferences.ActionsFile
import prism.preferences.OpenSpec
import prism.preferences.Preferences
import prism.preferences.Spec
import prism.preferences.TextSearchSpec
import prism.preferences.WhenSpec

/**
 * The pages of actions and text searches, and the windows that make, change and move one, as they are used: by the
 * pointer and the keyboard, with what each press leaves the preferences as; and drawn, in both palettes.
 */
@OptIn(ExperimentalTestApi::class)
class ActionsPageTest {
	private val home = Path.of("/home/someone")

	/** A machine with some of what the actions that ship need, and not the rest. */
	private fun world(vararg programs: String): World =
		object : World {
			override val home = this@ActionsPageTest.home

			override fun lineage(thing: Thing) = emptyList<String>()

			override fun installed(program: String) = program in programs

			override fun exists(path: Path) = Files.exists(path)
		}

	private val most =
		world(
			"sh",
			"tar",
			"bsdtar",
			"unzip",
			"zip",
			"zipinfo",
			"git",
			"gzip",
			"xz",
			"rg",
			"grep",
			"strings",
			"xargs",
		)

	/** Someone with actions of their own, one of prism's changed, one turned off, and one written wrong. */
	private val mine =
		ActionsFile
			.parse(
				"""{"actions": [
					{"id": "mine.png", "label": "To PNG", "submenu": "Image/Convert", "key": "Ctrl+Shift+P",
					 "when": {"ext": ["jpg", "jpeg", "webp"], "kind": "file"},
					 "run": ["magick", "{path}", "{stem}.png"], "how": {"watched": true}, "then": {"reread": true}},
					{"id": "mine.mail", "label": "Mail it…", "when": {"count": "some", "real": true},
					 "ask": {"to": {"text": "Send to"}}, "run": ["mutt", "-s", "Files", "-a", "{paths}", "--", "{to}"],
					 "how": {"terminal": true}},
					{"id": "compress.zip", "label": "As a .zip (fast)…", "submenu": "Compress",
					 "when": {"count": "some", "real": true, "in": ["folder"]},
					 "ask": {"to": {"text": "Archive name", "start": "{stem}"}},
					 "run": ["zip", "-1", "-r", "{to}.zip", "{names}"], "how": {"watched": true}},
					{"id": "mine.wrong", "label": "Broken", "run": ["echo", "{nothing}"]}
				]}""",
			).let {
				Preferences(actions = it.actions, disabled = setOf("git.changes", "decompress.Z"))
			}

	/** Someone with a great many actions of their own: five hundred, over many extensions, windows and submenus. */
	private val many: Preferences by lazy {
		val extensions =
			listOf("png", "jpg", "mp4", "mkv", "pdf", "md", "kt", "rs", "py", "flac")
		val within = listOf(null, "folder", "results", "archive.zip", "git.changes")

		val verbs =
			listOf(
				"Convert",
				"Resize",
				"Upload",
				"Lint",
				"Format",
				"Sign",
				"Publish",
				"Tag",
				"Scan",
				"Print",
			)

		Preferences(
			actions =
				ActionsFile
					.parse(
						(0 until 500).joinToString(",", """{"actions": [""", "]}") { at ->
							val ext = extensions[at % extensions.size]
							val where = within[(at / 7) % within.size]?.let { """, "in": ["$it"]""" }.orEmpty()

							"""{"id": "many.$at", "label": "${verbs[(at / 10) % verbs.size]} $ext ${at / 100 + 1}",
							 "submenu": "${verbs[(at / 10) % verbs.size]}",
							 "when": {"ext": ["$ext"]$where}, "run": ["tool$at", "{path}"]}"""
						},
					).actions,
		)
	}

	/** The field tagged [tag], typed into: what was in it replaced by [text]. */
	private fun ComposeUiTest.type(tag: String, text: String) {
		onNode(hasSetTextAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))))
			.performTextReplacement(text)
		waitForIdle()
	}

	private fun ComposeUiTest.click(tag: String) {
		onNodeWithTag(tag).performClick()
		waitForIdle()
	}

	/** The window over [from], each change kept: what the tests then read. */
	private class Edited(
		from: Settings,
	) {
		var settings by mutableStateOf(from)

		val actions: List<ActionSpec> get() =
			settings.written.written
				.actionSpecs()
				.map { (it as Spec.Read).value }

		val searches: List<TextSearchSpec> get() =
			settings.written.written
				.textSearchSpecs()
				.map { (it as Spec.Read).value }
	}

	private fun ComposeUiTest.shown(
		edited: Edited,
		world: World = most,
		commands: Commands = Commands { _, _ -> Ran(0, "", "") },
	) = setContent {
		// Drawn small, so that a window's whole form is in sight, and under the pointer, without scrolling to it.
		CompositionLocalProvider(LocalDensity provides Density(0.5f)) {
			BrowserTheme {
				SettingsView(
					edited.settings,
					{ edited.settings = it },
					onApply = {},
					onClose = {},
					world = world,
					commands = commands,
				)
			}
		}
	}

	/** Whether something tagged [tag] is drawn, wherever in a row it is. */
	private fun ComposeUiTest.drawn(tag: String): Boolean =
		onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

	/** What the text tagged [tag] says, wherever in a row it is. */
	private fun ComposeUiTest.said(tag: String): String =
		onNodeWithTag(tag, useUnmergedTree = true)
			.fetchSemanticsNode()
			.config
			.let { config ->
				val text = androidx.compose.ui.semantics.SemanticsProperties.Text

				if (text in config)
					config[text].joinToString("") { it.text }
				else
					""
			}

	@Test
	fun anActionIsTurnedOffByItsSwitch_AndTheListIsNarrowedByWordsAFileAMarkAndFolded() =
		runComposeUiTest {
			val edited = Edited(Settings(Preferences.DEFAULT, page = Page.Actions))

			shown(edited)
			onNodeWithText("Apply").assertIsNotEnabled()
			onNodeWithTag("scripts-count").assertTextEquals("60 actions, 49 available")

			// What cannot be run here says what it wants, and is still listed.
			type("scripts-filter", "archive.rar.remove")
			assertEquals("Unavailable: requires rar", said("script-note:archive.rar.remove"))
			onNodeWithTag("scripts-count").assertTextEquals("1 of 60 actions shown")

			// Ctrl+F, from wherever the keyboard is, goes to the filter; a word of a name leaves the rest out.
			click("page:Display")
			click("page:File actions")
			onNode(isFocused()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
			waitForIdle()
			onNode(isFocused()).performTextReplacement("containing")
			waitForIdle()
			onNodeWithTag("script:containing-folder").assertExists()
			onNodeWithTag("script:archive.zip.open").assertDoesNotExist()

			// Its switch turns it off, which is all that is written of it.
			click("script-on:containing-folder")
			assertEquals(setOf("containing-folder"), edited.settings.written.disabled)
			assertTrue(edited.actions.isEmpty())
			onNodeWithText("Apply").assertIsEnabled()

			// Those off, by their chip; and all of them again.
			click("scripts-clear")
			click("mark:Off")
			onNodeWithTag("script:containing-folder").assertExists()
			onNodeWithTag("script:containing-folder-window").assertDoesNotExist()
			click("mark:Off")

			// For a file of a name: what is for it, in one window; and a window's entries folded under its heading.
			type("scripts-named", "notes.gz")
			onNodeWithTag("script:decompress.gz").assertExists()
			onNodeWithTag("script:decompress.xz").assertDoesNotExist()
			click("scene:Anywhere")
			onNodeWithTag("script:decompress.gz").assertDoesNotExist()
			onNodeWithTag("scene:Folders").assertExists()
			assertEquals(setOf<Scene>(Scene.Anywhere), edited.settings.actionsShown.folded)

			// None of which is anything to apply but the switch.
			assertEquals(
				Preferences(disabled = setOf("containing-folder")),
				edited.settings.written,
			)
		}

	@Test
	fun anActionIsMadeInItsWindow_WhichSaysWhatItWillRunAndWhereAsItIsTyped() =
		runComposeUiTest {
			val edited = Edited(Settings(Preferences.DEFAULT, page = Page.Actions))

			shown(edited)
			click("script-new")
			onNodeWithTag("action-editor").assertExists()
			// Nothing typed, it cannot be added, and says only that it is not finished.
			onNodeWithTag("editor-save").assertIsNotEnabled()
			onNodeWithTag("editor-test").assertIsNotEnabled()
			onNodeWithTag("editor-unfinished").assertExists()
			onNodeWithTag("editor-wrong").assertDoesNotExist()

			// A label says what it means when the pointer rests on it: a submenu is a word of prism's, not everyone's.
			onNodeWithText("Places the item in a submenu", substring = true).assertDoesNotExist()
			onNodeWithText("Submenu").performMouseInput { moveTo(center) }
			mainClock.advanceTimeBy(1_000)
			waitForIdle()
			onNodeWithText("Places the item in a submenu", substring = true).assertExists()
			onNodeWithText("Name").performMouseInput { moveTo(center) }
			waitForIdle()

			// Named, its id follows; a command typed wrong is said to be, and why.
			type("form-label", "To PNG")
			assertEquals(
				"to-png",
				(edited.settings.modal as ActingModal.EditingAction).form.id,
			)
			type("command", "magick {paht} {stem}.png")
			onNodeWithTag("editor-wrong").assertExists()
			onNodeWithText("Unknown placeholder “{paht}”", substring = true).assertExists()
			onNodeWithTag("editor-save").assertIsNotEnabled()

			// What a command can name is in a menu at its corner, and is put after the rest of it; and so is a
			// question, whose answer it then names.
			click("command-insert")
			click("menu:{parent}   Containing folder")
			click("command-insert")
			click("menu:Prompt for text…")
			(edited.settings.modal as ActingModal.EditingAction).form.let {
				assertEquals(
					listOf("magick", "{paht}", "{stem}.png", "{parent}", "{value}"),
					it.ways
						.single()
						.command.words,
				)
				assertEquals(listOf(AskForm("value", false, "Enter a value")), it.asks)
			}
			onNodeWithTag("ask-prompt:0").assertExists()
			click("ask-remove:0")

			// Its other menu sets its arguments apart, each in a box of its own, and back.
			click("command-more")
			click("menu:Edit as separate arguments")
			onNodeWithTag("command:1").assertExists()
			click("command-more")
			click("menu:Edit as command line")

			// Typed right, what it would run for an example is shown, and that its program is not here.
			type("command", "magick {path} {stem}.png")
			onNodeWithTag(
				"example",
			).assertTextEquals("magick /home/someone/example.txt example.png")
			onNodeWithTag("editor-unfound").assertExists()

			// When it is offered is another part of the form, and is said over the conditions as they are set.
			click("tab:Conditions")
			onNodeWithTag("command").assertDoesNotExist()
			type("when-ext", "jpg, webp")
			onNodeWithText("For any items ending in .jpg, .webp.").assertExists()
			click("when-count:One")
			onNodeWithText("For one item ending in .jpg, .webp.").assertExists()

			// For some extensions, the example is a file of the first.
			click("tab:Action")
			onNodeWithTag(
				"example",
			).assertTextEquals("magick /home/someone/example.jpg example.png")

			// Watched, with the listing read again after; then added.
			click("tab:Run options")
			click("running:Monitored")
			click("then-reread")
			click("editor-save")
			assertNull(edited.settings.modal)
			assertEquals(
				listOf(
					ActionSpec(
						"to-png",
						"To PNG",
						WhenSpec(ext = listOf("jpg", "webp"), count = "one"),
						run = listOf("magick", "{path}", "{stem}.png"),
						how = prism.preferences.HowSpec(watched = true),
						then = prism.preferences.ThenSpec(reread = true),
					),
				),
				edited.actions,
			)
			// It is in the list, marked as the user's.
			type("scripts-filter", "to-png")
			assertTrue(drawn("own:to-png"))

			// Cancelled, a window changes nothing.
			click("script-edit:to-png")
			type("form-label", "Other")
			click("editor-cancel")
			assertEquals("To PNG", edited.actions.single().label)
		}

	@Test
	fun oneOfPrismsIsChangedAndPutBack_AndOneOfTheUsersCopiedMovedAndDeleted() =
		runComposeUiTest {
			val edited = Edited(Settings(mine, page = Page.Actions))

			shown(edited)

			// One of prism's, changed by the user, says so; its menu puts it back.
			type("scripts-filter", "compress.zip")
			assertTrue(drawn("changed:compress.zip"))
			click("script-more:compress.zip")
			click("menu:Reset to default")
			assertFalse(drawn("changed:compress.zip"))
			assertFalse(edited.actions.any { it.id == "compress.zip" })

			// Changed again, in its window: its id is its own, and cannot be typed over.
			click("script-edit:compress.zip")
			type("form-label", "Zip…")
			click("editor-save")
			assertEquals("Zip…", edited.actions.single { it.id == "compress.zip" }.label)
			assertTrue(drawn("changed:compress.zip"))

			// A copy opens to be changed before it is added, under an id of its own.
			type("scripts-filter", "mine.png")
			click("script-copy:mine.png")
			assertEquals(
				"mine.png-copy",
				(edited.settings.modal as ActingModal.EditingAction).form.id,
			)
			type("form-label", "To WebP")
			type("command", "magick {path} {stem}.webp")
			click("editor-save")
			assertEquals(
				listOf("magick", "{path}", "{stem}.webp"),
				edited.actions.single { it.id == "mine.png-copy" }.run,
			)

			// Moved to other extensions and another window; and copied to yet others.
			click("script-more:mine.png")
			click("menu:Move or copy to…")
			onNodeWithTag("move-apply").assertIsNotEnabled()
			type("move-ext", "tiff, bmp")
			click("scene-add")
			click("option:Search results")
			onNodeWithTag("move-said").assertExists()
			onNodeWithText("In the menu for selected items, in search results.").assertExists()
			click("move-apply")
			edited.actions.single { it.id == "mine.png" }.conditions.let {
				assertEquals(listOf("tiff", "bmp"), it.ext)
				assertEquals(listOf("results"), it.within)
				assertEquals("file", it.kind)
			}
			click("script-more:mine.png")
			click("menu:Move or copy to…")
			type("move-ext", "heic")
			click("move-copy")
			assertEquals(
				listOf("heic"),
				edited.actions
					.single { it.id == "mine.png-copy-2" }
					.conditions.ext,
			)

			// One of the user's is deleted from its menu; one written wrong is listed with why, to be put right or deleted.
			type("scripts-filter", "mine.wrong")
			assertTrue("{nothing}" in said("script-note:mine.wrong"))
			click("script-more:mine.wrong")
			click("menu:Delete")
			assertFalse(edited.actions.any { it.id == "mine.wrong" })
		}

	@Test
	fun anActionIsTriedFromItsWindow_AndWhatItPrintedIsShown() =
		runComposeUiTest {
			val ran = ArrayList<List<String>>()

			val commands =
				Commands { invocation, _ ->
					ran += invocation.words
					Ran(2, "half done\n", "magick: no decode delegate\n")
				}

			val edited = Edited(Settings(mine, page = Page.Actions))
			val file = Files.createTempFile("prism-trial", ".jpg")

			try {
				shown(edited, commands = commands)
				type("scripts-filter", "mine.png")
				click("script-edit:mine.png")

				// Tried in a window of its own, over the form. With nothing to try it on, it says what it wants.
				onNodeWithTag("trial").assertDoesNotExist()
				click("editor-test")
				onNodeWithTag("trial").assertExists()
				click("trial-run")
				waitUntil { onAllNodesWithTag("tried-refused").fetchSemanticsNodes().isNotEmpty() }
				assertTrue(ran.isEmpty())

				type("trial-given", file.toString())
				click("trial-run")
				waitUntil { onAllNodesWithTag("tried-status:0").fetchSemanticsNodes().isNotEmpty() }
				assertEquals(
					listOf(
						listOf(
							"magick",
							file.toString(),
							file.fileName.toString().removeSuffix(".jpg") + ".png",
						),
					),
					ran,
				)
				onNodeWithTag("tried-status:0").assertTextEquals("Failed (exit status 2)")
				onNodeWithTag("tried-output:0").assertTextEquals("half done")
				onNodeWithTag("tried-errors:0").assertTextEquals("magick: no decode delegate")
				// Trying it changed nothing that would be applied; closed, the form is there as it was, with what was
				// typed to try it on kept for the next time.
				assertEquals(mine, edited.settings.written)
				click("trial-close")
				onNodeWithTag("trial").assertDoesNotExist()
				onNodeWithTag("action-editor").assertExists()
				assertEquals(
					file.toString(),
					(edited.settings.modal as ActingModal.EditingAction).trial.given,
				)
			} finally {
				Files.deleteIfExists(file)
			}
		}

	@Test
	fun aSearchIsMadeAndTried_AndTheLinesItPrintedThatCouldNotBeReadAreShown() =
		runComposeUiTest {
			val commands =
				Commands { _, _ ->
					Ran(0, "warming up\nnotes.txt:3:the word is here\nnotes.txt:oops\n", "")
				}

			val edited = Edited(Settings(Preferences.DEFAULT, page = Page.Searches))
			val folder = Files.createTempDirectory("prism-trial")

			try {
				shown(edited, commands = commands)
				onNodeWithTag("script:text.search").assertExists()
				click("script-new")
				onNodeWithTag("search-editor").assertExists()
				type("form-label", "Find words")
				type("command", "xargs -0 grep -Hn -e {query} --")
				onNodeWithTag("example").assertTextEquals("xargs -0 grep -Hn -e ‹query› --")
				// What it must print is said, with a line of it, and follows what is chosen.
				click("tab:Output")
				onNodeWithTag("prints-example").assertTextEquals("src/main.kt:12:val answer = 42")
				click("read:JSON")
				onNodeWithTag("prints-example").assertTextContains("\"marks\"", substring = true)
				click("read:Lines")

				click("editor-test")
				type("trial-given", folder.toString())
				type("trial-query", "word")
				click("trial-run")
				waitUntil { onAllNodesWithTag("tried-found").fetchSemanticsNodes().isNotEmpty() }
				onNodeWithTag("tried-found").assertTextEquals("Found 1 line in 1 file.")
				onNodeWithTag("tried-hits").assertTextEquals("notes.txt:3:the word is here")
				onNodeWithTag("tried-unread").assertTextEquals("warming up\nnotes.txt:oops")

				click("trial-close")
				click("editor-save")
				assertEquals(
					listOf(
						TextSearchSpec(
							"find-words",
							"Find words",
							run = listOf("xargs", "-0", "grep", "-Hn", "-e", "{query}", "--"),
						),
					),
					edited.searches,
				)

				// The editor a line found is opened in, typed under the list.
				type("editor", "zed {path}:{line}")
				click("editor-terminal")
				assertEquals(
					OpenSpec(listOf("zed", "{path}:{line}"), terminal = true),
					edited.settings.written.editor,
				)
				// One that names no file is said to be wrong.
				type("editor", "zed")
				onNodeWithTag("editor-wrong").assertExists()
			} finally {
				folder.toFile().deleteRecursively()
			}
		}

	private fun shot(name: String, png: ByteArray) {
		assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
		System.getProperty("explorer.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
			Path
				.of(dir)
				.createDirectories()
				.resolve("$name.png")
				.writeBytes(png)
		}
	}

	/** Both pages and each window over them, drawn headless in both palettes. With `-Pexplorer.screenshots=DIR`, kept. */
	@Test
	fun thePagesAndTheirWindowsAreDrawn() {
		val base = Settings(mine, page = Page.Actions)
		val catalog = ScriptKind.Action.catalog(base.acting) { true }
		val searches = Settings(mine, page = Page.Searches)

		val views =
			listOf(
				"actions" to base,
				"actions-narrowed" to
					base.copy(actionsShown = Narrowing(scene = Scene.In("folder"), named = "zip")),
				"actions-edit" to
					base.copy(
						modal = ActingModal.editing(catalog["mine.png"]!!.spec),
					),
				"actions-edit-runs" to
					base.copy(
						modal =
							ActingModal.EditingAction(
								ActionForm.of(catalog["mine.png"]!!.spec),
								tab = ActionTab.Runs,
							),
					),
				"actions-edit-offered" to
					base.copy(
						modal =
							ActingModal.EditingAction(
								ActionForm.of(catalog["mine.png"]!!.spec),
								tab = ActionTab.Offered,
							),
					),
				"actions-edit-asks" to
					base.copy(modal = ActingModal.editing(catalog["mine.mail"]!!.spec)),
				"searches-edit-prints" to
					searches.copy(
						modal =
							ActingModal.EditingSearch(
								SearchForm.of(ScriptKind.Search.shipped.getValue("text.search")),
								tab = SearchTab.Prints,
							),
					),
				"searches-edit-shows" to
					searches.copy(
						modal =
							ActingModal.EditingSearch(
								SearchForm.of(ScriptKind.Search.shipped.getValue("text.search")),
								tab = SearchTab.Shows,
							),
					),
				"actions-edit-script" to
					base.copy(modal = ActingModal.editing(catalog["archive.zip.unpack"]!!.spec)),
				"actions-new" to base.copy(modal = ActingModal.EditingAction(ActionForm())),
				"actions-edit-words" to
					base.copy(modal = ActingModal.editing(catalog["archive.zip.remove"]!!.spec)),
				"actions-edit-like" to
					base.copy(modal = ActingModal.editing(catalog["archive.zip.unpack-folder"]!!.spec)),
				"actions-tried" to
					base.copy(
						modal =
							ActingModal.EditingAction(
								ActionForm.of(catalog["mine.png"]!!.spec),
								trying = true,
								trial =
									Trial(
										"~/Pictures/holiday.jpg",
										result =
											Tried.Ran(
												listOf(
													RanOnce(
														listOf("magick", "/home/someone/Pictures/holiday.jpg", "holiday.png"),
														1,
														"",
														"magick: no decode delegate for this image format `JPG' @ error/constitute.c\n",
													),
												),
											),
									),
							),
					),
				"actions-move" to
					base.copy(
						modal =
							ActingModal.Moving(
								ScriptKind.Action,
								"mine.png",
								Retarget(listOf("results", "folder"), "jpg, jpeg, webp, avif"),
							),
					),
				"actions-many" to Settings(many, page = Page.Actions),
				"actions-many-narrowed" to
					Settings(many, page = Page.Actions).copy(
						actionsShown =
							Narrowing("lint", Scene.In("results"), "kt", folded = setOf(Scene.Anywhere)),
					),
				"searches" to searches,
				"searches-edit" to
					searches.copy(
						modal = ActingModal.editing(ScriptKind.Search.shipped.getValue("text.search")),
					),
				"searches-tried" to
					searches.copy(
						modal =
							ActingModal.EditingSearch(
								SearchForm.of(
									ScriptKind.Search.shipped
										.getValue("text.search")
										.copy(read = "lines"),
								),
								trying = true,
								trial =
									Trial(
										"~/Projects/prism",
										query = "answer",
										result =
											Tried.Found(
												listOf("xargs", "-0", "-r", "rg", "--json", "-e", "answer", "--"),
												prism.browser.TextEnding.Done(),
												2,
												1,
												listOf(
													prism.browser.FileHit(
														Path.of("main.kt"),
														prism.browser.Hit(12, "val answer = 42"),
													),
													prism.browser.FileHit(
														Path.of("main.kt"),
														prism.browser.Hit(40, "println(answer)"),
													),
												),
												listOf(
													"{\"type\":\"begin\",\"data\":{\"path\":{\"text\":\"main.kt\"}}}",
													"{\"type\":\"summary\",\"data\":{\"elapsed_total\":{\"secs\":0}}}",
												),
											),
									),
							),
					),
			)

		for ((name, settings) in views)
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark))
				shot(
					"$name-$tone",
					renderedPng(1180, 860, palette, scale = 2f) {
						SettingsView(settings, {}, onApply = {}, onClose = {}, world = most)
					},
				)
	}
}
