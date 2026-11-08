package barion.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import barion.cli.Args
import barion.cli.Command
import barion.cli.InputError
import barion.cli.Outcome
import barion.cli.ParamType
import barion.cli.Size
import barion.picker.Choosing
import barion.picker.FilePicker
import barion.picker.Palette
import barion.picker.PickerEvent
import barion.picker.PickerState
import barion.picker.PickerTheme
import barion.picker.pickerOf
import barion.picker.renderedPng
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.writeBytes

/*
 * The picker from the command line: in a window (`barion pick`), or drawn into a picture (`barion snapshot`), each
 * over a real folder read through the JVM. The picker is a view for now: what the user does is printed as the
 * events the view reports, and the window closes on Open or Cancel, so the shape of the conversation between the
 * view and whatever will hold its state can be seen before anything holds it.
 */

/** The parameters both commands take: the folder, what the picker is choosing, and the palette. */
private class Picking(
	command: Command,
) {
	private val folder =
		command.argument(
			"FOLDER",
			ParamType.FOLDER,
			default = { Path(System.getProperty("user.home")) },
			shown = "your home",
		)
	private val save =
		command.option(
			"--save",
			type = ParamType.TEXT.named("NAME"),
			help = "a picker that saves, under NAME",
		)
	private val many = command.flag("--multiple", help = "a picker that opens several files")
	private val folders = command.flag("--folder", help = "a picker that chooses a folder")
	private val dark = command.flag("--dark", help = "the dark palette")

	fun state(args: Args): PickerState {
		val asked =
			listOfNotNull(
				args[save]?.let {
					"--save"
				},
				"--multiple".takeIf { args[many] },
				"--folder".takeIf { args[folders] },
			)

		if (asked.size > 1)
			throw InputError(
				"${asked.joinToString(" and ")} ask for different pickers; ask for one",
			)

		val choosing =
			when {
				args[save] != null -> Choosing.SaveAs(args[save]!!)
				args[many] -> Choosing.Files(emptySet())
				args[folders] -> Choosing.Folder(null)
				else -> Choosing.OneFile(null)
			}

		return pickerOf(args[folder], choosing)
	}

	fun palette(args: Args): Palette = if (args[dark]) Palette.Dark else Palette.Light
}

/** `barion pick`: the picker in a window. */
object PickCommand : Command(
	"pick",
	"open the picker in a window over a folder, printing what the user does",
) {
	private val picking = Picking(this)

	override fun run(args: Args): Outcome {
		val state = picking.state(args)
		val palette = picking.palette(args)

		application {
			Window(
				onCloseRequest = ::exitApplication,
				title = state.title,
				state = rememberWindowState(width = 1100.dp, height = 680.dp),
			) {
				PickerTheme(palette) {
					FilePicker(state) { event ->
						println(event)

						if (event == PickerEvent.Accepted || event == PickerEvent.Cancelled)
							exitApplication()
					}
				}
			}
		}

		return Outcome("")
	}
}

/** `barion snapshot`: the picker drawn headless into a PNG. */
object SnapshotCommand : Command(
	"snapshot",
	"draw the picker over a folder into a PNG, with no display",
) {
	private val picking = Picking(this)
	private val out =
		required("--out", type = ParamType.PATH.named("FILE"), help = "where the PNG goes")
	private val size =
		option(
			"--size",
			type = Size.TYPE,
			default = Size(1100, 680),
			help = "the window's size",
			shown = "1100x680",
		)
	private val scale =
		option("--scale", type = ParamType.COUNT, default = 2, help = "the display's density")

	override fun run(args: Args): Outcome {
		val png =
			renderedPng(
				picking.state(args),
				args[size].width,
				args[size].height,
				picking.palette(args),
				args[scale].toFloat(),
			)

		val into: Path = args[out]

		into.writeBytes(png)

		return Outcome("${into.toAbsolutePath()}: ${png.size} bytes")
	}
}
