package prism.launcher

import prism.macos.Applications
import prism.platform.Os
import prism.window.OpenWindow
import prism.window.OpenWindows
import prism.window.openWindows

/*
 * The applications running, on a Mac, as the launcher's "Focus window" needs them. A Mac tells a program of another's
 * windows only with Screen Recording (their titles) or Accessibility (bringing one forward), which prism does not ask
 * for; it tells of the applications running freely, and brings one forward with every window it has. So each
 * application running is one "window", by its bundle's identifier (which an application's id is, on a Mac) and its
 * name, and focusing it brings it forward whole.
 */

/** The applications running, each as the one window the launcher can bring forward. */
object RunningApplications : OpenWindows.Listed() {
	override fun now(): List<OpenWindow> =
		Applications.running().mapNotNull { running ->
			running.bundle?.let(Applications::bundle)?.identifier?.let {
				OpenWindow(running.pid.toLong(), it, running.name)
			}
		}

	override fun focus(window: OpenWindow) {
		Applications.activate(window.id.toInt())
	}
}

/** The windows open on this platform, as the launcher asks for them: the compositor's, or a Mac's applications. */
fun platformWindows(): OpenWindows =
	if (Os.current ==
		Os.MacOS
	)
		RunningApplications
	else
		openWindows()
