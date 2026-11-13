package prism.desktop

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What a program prism starts finds in its environment: the session's, with nothing arranged for running prism. */
class EnvironmentTest {
	@Test
	fun whatALauncherChangedForPrismIsPutBackAsItWas() {
		// A launcher that put prism's libraries before what was there, and one where nothing was.
		assertEquals(
			mapOf("LD_LIBRARY_PATH" to "/opt/lib", "PATH" to "/bin"),
			childEnvironment(
				mapOf(
					"LD_LIBRARY_PATH" to "/nix/store/x/lib:/opt/lib",
					"PRISM_RESTORE_LD_LIBRARY_PATH" to "/opt/lib",
					"PATH" to "/bin",
				),
				appImage = null,
			),
		)
		assertEquals(
			mapOf("PATH" to "/bin"),
			childEnvironment(
				mapOf(
					"LD_LIBRARY_PATH" to "/nix/store/x/lib",
					"PRISM_RESTORE_LD_LIBRARY_PATH" to "",
					"PATH" to "/bin",
				),
				appImage = null,
			),
		)
	}

	@Test
	fun nothingOfAnAppImagesLauncherIsPassedOn() {
		val launcher = Path.of("/opt/prism/bin/prism")

		// Its folder taken off the path, what else was on it kept; and its mark, which breaks its like, left out.
		assertEquals(
			mapOf("LD_LIBRARY_PATH" to "/opt/lib", "HOME" to "/home/a"),
			childEnvironment(
				mapOf(
					"LD_LIBRARY_PATH" to "/opt/lib:/opt/prism/lib/app",
					"_JPACKAGE_LAUNCHER" to "0",
					"HOME" to "/home/a",
				),
				launcher,
			),
		)
		// As jpackage writes it where nothing was there: its folder after nothing.
		assertEquals(
			mapOf("HOME" to "/home/a"),
			childEnvironment(
				mapOf(
					"LD_LIBRARY_PATH" to ":/opt/prism/lib/app",
					"_JPACKAGE_LAUNCHER" to "0",
					"HOME" to "/home/a",
				),
				launcher,
			),
		)
	}

	@Test
	fun anEnvironmentNothingArrangedIsPassedOnAsItIs() {
		val own = mapOf("LD_LIBRARY_PATH" to "/opt/lib", "EMPTY" to "", "PATH" to "/bin")

		assertEquals(own, childEnvironment(own, appImage = null))
	}
}
