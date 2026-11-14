package prism.window

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * What a window is among the others on the screen. Most are windows as the platform has them: placed, stacked and
 * given the keyboard as its window manager does any. A launcher is not: it is over everything, a fullscreen window
 * included, where it says to be rather than where a window manager puts it, and typed into for as long as it is
 * shown. Every platform has its own way of saying so, or none:
 *
 * | platform | an overlay is |
 * |---|---|
 * | Wayland, where the compositor has layer-shell (niri, sway, Hyprland, KDE, COSMIC) | a surface in the overlay layer |
 * | Wayland without it (GNOME), and X11 | a window that cannot be resized, kept in front where it can be, which a window manager that tiles leaves floating |
 * | macOS, when prism is on it | a panel at a level above the windows' (`NSPanel`, non-activating, as Spotlight's is) |
 * | Windows, when prism is on it | a topmost tool window, in no taskbar |
 *
 * So what is asked for is said here in the platform's absence ([WindowRole.Overlay]: where, with what of the
 * keyboard, on which screen), each platform makes of it what it has ([surfaceFor]), and a window says how it turned
 * out ([NativeWindow.overlaid]).
 */

/** What a window is among the others on the screen. */
sealed interface WindowRole {
	/** A window as the platform has them: where, over what, and with the keyboard when, are its window manager's. */
	data object Normal : WindowRole

	/**
	 * Over every other window, fullscreen ones included, placed as [placement] says on the [screen] it says, and with
	 * the keyboard as [keyboard] says: what a launcher is. Its size is the one it opens at. [name] is what the
	 * platform's own rules know it by, where it has rules for such things (a compositor's, for its corners or its
	 * shadow), since it is no window to them.
	 *
	 * Where the platform has no such thing, it is a window that cannot be resized, in front where the platform lets
	 * one be kept there: [NativeWindow.overlaid] says which it is.
	 */
	data class Overlay(
		val name: String,
		val placement: Placement = Placement.Centred,
		val keyboard: Keyboard = Keyboard.Held,
		val screen: Screen = Screen.Current,
	) : WindowRole
}

/** Where on its screen an overlay is. */
sealed interface Placement {
	/** In the middle. */
	data object Centred : Placement

	/** Against [edge], in the middle of it, [margin] from it. */
	data class Against(
		val edge: Edge,
		val margin: Dp = 0.dp,
	) : Placement
}

/** An edge of a screen. */
enum class Edge {
	Top,
	Bottom,
	Left,
	Right,
}

/** What an overlay has of the keyboard. */
enum class Keyboard {
	/**
	 * The keyboard is its own for as long as it is shown: nothing else is typed into, and no click elsewhere takes
	 * it. What a launcher wants: it is typed into from the moment it is there, whatever had the keyboard before.
	 */
	Held,

	/**
	 * The keyboard is its own as it opens, and goes to whatever is clicked next, as a window's does: what a launcher
	 * that is put away by a click elsewhere wants. Where a platform takes the keyboard from an overlay that does
	 * not hold it (some Wayland compositors: sway), it is held after all, rather than have the overlay unable to be
	 * typed into.
	 */
	Shared,
}

/** Which screen an overlay is on. */
sealed interface Screen {
	/** The one the user is on, as the platform reckons that: where the keyboard or the pointer is. */
	data object Current : Screen

	/** The one the platform calls [name] (`DP-1`); the current one, where there is none of that name. */
	data class Named(
		val name: String,
	) : Screen
}

/** How a platform draws a window of some role: what [surfaceFor] decides, before any window is made. */
internal sealed interface Surfacing {
	/** A window of the platform's own, in front of the others where [inFront]. */
	data class Toplevel(
		val decorated: Boolean,
		val resizable: Boolean,
		val inFront: Boolean,
	) : Surfacing

	/** Wayland's layer-shell: a surface in the overlay layer, as [layer] says. */
	data class Layer(
		val layer: LayerSpec,
		val screen: Screen,
	) : Surfacing
}

/**
 * A layer surface, in layer-shell's own terms: its namespace, the edges it is anchored to, its margins, and whether,
 * once it has the keyboard, it [shares] it (waylandoverlay.kt).
 */
internal data class LayerSpec(
	val namespace: String,
	val anchor: Int,
	val margins: Margins,
	val shares: Boolean,
) {
	/** The room between a layer surface and each edge it is anchored to, in the compositor's units. */
	data class Margins(
		val top: Int = 0,
		val right: Int = 0,
		val bottom: Int = 0,
		val left: Int = 0,
	)

	companion object {
		const val ANCHOR_TOP = 1
		const val ANCHOR_BOTTOM = 2
		const val ANCHOR_LEFT = 4
		const val ANCHOR_RIGHT = 8

		/**
		 * The first version of layer-shell with [Interactivity.OnDemand]; before it, a surface has the keyboard wholly
		 * or not at all.
		 */
		const val ON_DEMAND_SINCE = 4

		/**
		 * [overlay] as layer-shell says it, to a compositor whose layer-shell is of [version]. A surface anchored to
		 * no edge is in the middle; anchored to one, it is against it, in the middle of it. A keyboard shared is one
		 * held, where the compositor's layer-shell is from before it could be shared: an overlay that is not typed
		 * into is worse than one that keeps the keyboard.
		 */
		fun of(overlay: WindowRole.Overlay, version: Int): LayerSpec {
			val against = overlay.placement as? Placement.Against
			val margin = against?.margin?.value?.toInt() ?: 0

			return LayerSpec(
				overlay.name,
				when (against?.edge) {
					null -> 0
					Edge.Top -> ANCHOR_TOP
					Edge.Bottom -> ANCHOR_BOTTOM
					Edge.Left -> ANCHOR_LEFT
					Edge.Right -> ANCHOR_RIGHT
				},
				when (against?.edge) {
					null -> Margins()
					Edge.Top -> Margins(top = margin)
					Edge.Bottom -> Margins(bottom = margin)
					Edge.Left -> Margins(left = margin)
					Edge.Right -> Margins(right = margin)
				},
				shares = overlay.keyboard == Keyboard.Shared && version >= ON_DEMAND_SINCE,
			)
		}
	}
}

/**
 * How a window of [role] is drawn on [video], whose compositor has layer-shell at version [layerShell] (null where it
 * has none, or the platform is not Wayland): an overlay in the overlay layer where there is one, and otherwise a
 * window that cannot be resized and has no title bar, in front where the platform keeps one there (Wayland lets no
 * window say so). A window of the usual kind is [decorated] and [resizable] as it asked.
 */
internal fun surfaceFor(
	role: WindowRole,
	video: Video,
	layerShell: Int?,
	decorated: Boolean,
	resizable: Boolean,
): Surfacing =
	when (role) {
		WindowRole.Normal -> Surfacing.Toplevel(decorated, resizable, inFront = false)
		is WindowRole.Overlay ->
			if (video == Video.Wayland && layerShell != null)
				Surfacing.Layer(LayerSpec.of(role, layerShell), role.screen)
			else
				Surfacing.Toplevel(
					decorated = false,
					resizable = false,
					inFront = video != Video.Wayland,
				)
	}
