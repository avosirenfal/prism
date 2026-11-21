package prism.watcher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CapabilitiesTest {
	@TempDir
	lateinit var folder: Path

	@Test
	fun aProcessMayLoadAWatcherOnlyWithBothCapabilitiesInEffect() {
		// Root's (every capability), a user's (none), and one with CAP_BPF alone.
		assertTrue(Capabilities.mayLoadBpf(0x000001ffffffffffuL))
		assertFalse(Capabilities.mayLoadBpf(0uL))
		assertFalse(Capabilities.mayLoadBpf(1uL shl 39))
		assertTrue(Capabilities.mayLoadBpf((1uL shl 39) or (1uL shl 38)))

		// As the kernel writes them.
		val status = folder.resolve("status")

		status.writeText("Name:\tjava\nCapInh:\t0000000000000000\nCapEff:\t0000000000000000\n")
		assertFalse(Capabilities.mayLoadBpf(status))
		status.writeText("Name:\tjava\nCapEff:\t000001ffffffffff\n")
		assertTrue(Capabilities.mayLoadBpf(status))
		// Where the kernel cannot be asked, loading is left to say.
		assertTrue(Capabilities.mayLoadBpf(folder.resolve("missing")))
		Files.delete(status)
	}
}
