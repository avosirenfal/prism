package prism.desktop.freedesktop

import java.nio.file.Path
import prism.desktop.Activation
import prism.desktop.Desktop
import prism.desktop.FileFacts
import prism.desktop.JvmFacts
import prism.linux.Fd
import prism.linux.Sys
import prism.linux.statx

/** A freedesktop.org desktop, as Linux's are, from [xdg]'s environment; what it starts brought forward by [activation]. */
class Freedesktop(
	val xdg: Xdg = Xdg(),
	activation: Activation = Activation.None,
) : Desktop {
	override val types = SharedMimeInfo(xdg)
	override val applications = DesktopEntries(xdg, types)
	override val launcher = DetachedLauncher(xdg, activation)
	override val icons = IconThemes(xdg)
	override val places = XdgPlaces()
	override val storage = LinuxStorage()
	override val facts: FileFacts = JvmFacts(types, ::bornAt)

	private companion object {
		/** When the file at [path] was made, as statx(2) says, where the file system keeps it. */
		fun bornAt(path: Path) = (statx(Fd.CWD, path.toString()) as? Sys.Ok)?.value?.born
	}
}
