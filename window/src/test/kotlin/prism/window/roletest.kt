package prism.window

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a window of each role is made as on each platform: decided before any window is, from what the platform is and
 * what its compositor has, so it is decided here without either.
 */
class RoleTest {
	private val launcher = WindowRole.Overlay("prism-launcher")

	private fun surface(
		role: WindowRole,
		video: Video,
		layerShell: Int? = null,
		decorated: Boolean = true,
		resizable: Boolean = true,
	) = surfaceFor(role, video, layerShell, decorated, resizable)

	@Test
	fun aWindowOfTheUsualKindIsAsItAskedOnEveryPlatform() {
		for (video in Video.entries) {
			assertEquals(
				Surfacing.Toplevel(decorated = true, resizable = true, inFront = false),
				surface(WindowRole.Normal, video, layerShell = 4),
			)
			assertEquals(
				Surfacing.Toplevel(decorated = false, resizable = false, inFront = false),
				surface(WindowRole.Normal, video, decorated = false, resizable = false),
			)
		}
	}

	@Test
	fun anOverlayIsALayerSurfaceWhereThereAreLayersAndAWindowKeptInFrontWhereThereAreNone() {
		assertEquals(
			Surfacing.Layer(
				LayerSpec("prism-launcher", 0, LayerSpec.Margins(), shares = false),
				Screen.Current,
			),
			surface(launcher, Video.Wayland, layerShell = 4),
		)
		// A Wayland compositor without layer-shell (GNOME's): a window, which Wayland lets nothing keep in front.
		assertEquals(
			Surfacing.Toplevel(decorated = false, resizable = false, inFront = false),
			surface(launcher, Video.Wayland, layerShell = null),
		)
		// A Mac: a non-activating panel, as Spotlight's is.
		assertEquals(Surfacing.Panel, surface(launcher, Video.Cocoa, layerShell = null))
		// Every other platform, until it has an overlay of its own: a window kept in front, whatever was asked of
		// its title bar and its size, and whatever layer-shell a compositor that is not there might have had.
		for (video in Video.entries - Video.Wayland - Video.Cocoa)
			assertEquals(
				Surfacing.Toplevel(decorated = false, resizable = false, inFront = true),
				surface(launcher, video, layerShell = 4),
			)
	}

	@Test
	fun whereAnOverlayIsAndWhatItHasOfTheKeyboardAreSaidAsLayerShellSaysThem() {
		fun spec(overlay: WindowRole.Overlay, version: Int = 4) = LayerSpec.of(overlay, version)

		// In the middle: anchored to no edge.
		assertEquals(0, spec(launcher).anchor)
		assertEquals(LayerSpec.Margins(), spec(launcher).margins)

		// Against an edge: anchored to it alone, which is the middle of it, its margin from that edge.
		val top = spec(launcher.copy(placement = Placement.Against(Edge.Top, 120.dp)))

		assertEquals(LayerSpec.ANCHOR_TOP, top.anchor)
		assertEquals(LayerSpec.Margins(top = 120), top.margins)
		assertEquals(
			LayerSpec.ANCHOR_RIGHT to LayerSpec.Margins(right = 8),
			spec(launcher.copy(placement = Placement.Against(Edge.Right, 8.dp))).let {
				it.anchor to it.margins
			},
		)
		assertEquals(
			LayerSpec.ANCHOR_BOTTOM to LayerSpec.Margins(),
			spec(launcher.copy(placement = Placement.Against(Edge.Bottom))).let {
				it.anchor to it.margins
			},
		)
		assertEquals(
			LayerSpec.ANCHOR_LEFT,
			spec(launcher.copy(placement = Placement.Against(Edge.Left))).anchor,
		)

		// The keyboard held is never shared; shared, it is, where the compositor's layer-shell is late enough to have
		// a surface say so, and held before that.
		assertEquals(false, spec(launcher).shares)
		assertEquals(true, spec(launcher.copy(keyboard = Keyboard.Shared)).shares)
		assertEquals(false, spec(launcher.copy(keyboard = Keyboard.Shared), version = 3).shares)
		assertEquals("prism-launcher", spec(launcher).namespace)
	}

	@Test
	fun theScreenAskedForIsCarriedToTheLayer() {
		assertEquals(
			Screen.Named("DP-2"),
			(
				surface(
					launcher.copy(screen = Screen.Named("DP-2")),
					Video.Wayland,
					layerShell = 4,
				) as Surfacing.Layer
			).screen,
		)
	}
}
