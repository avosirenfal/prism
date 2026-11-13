package prism.window

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/*
 * Bringing forward the window of a program prism starts. A compositor does not let a program take the screen from the
 * user of its own accord: a window that asks to be brought forward is, only where the user asked for it. On Wayland,
 * that is said by a token (xdg-activation), which the program that the user pressed something in asks the compositor
 * for, and gives the program it starts, in `XDG_ACTIVATION_TOKEN`; that program asks with it for its window to be
 * brought forward. A program already running, given a file by a new start of itself (a text editor with one window
 * for every file), hands the token to the window that is running, which is then brought forward.
 *
 * The token is made from the serial of the user's last press of a key or a button in one of prism's windows, and the
 * window that has the keyboard, which the compositor checks: niri, for one, gives one that is made otherwise only the
 * right to mark the window as wanting attention.
 */

/** How long a start waits for the compositor to give its token, before it starts without one. */
private const val WAIT_MILLIS = 500L

/**
 * A token for bringing forward a window of the program about to be started, the application [appId] where that is
 * known (its desktop entry's name, without `.desktop`), as the platform makes it; null where it makes none (there is
 * no window open, or the platform is not Wayland, or its compositor has no xdg-activation), or did not in time.
 * Asked for from any thread, for each start: a token is used once.
 */
fun activationToken(appId: String?): String? {
	val app = Application.current ?: return null
	// Asked for on the windows' thread, where the window it is asked from is surely still open; answered on another.
	val asked = Ui.call { app.activation(appId) } ?: return null

	return try {
		asked.get(WAIT_MILLIS, TimeUnit.MILLISECONDS)
	} catch (late: TimeoutException) {
		Log.say { "activation: no token in ${WAIT_MILLIS}ms" }
		null
	} catch (gone: CancellationException) {
		null
	} catch (failed: ExecutionException) {
		null
	}
}
