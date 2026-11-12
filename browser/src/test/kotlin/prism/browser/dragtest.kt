package prism.browser

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.XdgPlaces
import prism.window.Video
import prism.window.Window
import prism.window.application

/**
 * A drag between two real windows, made by the pointer: an entry of an archive pressed in the archive's window,
 * dragged to a folder's window and let go there, as a person does it, through the display server. The pointer is X's
 * own, moved by `xdotool`, so this runs only where it is asked for, on an X display: `xvfb-run ./gradlew :browser:test
 * --tests prism.browser.DragTest -Pbrowser.display`.
 */
class DragTest {
	@TempDir
	lateinit var root: Path

	private fun xdotool(vararg words: String) {
		assertEquals(0, ProcessBuilder(listOf("xdotool") + words).inheritIO().start().waitFor())
	}

	/** The archive's window, the folder's beside it, and the pointer, for [test]; then the windows closed. */
	private fun windowed(test: suspend (Session, Session, Path) -> Unit) {
		assumeTrue(
			System.getProperty("browser.display") != null && System.getenv("DISPLAY") != null,
		)

		val out = root.resolve("out").createDirectories()

		root.resolve("src").createDirectories()
		root.resolve("src/main.c").writeText("int main;")
		assertEquals(
			0,
			ProcessBuilder(
				"tar",
				"-cf",
				"code.tar",
				"src",
			).directory(root.toFile()).start().waitFor(),
		)

		val actions = Actions.read(listOf(Actions.SHIPPED))
		val services = Services(JvmDisk(), home = root)
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val sidebar = sidebarOf(XdgPlaces(), root, null)

		val archive =
			Session(
				openedIn(
					Placed(
						actions.places.getValue("archive.tar"),
						mapOf("archive" to root.resolve("code.tar").toString()),
					),
					root,
					sidebar,
				),
				services,
				scope,
			)

		val folder = Session(opened(Choosing.Exploring(), out, root, sidebar), services, scope)
		var failed: Throwable? = null

		try {
			runBlocking {
				withTimeout(30_000) {
					archive.state.first { it.listing is Listing.Read && it.actions.actions.isNotEmpty() }
					folder.state.first { it.listing is Listing.Read && it.actions.actions.isNotEmpty() }
				}
			}
			application("prism-test") {
				Window({
				}, "archive", DpSize(500.dp, HEIGHT.dp), decorated = false, at = IntOffset(0, 0)) {
					BrowserTheme { SessionBrowser(archive) }
				}
				Window({
				}, "folder", DpSize(760.dp, HEIGHT.dp), decorated = false, at = IntOffset(520, 0)) {
					BrowserTheme { SessionBrowser(folder) }
					assumeTrue(window.video == Video.X11)
					LaunchedEffect(Unit) {
						try {
							delay(1500)
							System.getProperty("browser.screenshots")?.let { dir ->
								withContext(Dispatchers.IO) {
									ProcessBuilder(
										"import",
										"-window",
										"root",
										"$dir/drag-windows.png",
									).start().waitFor()
								}
							}
							withTimeout(30_000) { test(archive, folder, out) }
						} catch (wrong: Throwable) {
							failed = wrong
						}
						exitApplication()
					}
				}
			}
		} finally {
			scope.cancel()
		}
		failed?.let { throw it }
	}

	/** The archive's one row, `src`, pressed and dragged across to the empty listing of the folder's window. */
	private suspend fun dragAcross() =
		withContext(Dispatchers.IO) {
			xdotool("mousemove", "90", "$ROW", "mousedown", "1")
			// In steps, as a hand moves.
			for (step in 1..10)
				xdotool("mousemove", "${90 + step * 90}", "${ROW + step * 18}")
			xdotool("mousemove", "1000", "300", "sleep", "0.3", "mouseup", "1")
		}

	@Test
	fun anEntryDraggedFromAnArchivesWindowToAFoldersIsExtractedThere() =
		windowed { _, folder, out ->
			dragAcross()
			folder.state.first { state -> state.rows.list.any { it.name == "src" } }
			assertEquals("int main;", out.resolve("src/main.c").readText())
			// Extracted, and not moved: the archive is as it was.
			assertFalse(out.resolve("code.tar").exists())
		}

	@Test
	fun aClickWithAnUnsteadyHandAndTheOtherButtonsDragNothing() =
		windowed { archive, _, out ->
			withContext(Dispatchers.IO) {
				// Pressed, moved a few pixels, and let go: a click, which chooses the row.
				xdotool("mousemove", "90", "$ROW", "mousedown", "1", "mousemove", "93", "${ROW + 2}")
				xdotool("mousemove", "94", "${ROW + 3}", "mouseup", "1")
			}
			archive.state.first { it.choosing.rows.size == 1 }

			withContext(Dispatchers.IO) {
				// The right button, held and moved all the way across, drags nothing: it opens the row's menu.
				xdotool("mousemove", "90", "$ROW", "mousedown", "3")
				for (step in 1..10)
					xdotool("mousemove", "${90 + step * 90}", "${ROW + step * 18}")
				xdotool("mouseup", "3")
			}
			delay(800)

			assertFalse(out.resolve("src").exists())
			assertEquals(emptyList<Work>(), archive.state.value.running)
		}

	private companion object {
		const val HEIGHT = 420

		/** Where the first row of a listing is, down a window: under the toolbar and the columns' headings. */
		const val ROW = 104
	}
}
