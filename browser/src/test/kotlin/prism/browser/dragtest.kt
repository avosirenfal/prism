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

	/** The archive's window, the folder's beside it, and the pointer, for [test]; then the windows closed. */
	private fun windowed(test: suspend (Robot, Session, Session, Path) -> Unit) {
		assumeTrue(
			System.getProperty("browser.display") != null && !GraphicsEnvironment.isHeadless(),
		)
		// Before anything asks: a drop the display did not deliver is made good here too, as under XWayland.
		System.setProperty("prism.strayDrop", "true")

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
					EventQueue.invokeAndWait {
						windows += window(archive, 0, 500)
						windows += window(folder, 520, 760)
					}

					val robot = Robot().apply { autoDelay = 40 }

					robot.waitForIdle()
					robot.delay(1500)
					test(robot, archive, folder, out)
				}
			}
		} finally {
			EventQueue.invokeAndWait { windows.forEach { it.dispose() } }
			scope.cancel()
		}
	}

	/** The archive's one row, `src`, pressed and dragged across to the empty listing of the folder's window. */
	private fun Robot.dragAcross() {
		mouseMove(90, ROW)
		mousePress(InputEvent.BUTTON1_DOWN_MASK)
		// In steps, as a hand moves.
		for (step in 1..30)
			mouseMove(90 + step * 30, ROW + step * 6)
		mouseMove(1000, 300)
		delay(300)
		mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
	}

	@Test
	fun anEntryDraggedFromAnArchivesWindowToAFoldersIsExtractedThere() =
		windowed { robot, _, folder, out ->
			robot.dragAcross()
			folder.state.first { state -> state.rows.list.any { it.name == "src" } }
			assertEquals("int main;", out.resolve("src/main.c").readText())
			// Extracted, and not moved: the archive is as it was.
			assertFalse(out.resolve("code.tar").exists())
		}

	/**
	 * The same, with nothing of the drag kept in this process: the window dropped on reads what the drag itself
	 * carries, written out and read back by the toolkit, as a window of another prism would.
	 */
	@Test
	fun theDragItselfSaysWhatIsDraggedToAWindowThatWasNotTold() {
		CarriedDrag.kept = false
		try {
			windowed { robot, _, folder, out ->
				robot.dragAcross()
				folder.state.first { state -> state.rows.list.any { it.name == "src" } }
				assertEquals("int main;", out.resolve("src/main.c").readText())
			}
		} finally {
			CarriedDrag.kept = true
		}
	}

	@Test
	fun aClickWithAnUnsteadyHandAndTheOtherButtonsDragNothing() =
		windowed { robot, archive, _, out ->
			// Pressed, moved a few pixels, and let go: a click, which chooses the row.
			robot.mouseMove(90, ROW)
			robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
			robot.mouseMove(93, ROW + 2)
			robot.mouseMove(94, ROW + 3)
			robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
			archive.state.first { it.choosing.rows.size == 1 }

			// The right button, held and moved all the way across, drags nothing: it opens the row's menu.
			robot.mouseMove(90, ROW)
			robot.mousePress(InputEvent.BUTTON3_DOWN_MASK)
			for (step in 1..30)
				robot.mouseMove(90 + step * 30, ROW + step * 6)
			robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)
			robot.delay(800)

			assertFalse(out.resolve("src").exists())
			assertEquals(emptyList<Work>(), archive.state.value.running)
		}

	/**
	 * A drop the display never delivers, as under XWayland on a compositor that does not say where windows are: here,
	 * the button let go where no window is. The pointer then coming into the folder's window is where it was dropped.
	 */
	@Test
	fun aDropTheDisplayDidNotDeliverIsMadeWhereThePointerThenComesIn() =
		windowed { robot, _, folder, out ->
			robot.mouseMove(90, ROW)
			robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
			for (step in 1..12)
				robot.mouseMove(90 + step * 20, ROW + step * 34)
			// Below both windows, where there is nothing to drop on.
			robot.mouseMove(600, HEIGHT + 90)
			robot.delay(200)
			robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
			robot.mouseMove(1000, 300)
			robot.mouseMove(1004, 304)

			folder.state.first { state -> state.rows.list.any { it.name == "src" } }
			assertEquals("int main;", out.resolve("src/main.c").readText())
		}

	private companion object {
		const val HEIGHT = 420

		/** Where the first row of a listing is, down a window: under the toolbar and the columns' headings. */
		const val ROW = 104
	}
}
