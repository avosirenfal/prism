package prism

/**
 * The platform a build runs on, which decides what of prism it can build and what it packages: the probes compile
 * only where clang has the BPF target and there is a kernel to load them into (Linux), and a package carries its own
 * platform's natives (SDL's, LWJGL's) and is that platform's kind of package.
 */
enum class Host(
	/** The platform as LWJGL's natives name it: `natives-macos`, `natives-macos-arm64`. */
	private val family: String,
) {
	Linux("linux"),
	MacOS("macos"),
	Windows("windows"),
	;

	/** Whether the jar named [jar] holds the natives of another platform, which a package of this one leaves out. */
	fun elsewhere(jar: String): Boolean =
		entries.any {
			it != this &&
				"natives-${it.family}" in jar
		}

	companion object {
		/** The platform this build runs on, by the JVM's name for it. */
		val current: Host =
			System.getProperty("os.name").let { name ->
				when {
					name.startsWith("Mac") -> MacOS
					name.startsWith("Windows") -> Windows
					else -> Linux
				}
			}
	}
}
