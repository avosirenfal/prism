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
		"What draws prism's windows, the graphics devices this computer has, and why each is used or not.",
	) {
		if (report == null) {
			Section("Drawing") {
				Card {
					Notice("Looking", Tone.Plain, detail = "Looking at the graphics devices…")
				}
			}
		} else {
			Section(
				"Drawing",
				description =
					said(
						"Prism draws on the GPU where OpenGL runs on one.",
						"PRISM_DRAWING=gpu or cpu, set before it starts, chooses instead.",
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
						Card { Notice("Not looked for", Tone.Plain, detail = devices.why) }
					is DevicesFound.Found ->
						if (devices.devices.isEmpty())
							Card {
								Notice(
									"None found",
									Tone.Plain,
									detail = "The system lists no graphics device.",
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
	val onCpu = "Drawn on the CPU"
	val running = report.rendering.openGl as? OpenGl.Running

	return when (val summary = report.summary) {
		is Summary.OnGpu ->
			Explained(
				"Drawn on the GPU",
				Tone.Good,
				"OpenGL draws prism's windows on the " +
					(summary.device?.name ?: summary.renderer) + ".",
			)
		Summary.CpuAsked ->
			Explained(
				"$onCpu, as asked",
				Tone.Plain,
				said(
					"PRISM_DRAWING=cpu (or -Dprism.window.drawing=cpu)",
					"asks prism to draw on the CPU.",
				),
				"Unset it and start prism again to draw on the GPU where one can.",
			)
		is Summary.NoLoader ->
			Explained(
				onCpu,
				Tone.Bad,
				said(
					"OpenGL's own library, ${summary.library}, can't be loaded,",
					"so no GPU can be drawn with.",
					"The system's loader says: “${summary.why}”",
				),
				said(
					"Install your system's OpenGL libraries (libglvnd), or, if they're",
					"installed in a folder the system doesn't search, add that folder",
					"to ${report.searchPath.variable}. Then start prism again.",
				),
			)
		is Summary.HeldBack -> {
			val device = explained(summary.examined, report)

			Explained(
				onCpu,
				Tone.Bad,
				said("The ${summary.examined.device.name} isn't used.", device.why),
				device.fix,
			)
		}
		is Summary.NoOpenGl ->
			Explained(
				onCpu,
				Tone.Bad,
				"OpenGL couldn't be started. It says: “${summary.why}”",
			)
		is Summary.Software ->
			Explained(
				onCpu,
				Tone.Bad,
				said(
					if (report.rendering.asked == Drawing.Gpu)
						"OpenGL draws them, as PRISM_DRAWING=gpu asks, but it runs"
					else
						"OpenGL runs",
					"on the CPU here, as ${summary.renderer},",
					"which is slower than prism's own drawing on the CPU.",
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
				"No graphics device was found."
			else
				said(
					"Each graphics device found has its driver and libraries,",
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
				"Draws prism's windows",
				Tone.Good,
				"OpenGL draws with it, as ${running?.renderer}.",
			)
		Verdict.CpuAsked ->
			Explained(
				"Not asked",
				Tone.Plain,
				"The CPU was asked for, so OpenGL wasn't started.",
			)
		Verdict.NoDriver ->
			Explained(
				"No driver",
				Tone.Bad,
				"The kernel has no driver bound to it, so nothing can draw with it.",
				said(
					"Install its driver, or load it if it's installed.",
					"A restart may be needed after installing one.",
				),
			)
		is Verdict.SetAside ->
			Explained(
				"Set aside",
				Tone.Plain,
				said(
					"Its driver, ${verdict.driver}, sets it aside,",
					"usually for a virtual machine to use.",
				),
			)
		is Verdict.NoAccess -> {
			val group = (verdict.node.access as? Access.Denied)?.group

			Explained(
				"Can't be opened",
				Tone.Bad,
				said(
					"Drawing with it opens ${verdict.node.path},",
					"which you aren't allowed to open.",
					group?.let { "It belongs to the $it group." },
				),
				group?.let { "Add yourself to the $it group, then sign out and back in." }
					?: "Ask whoever runs this computer for access to it.",
			)
		}
		is Verdict.NoLibrary ->
			Explained(
				"Its OpenGL library can't be loaded",
				Tone.Bad,
				said(
					"Its driver, $driver, is loaded, but ${verdict.library},",
					"through which OpenGL reaches that driver, can't be.",
					"The system's loader says: “${verdict.why}”",
				),
				said(
					"Install the OpenGL part of its driver, of the driver's version.",
					"If it's installed in a folder the system doesn't search,",
					"add that folder to $search. Then start prism again.",
				),
			)
		is Verdict.Mismatched ->
			Explained(
				"Its driver and libraries don't match",
				Tone.Bad,
				said(
					"The kernel's driver is version ${verdict.driver},",
					"but ${verdict.library} is version ${verdict.version}.",
					"They only work together at the same version.",
				),
				said(
					"If the driver was just updated, restart the computer.",
					"Otherwise, install its libraries of version ${verdict.driver}.",
				),
			)
		is Verdict.NoOpenGl ->
			Explained(
				"OpenGL couldn't start",
				Tone.Bad,
				said(
					"Its driver, nodes and library are all here,",
					"but OpenGL couldn't be started. It says: “${verdict.why}”",
				),
			)
		is Verdict.Passed ->
			Explained(
				"Not chosen",
				Tone.Plain,
				said(
					"Its driver, nodes and library are all here, but OpenGL",
					if (running?.software == true)
						"started on the CPU instead, as ${verdict.renderer}."
					else
						"chose another device: ${verdict.renderer}.",
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
			OpenGl.NotAsked -> Line("OpenGL", "Not started, since the CPU was asked for")
			is OpenGl.Unavailable ->
				Line(
					"OpenGL",
					"Couldn't be started: ${opengl.why}",
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
		Line("Display", "${videoName(rendering.video)}, through ${rendering.api}"),
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
			"Asked for",
			when (rendering.asked) {
				Drawing.Auto -> "Automatic: the GPU, where one can draw"
				Drawing.Gpu -> "The GPU"
				Drawing.Cpu -> "The CPU"
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
			"Where",
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
					"Nodes",
					nodes.joinToString(", ") { node ->
						node.path.toString() +
							if (node.access is Access.Denied)
								" (you can't open it)"
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
		Video.Other -> "Another display"
	}

/** A PCI address, as Linux writes one: domain, bus, device and function. */
private val PCI = Regex("[0-9a-f]{4}:[0-9a-f]{2}:[0-9a-f]{2}\\.[0-9a-f]")
