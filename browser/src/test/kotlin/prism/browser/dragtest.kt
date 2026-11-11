package prism.browser

import androidx.compose.ui.awt.ComposeWindow
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.XdgPlaces

/**
 * A drag between two real windows, made by the pointer: an entry of an archive pressed in the archive's window,
 * dragged to a folder's window and let go there, as a person does it, through AWT's own drag and drop and the display
 * server's. Nothing headless can say whether that works, so this runs only where it is asked for and there is a
 * display: `xvfb-run ./gradlew :browser:test --tests prism.browser.DragTest -Pbrowser.display`.
 */
class DragTest {
	@TempDir
	lateinit var root: Path

	/** A window of [width] by [HEIGHT] at [x], showing [session]. */
	private fun window(session: Session, x: Int, width: Int): ComposeWindow =
		ComposeWindow().apply {
			isUndecorated = true
			setBounds(x, 0, width, HEIGHT)
			setContent {
				BrowserTheme { SessionBrowser(session) }
			}
			isVisible = true
		}

	@Test
	fun anEntryDraggedFromAnArchivesWindowToAFoldersIsExtractedThere() = dragged()

	/**
	 * The same, with nothing of the drag kept in this process: the window dropped on reads what the drag itself
	 * carries, written out and read back by the toolkit, as a window of another prism would.
	 */
	@Test
	fun theDragItselfSaysWhatIsDraggedToAWindowThatWasNotTold() {
		CarriedDrag.kept = false
		try {
			dragged()
		} finally {
			CarriedDrag.kept = true
		}
	}

	private fun dragged() {
		assumeTrue(
			System.getProperty("browser.display") != null && !GraphicsEnvironment.isHeadless(),
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
		val windows = ArrayList<ComposeWindow>()

		try {
			runBlocking {
				withTimeout(30_000) {
					archive.state.first { it.listing is Listing.Read && it.actions.actions.isNotEmpty() }
					folder.state.first { it.listing is Listing.Read && it.actions.actions.isNotEmpty() }
					with(Dispatchers.Swing) {
						EventQueue.invokeAndWait {
							windows += window(archive, 0, 500)
							windows += window(folder, 520, 760)
						}
					}

					val robot = Robot().apply { autoDelay = 40 }

					robot.waitForIdle()
					robot.delay(1500)

					// The archive's one row, `src`: the first of its listing, which has no sidebar beside it.
					robot.mouseMove(90, ROW)
					robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
					// Across to the empty listing of the folder's window, in steps, as a hand moves.
					for (step in 1..30)
						robot.mouseMove(90 + step * 30, ROW + step * 6)
					robot.mouseMove(1000, 300)
					robot.delay(300)
					robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)

					folder.state.first { state -> state.rows.list.any { it.name == "src" } }
				}
			}

			assertEquals("int main;", out.resolve("src/main.c").readText())
			// Extracted, and not moved: the archive is as it was.
			assertFalse(out.resolve("code.tar").exists())
		} finally {
			EventQueue.invokeAndWait { windows.forEach { it.dispose() } }
			scope.cancel()
		}
	}

	private companion object {
		const val HEIGHT = 420

		/** Where the first row of a listing is, down a window: under the toolbar and the columns' headings. */
		const val ROW = 104
	}
}
