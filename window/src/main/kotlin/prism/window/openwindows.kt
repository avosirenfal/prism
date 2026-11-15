package prism.window

/*
 * The windows other applications have open, and one of them brought forward: what a launcher does for an application
 * that is running already. A desktop tells a program of other programs' windows only where it has a way made for
 * that. On Wayland the way is wlr-foreign-toplevel-management (waylandtoplevels.kt), which sway, niri, Hyprland and
 * the other compositors built on wlroots or Smithay have, and GNOME and KDE do not. Where there is no such way, that
 * is said as a value ([OpenWindows.Unavailable]), so whoever asks offers nothing that needs it.
 */

/** A window an application has open, as the desktop tells of it. */
data class OpenWindow(
	/** What tells it from every other window open now. */
	val id: Long,
	/** The application it is a window of, as that names itself to the desktop: `org.mozilla.firefox`, `foot`. */
	val appId: String,
	val title: String,
)

/** What the desktop tells of the windows applications have open: nothing, or which there are. */
sealed interface OpenWindows {
	/** The desktop has no way to tell: no window can be listed, and none brought forward. */
	data object Unavailable : OpenWindows

	/** The desktop tells: the windows open are listed, and one of them can be brought forward. */
	abstract class Listed : OpenWindows {
		/** The windows open now, in the order they were opened in. */
		abstract fun now(): List<OpenWindow>

		/** [window] brought forward and given the keyboard, where it is open still. */
		abstract fun focus(window: OpenWindow)
	}
}

/**
 * What the desktop tells of the windows open on it, asked from any thread: [OpenWindows.Unavailable] where the
 * platform is not Wayland, its compositor has no wlr-foreign-toplevel-management, or no application is running to ask.
 */
fun openWindows(): OpenWindows {
	val app = Application.current ?: return OpenWindows.Unavailable

	// Asked on the windows' thread, where prism's own connection to the compositor is made the first time it is needed.
	return Ui.call { app.seat()?.toplevels } ?: OpenWindows.Unavailable
}
