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
			found.lines().let { lines ->
				// Files come in the order the program found them, which is not said: by name, here.
				val files = lines.indices.filter { !lines[it].startsWith("  ") } + lines.size

				files
					.zipWithNext { from, to -> lines.subList(from, to) }
					.sortedBy { it.first() }
					.flatten()
			},
		)
	}

	@Test
	fun theSearchIsDrawn() {
		has("rg", "xargs")
		project()

		val found = exploring().searching("class")

		kept("text-light", renderedPng(found, 1280, 760, Palette.Light, scale = 2f))
		kept("text-dark", renderedPng(found, 1280, 760, Palette.Dark, scale = 2f))
	}
}
