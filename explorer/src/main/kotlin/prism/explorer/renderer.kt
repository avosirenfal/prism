package prism.explorer

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay
import prism.browser.Card
import prism.browser.Emphasis
import prism.browser.LocalType
import prism.browser.Notice
import prism.browser.PushButton
import prism.browser.Section
import prism.browser.Tone
import prism.window.Access
import prism.window.DevicesFound
import prism.window.Drawing
import prism.window.Drawn
import prism.window.Examined
import prism.window.GraphicsReport
import prism.window.Library
import prism.window.Loading
import prism.window.OpenGl
import prism.window.Summary
import prism.window.Verdict
import prism.window.Video
import prism.browser.Page as PageLayout

/*
 * The Renderer page: what draws prism's windows, the GPU or the CPU, and why; then each graphics device the computer
 * has, and why it draws them or does not, with what to do about it where something can be done. What it says is worked
 * out from the report by plain functions ([explained], [factsOf]), which the report copied from it ([reportText]) says
 * in the same words.
 */

/** What a report says of something: in brief ([title]), how it reads ([tone]), [why], and what would change it ([fix]). */
data class Explained(
	val title: String,
	val tone: Tone,
	val why: String,
	val fix: String? = null,
)

/** The Renderer page, of [report] (being made, while null), which [onCopy] copies as text. */
@Composable
internal fun RendererPage(report: GraphicsReport?, onCopy: (String) -> Unit) {
	PageLayout(
		"Renderer",
		"How prism's windows are rendered, and the graphics devices on this computer.",
	) {
		if (report == null) {
			Section("Rendering") {
				Card {
					Notice("Detecting", Tone.Plain, detail = "Detecting graphics devices…")
				}
			}
		} else {
			Section(
				"Rendering",
				description =
					said(
						"Prism renders on the GPU when OpenGL is available on one.",
						"Set PRISM_DRAWING=gpu or cpu before starting prism to override this.",
					),
				trailing = { CopyButton { onCopy(reportText(report)) } },
			) {
				Card(Modifier.testTag("drawing")) {
					Explanation(explained(report))
					factsOf(report).forEach { FactRow(it) }
				}
			}
			Section("Graphics devices") {
				when (val devices = report.devices) {
					is DevicesFound.Unknown ->
						Card { Notice("Not detected", Tone.Plain, detail = devices.why) }
					is DevicesFound.Found ->
						if (devices.devices.isEmpty())
							Card {
								Notice(
									"None found",
									Tone.Plain,
									detail = "The system reports no graphics devices.",
								)
							}
						else
							report.examined.forEach { examined ->
								Card(Modifier.testTag("device:${examined.device.slot}")) {
									Explanation(
										explained(examined, report),
										examined.device.name,
										brief = examined == report.subject,
									)
									factsOf(examined).forEach { FactRow(it) }
								}
							}
				}
			}
		}
	}
}

/**
 * [explained], as a notice: titled [title] where it is a device's, with what to do under why; or, [brief], only what
 * it is, where the page has said why already.
 */
@Composable
private fun Explanation(
	explained: Explained,
	title: String? = null,
	brief: Boolean = false,
) {
	val type = LocalType.current

	Notice(
		title ?: explained.title,
		explained.tone,
		detail = detailOf(explained, title, brief),
	) {
		if (!brief)
			explained.fix?.let { Text(it, style = type.body, modifier = Modifier.testTag("fix")) }
	}
}

/** What a notice of [explained] says under its title: as [Explanation] has it. */
private fun detailOf(explained: Explained, title: String?, brief: Boolean): String =
	when {
		title == null -> explained.why
		brief -> "${explained.title}."
		else -> "${explained.title}. ${explained.why}"
	}

/**
 * The device what [GraphicsReport.summary] says is about: the one that draws, or the one held back. Its card says only
 * what it is, since the summary has said why.
 */
private val GraphicsReport.subject: Examined?
	get() =
		when (val summary = summary) {
			is Summary.OnGpu -> examined.firstOrNull { it.device == summary.device }
			is Summary.HeldBack -> summary.examined
			else -> null
		}

