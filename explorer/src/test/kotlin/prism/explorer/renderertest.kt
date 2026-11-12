package prism.explorer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.BrowserTheme
import prism.browser.Palette
import prism.browser.Tone
import prism.browser.renderedPng
import prism.preferences.Preferences
import prism.window.Access
import prism.window.DeviceNode
import prism.window.DevicesFound
import prism.window.Drawing
import prism.window.Graphics
import prism.window.GraphicsDevice
import prism.window.GraphicsDevices
import prism.window.GraphicsReport
import prism.window.KernelDriver
import prism.window.LinuxLibraries
import prism.window.Loading
import prism.window.OpenGl
import prism.window.Rendering
import prism.window.SearchPath
import prism.window.Vendor
import prism.window.Video

@OptIn(ExperimentalTestApi::class)
class RendererTest {
	@Test
	fun whyTheGpuIsNotUsedIsSaidFirst_WithWhatToDo() {
		val explained = explained(missingLibrary())

		assertEquals("Drawn on the CPU", explained.title)
		assertEquals(Tone.Bad, explained.tone)
		assertTrue(
			explained.why.startsWith("The NVIDIA GeForce RTX 4070 isn't used."),
			explained.why,
		)
		assertTrue(
			explained.why.endsWith("The system's loader says: “$NOT_FOUND”"),
			explained.why,
		)
		assertTrue(
			explained.fix.orEmpty().contains("add that folder to LD_LIBRARY_PATH"),
			"${explained.fix}",
		)
		assertEquals(Tone.Good, explained(drawing()).tone)
		assertEquals(
			"OpenGL draws prism's windows on the NVIDIA GeForce RTX 4070.",
			explained(drawing()).why,
		)
	}

	@Test
	fun theReportSaysWhatThePageSays_LineByLine() {
		val text = reportText(missingLibrary())

		assertTrue(
			text.startsWith(
				"prism's windows: Drawn on the CPU\nThe NVIDIA GeForce RTX 4070 isn't used.",
			),
			text,
		)
		for (line in listOf(
			"OpenGL: llvmpipe (LLVM 20.1.8, 256 bits), by Mesa",
			"Display: Wayland, through EGL",
			"Loader: libEGL.so.1, from /usr/lib/libEGL.so.1.1.0",
			"Search path: LD_LIBRARY_PATH isn't set",
			"NVIDIA GeForce RTX 4070: Its OpenGL library can't be loaded",
			"Where: PCI 0000:01:00.0",
			"Driver: nvidia 550.78",
			"Nodes: /dev/dri/card1, /dev/dri/renderD128, /dev/nvidiactl, /dev/nvidia0",
			"OpenGL library: libEGL_nvidia.so.0 can't be loaded: $NOT_FOUND",
		))
			assertTrue(text.lines().contains(line), "no “$line” in:\n$text")
	}

	@Test
	fun theRendererPageShowsEachDeviceAndCopiesTheReport() =
		runComposeUiTest {
			var copied: String? = null
			val report = missingLibrary()

			setContent {
				BrowserTheme {
					SettingsView(
						Settings(Preferences.DEFAULT, page = Page.Renderer),
						{},
						onApply = {},
						onClose = {},
						graphics = report,
						onCopy = { copied = it },
					)
				}
			}

			onNodeWithText("Drawn on the CPU").assertExists()
			onNodeWithTag("device:0000:01:00.0").assertExists()
			// Why is said once, at the top; the device's card says what it is, and the loader's words in its facts.
			onNodeWithText("Its OpenGL library can't be loaded.").assertExists()
			assertEquals(
				2,
				onAllNodesWithText(
					"cannot open shared object file",
					substring = true,
				).fetchSemanticsNodes().size,
			)
			onNodeWithTag("copy-report").performClick()
			waitForIdle()
			assertEquals(reportText(report), copied)
			onNodeWithText("Copied").assertExists()
		}

	@Test
	fun whileTheDevicesAreLookedAtThePageSaysSo() =
		runComposeUiTest {
			setContent {
				BrowserTheme {
					SettingsView(Settings(Preferences.DEFAULT, page = Page.Renderer), {
					}, onApply = {}, onClose = {})
				}
			}

			onNodeWithText("Looking at the graphics devices…").assertExists()
		}

	/**
	 * The page drawn headless, in both palettes, for an NVIDIA GPU whose OpenGL library is missing and for one that
	 * draws: with `-Pexplorer.screenshots=DIR`, kept there.
	 */
	@Test
	fun thePageIsDrawn() {
		for ((name, report) in listOf("missing" to missingLibrary(), "drawing" to drawing()))
			for ((tone, palette) in listOf("light" to Palette.Light, "dark" to Palette.Dark)) {
				val png =
					renderedPng(960, 980, palette, scale = 2f) {
						SettingsView(
							Settings(Preferences.DEFAULT, page = Page.Renderer),
							{},
							onApply = {},
							onClose = {},
							graphics = report,
						)
					}

				assertTrue(png.size > 20_000, "$name: ${png.size} bytes")
				System.getProperty("explorer.screenshots")?.takeIf { it.isNotBlank() }?.let { dir ->
					Path
						.of(dir)
						.createDirectories()
						.resolve("renderer-$name-$tone.png")
						.writeBytes(png)
				}
			}
	}

	/** A desktop's NVIDIA GPU, its driver loaded, whose OpenGL library the loader cannot find: OpenGL runs on the CPU. */
	private fun missingLibrary(): GraphicsReport =
		examined(
			OpenGl.Running(LLVMPIPE, "Mesa", "4.5 (Core Profile) Mesa 25.0.7"),
			mapOf("libEGL_nvidia.so.0" to Loading.Fails(NOT_FOUND)),
		)

	/** The same GPU, drawing. */
	private fun drawing(): GraphicsReport =
		examined(
			OpenGl.Running(
				"NVIDIA GeForce RTX 4070/PCIe/SSE2",
				"NVIDIA Corporation",
				"4.6.0 NVIDIA 550.78",
			),
			mapOf(
				"libEGL_nvidia.so.0" to Loading.Loads(Path.of("/usr/lib/libEGL_nvidia.so.550.78")),
			),
		)

	private fun examined(opengl: OpenGl, loads: Map<String, Loading>): GraphicsReport =
		Graphics.examine(
			Rendering(Video.Wayland, Drawing.Auto, opengl),
			GraphicsDevices { DevicesFound.Found(listOf(NVIDIA)) },
			{ name -> loads[name] ?: Loading.Loads(Path.of("/usr/lib/libEGL.so.1.1.0")) },
			LinuxLibraries,
			SearchPath("LD_LIBRARY_PATH", null),
		)

	private companion object {
		const val LLVMPIPE = "llvmpipe (LLVM 20.1.8, 256 bits)"
		const val NOT_FOUND =
			"libEGL_nvidia.so.0: cannot open shared object file: No such file or directory"

		val NVIDIA =
			GraphicsDevice(
				"NVIDIA GeForce RTX 4070",
				Vendor(0x10de, "NVIDIA"),
				"0000:01:00.0",
				KernelDriver("nvidia", "550.78"),
				listOf(
					"/dev/dri/card1",
					"/dev/dri/renderD128",
					"/dev/nvidiactl",
					"/dev/nvidia0",
				).map {
					DeviceNode(Path.of(it), Access.Open)
				},
			)
	}
}
