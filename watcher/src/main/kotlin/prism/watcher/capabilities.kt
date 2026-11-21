package prism.watcher

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What this process may do that loading a watcher takes, as the kernel says of it. */
internal object Capabilities {
	/** `CAP_PERFMON` and `CAP_BPF`, as the kernel numbers capabilities. */
	private const val PERFMON = 38
	private const val BPF = 39

	/**
	 * Whether this process may load and attach a BPF program: it has `CAP_BPF` and `CAP_PERFMON` in effect (root has
	 * every capability). Where the kernel cannot be asked, it is taken that it may, and loading says.
	 */
	fun mayLoadBpf(status: Path = Path.of("/proc/self/status")): Boolean {
		val effective =
			try {
				Files.readAllLines(status).firstOrNull { it.startsWith("CapEff:") }
			} catch (unread: IOException) {
				null
			} ?: return true

		return mayLoadBpf(effective.substringAfter(':').trim().toULong(16))
	}

	/** Whether [effective], a process's effective capabilities, hold both. */
	fun mayLoadBpf(effective: ULong): Boolean =
		listOf(PERFMON, BPF).all { effective and (1uL shl it) != 0uL }
}