/** Copies the report; says it has, for a moment after. */
@Composable
private fun CopyButton(onCopy: () -> Unit) {
	var copied by remember { mutableStateOf(false) }

	LaunchedEffect(copied) {
		if (copied) {
			delay(2_000)
			copied = false
		}
	}
	PushButton(
		if (copied) "Copied" else "Copy report",
		Emphasis.Secondary,
		true,
		Modifier.testTag("copy-report"),
	) {
		onCopy()
		copied = true
	}
}

/** Why prism's windows are drawn as [report] says, and what would change it. */
fun explained(report: GraphicsReport): Explained {
	val onCpu = "Rendering on the CPU"
	val running = report.rendering.openGl as? OpenGl.Running

	return when (val summary = report.summary) {
		is Summary.OnGpu ->
			Explained(
				"Rendering on the GPU",
				Tone.Good,
				"OpenGL renders prism's windows on the " +
					(summary.device?.name ?: summary.renderer) + ".",
			)
		Summary.CpuAsked ->
			Explained(
				"$onCpu, as requested",
				Tone.Plain,
				"PRISM_DRAWING=cpu (or -Dprism.window.drawing=cpu) is set.",
				"Unset it and restart prism to render on the GPU.",
			)
		is Summary.NoLoader ->
			Explained(
				onCpu,
				Tone.Bad,
				said(
					"The OpenGL library ${summary.library} can't be loaded,",
					"so no GPU can be used.",
					"The system loader reports: “${summary.why}”",
				),
				said(
					"Install your system's OpenGL libraries (libglvnd). If they're",
					"installed in a folder the system doesn't search, add that folder",
					"to ${report.searchPath.variable}. Then restart prism.",
				),
			)
		is Summary.HeldBack -> {
			val device = explained(summary.examined, report)

			Explained(
				onCpu,
				Tone.Bad,
				said("The ${summary.examined.device.name} isn't in use.", device.why),
				device.fix,
			)
		}
		is Summary.NoOpenGl ->
			Explained(
				onCpu,
				Tone.Bad,
				"OpenGL couldn't start: “${summary.why}”",
			)
		is Summary.FellBack ->
			Explained(
				onCpu,
				Tone.Bad,
				said(
					"OpenGL is available on the GPU (${summary.renderer}) but couldn't render this",
					"window, so the CPU is used instead. Reason: “${summary.why}”",
				),
				FELL_BACK_FIX,
			)
		is Summary.Software ->
			Explained(
				onCpu,
				Tone.Bad,
				said(
					if (report.rendering.asked == Drawing.Gpu)
						"OpenGL is used because PRISM_DRAWING=gpu is set, but it runs"
					else
						"OpenGL runs",
					"on the CPU here (${summary.renderer}),",
					"which is slower than prism's own CPU renderer.",
					softwareCause(report.devices),
				),
			)
	}
}

/** Why OpenGL may run on the CPU, as far as [devices] say: none, or none it would start. */
private fun softwareCause(devices: DevicesFound): String? =
	when (devices) {
		is DevicesFound.Unknown -> null
		is DevicesFound.Found ->
			if (devices.devices.isEmpty())
				"No graphics devices were found."
			else
				said(
					"Every graphics device has its driver and libraries,",
					"but OpenGL didn't start on any of them.",
				)
	}

