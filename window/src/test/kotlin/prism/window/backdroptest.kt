package prism.window

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether a window that asks to be clear is: decided from what the platform is, whether anything composites it, and
 * whether the window's pixels turned out to have an alpha channel, so it is decided here without a window.
 */
class BackdropTest {
	private fun never(): Boolean =
		error("a window that is not on X11 does not ask what composites X11")

	@Test
	fun aWindowThatDoesNotAskToBeClearIsNotOnAnyPlatformAndAsksNothingOfIt() {
		for (video in Video.entries)
			assertFalse(mayBeClear(Backdrop.Opaque, video, ::never), "$video")
	}

	@Test
	fun aWindowMayBeClearOnWaylandAlwaysAndOnX11WhereSomethingComposites() {
		assertTrue(mayBeClear(Backdrop.Clear, Video.Wayland, ::never))
		assertTrue(mayBeClear(Backdrop.Clear, Video.X11) { true })
		// Nothing composites the screen: alpha would be nobody's to blend, and a clear corner would be black.
		assertFalse(mayBeClear(Backdrop.Clear, Video.X11) { false })

		for (video in Video.entries - Video.Wayland - Video.X11)
			assertFalse(mayBeClear(Backdrop.Clear, video, ::never), "$video")
	}

	@Test
	fun aWindowIsClearOnlyWhereItMayBeAndItsPixelsHaveAnAlphaChannel() {
		assertEquals(Backdrop.Clear, backdropOf(may = true, alpha = true))
		// X11 drawn on the CPU, or an EGL configuration without alpha: asked for, and not to be had.
		assertEquals(Backdrop.Opaque, backdropOf(may = true, alpha = false))
		// A window dragged from draws with alpha on Wayland whatever it asked: that does not make it clear.
		assertEquals(Backdrop.Opaque, backdropOf(may = false, alpha = true))
		assertEquals(Backdrop.Opaque, backdropOf(may = false, alpha = false))
	}
}
