package prism.window

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What is told of other applications' windows where there is nothing to ask: that nothing is. */
class OpenWindowsTest {
	@Test
	fun withNoApplicationRunningTheDesktopTellsOfNoWindows() {
		// No windows of prism's own, so no connection to a compositor to ask: said as a value, and nothing thrown.
		assertEquals(OpenWindows.Unavailable, openWindows())
	}
}