/** Why the device [examined] does or does not draw prism's windows, in [report], and what would change it. */
fun explained(examined: Examined, report: GraphicsReport): Explained {
	val driver = examined.device.driver?.let(::driverText)
	val running = report.rendering.openGl as? OpenGl.Running
	val search = report.searchPath.variable

	return when (val verdict = examined.verdict) {
		Verdict.Drawing ->
			Explained(
				"In use",
				Tone.Good,
				"OpenGL renders with it (${running?.renderer}).",
			)
		Verdict.CpuAsked ->
			Explained(
				"Not used",
				Tone.Plain,
				"CPU rendering was requested, so OpenGL wasn't started.",
			)
		Verdict.NoDriver ->
			Explained(
				"No driver",
				Tone.Bad,
				"No kernel driver is bound to this device, so it can't be used.",
				said(
					"Install its driver, or load it if it's already installed.",
					"You may need to restart afterwards.",
				),
			)
		is Verdict.SetAside ->
			Explained(
				"Reserved",
				Tone.Plain,
				said(
					"Its driver, ${verdict.driver}, reserves the device,",
					"usually for a virtual machine.",
				),
			)
		is Verdict.NoAccess -> {
			val group = (verdict.node.access as? Access.Denied)?.group

			Explained(
				"Permission denied",
				Tone.Bad,
				said(
					"You don't have permission to open ${verdict.node.path}.",
					group?.let { "It belongs to the $it group." },
				),
				group?.let { "Add yourself to the $it group, then sign out and back in." }
					?: "Ask your administrator for access.",
			)
		}
		is Verdict.NoLibrary ->
			Explained(
				"OpenGL library can't be loaded",
				Tone.Bad,
				said(
					"The driver $driver is loaded, but its OpenGL library",
					"${verdict.library} can't be.",
					"The system loader reports: “${verdict.why}”",
				),
				said(
					"Install the driver's OpenGL libraries, in the same version as the driver.",
					"If they're installed in a folder the system doesn't search,",
					"add that folder to $search. Then restart prism.",
				),
			)
		is Verdict.Mismatched ->
			Explained(
				"Driver and library versions don't match",
				Tone.Bad,
				said(
					"The kernel driver is version ${verdict.driver},",
					"but ${verdict.library} is version ${verdict.version}.",
					"They must be the same version.",
				),
				said(
					"If the driver was just updated, restart the computer.",
					"Otherwise, install version ${verdict.driver} of its libraries.",
				),
			)
		is Verdict.Refused ->
			Explained(
				"Couldn't render this window",
				Tone.Bad,
				said(
					"OpenGL is available on it but couldn't render this window,",
					"so the CPU is used instead. Reason: “${verdict.why}”",
				),
				FELL_BACK_FIX,
			)
		is Verdict.NoOpenGl ->
			Explained(
				"OpenGL couldn't start",
				Tone.Bad,
				said(
					"Its driver, device nodes and library are all present,",
					"but OpenGL couldn't start: “${verdict.why}”",
				),
			)
		is Verdict.Passed ->
			Explained(
				"Not selected",
				Tone.Plain,
				said(
					"Its driver, device nodes and library are all present, but OpenGL",
					if (running?.software == true)
						"started on the CPU instead (${verdict.renderer})."
					else
						"selected another device: ${verdict.renderer}.",
				),
			)
	}
}

/** [parts] said one after another: a sentence's pieces, or its sentences, leaving out those that are not there. */
private fun said(vararg parts: String?): String = parts.filterNotNull().joinToString(" ")

/** What [report] says of how prism's windows are drawn, line by line. */
fun factsOf(report: GraphicsReport): List<Line> {
	val rendering = report.rendering

	return listOfNotNull(
		when (val opengl = rendering.openGl) {
			OpenGl.NotAsked -> Line("OpenGL", "Not started (CPU rendering requested)")
			is OpenGl.Unavailable ->
				Line(
					"OpenGL",
					"Couldn't start: ${opengl.why}",
					wrong = true,
				)
			is OpenGl.Running ->
				Line(
					"OpenGL",
					"${opengl.renderer}, by ${opengl.vendor}",
					wrong = opengl.software,
				)
		},
		(rendering.openGl as? OpenGl.Running)?.let { Line("Version", it.version) },
		Line("Display", "${videoName(rendering.video)}, using ${rendering.api}"),
		when (val drawn = report.drawn) {
			Drawn.ByOpenGl -> Line("This window", "Rendered by OpenGL")
			Drawn.OnCpu -> Line("This window", "Rendered on the CPU")
			is Drawn.FellBack ->
				Line(
					"This window",
					"Rendered on the CPU because OpenGL failed: ${drawn.why}",
					wrong = true,
				)
		},
		report.loader?.let {
			Line("Loader", libraryText(it), wrong = it.loading is Loading.Fails)
		},
		Line(
			"Search path",
			report.searchPath.let { path ->
				path.value?.let { "${path.variable}=$it" } ?: "${path.variable} isn't set"
			},
		),
		Line(
			"Requested",
			when (rendering.asked) {
				Drawing.Auto -> "Automatic (GPU when available)"
				Drawing.Gpu -> "GPU"
				Drawing.Cpu -> "CPU"
			},
		),
	)
}

/** What [examined] is, and what it has of what drawing on it takes, line by line. */
fun factsOf(examined: Examined): List<Line> {
	val device = examined.device
	val verdict = examined.verdict

	return listOf(
		Line(
			"Location",
			if (PCI.matches(device.slot))
				"PCI ${device.slot}"
			else
				device.slot,
		),
		Line(
			"Driver",
			device.driver?.let(::driverText) ?: "None",
			wrong = device.driver == null,
		),
	) +
		listOfNotNull(
			device.nodes.takeIf { it.isNotEmpty() }?.let { nodes ->
				Line(
					"Device nodes",
					nodes.joinToString(", ") { node ->
						node.path.toString() +
							if (node.access is Access.Denied)
								" (permission denied)"
							else
								""
					},
					wrong = verdict is Verdict.NoAccess,
				)
			},
		) +
		examined.libraries.map {
			Line("OpenGL library", libraryText(it), wrong = it.loading is Loading.Fails)
		}
}

/** [report] as text, to paste where someone can help: what the page says, in the same words, line by line. */
fun reportText(report: GraphicsReport): String {
	fun block(
		explained: Explained,
		title: String,
		facts: List<Line>,
		brief: Boolean = false,
	): String =
		(
			listOf("$title: ${explained.title}") +
				(
					if (brief)
						emptyList()
					else
						listOfNotNull(explained.why, explained.fix)
				) +
				facts.map { "${it.label}: ${it.value}" }
		).joinToString("\n")

	val devices =
		when (val found = report.devices) {
			is DevicesFound.Unknown -> found.why
			is DevicesFound.Found ->
				if (found.devices.isEmpty())
					"None found."
				else
					report.examined.joinToString("\n\n") {
						block(explained(it, report), it.device.name, factsOf(it), it == report.subject)
					}
		}

	return block(explained(report), "prism's windows", factsOf(report)) +
		"\n\nGraphics devices\n\n" +
		devices +
		"\n"
}

private fun driverText(driver: prism.window.KernelDriver): String =
	listOfNotNull(driver.name, driver.version).joinToString(" ")

private fun libraryText(library: Library): String =
	when (val loading = library.loading) {
		is Loading.Loads ->
			listOfNotNull(
				library.name,
				library.version?.let { "version $it" },
				loading.from?.let { "from $it" },
			).joinToString(", ")
		is Loading.Fails -> "${library.name} can't be loaded: ${loading.why}"
	}

private fun videoName(video: Video): String =
	when (video) {
		Video.Wayland -> "Wayland"
		Video.X11 -> "X11"
		Video.Windows -> "Windows"
		Video.Cocoa -> "macOS"
		Video.Other -> "Other"
	}

/** What to do where OpenGL runs, but couldn't draw a window: nothing the user can mend, but much that can be told. */
private val FELL_BACK_FIX =
	said(
		"This is likely a bug in prism or in the driver. Please report it with this report and",
		"the output of PRISM_WINDOW_LOG=1. Set PRISM_DRAWING=cpu to skip OpenGL.",
	)

/** A PCI address, as Linux writes one: domain, bus, device and function. */
private val PCI = Regex("[0-9a-f]{4}:[0-9a-f]{2}:[0-9a-f]{2}\\.[0-9a-f]")
