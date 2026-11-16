package prism.platform

/** The platforms prism knows. */
enum class Os {
	Linux,
	MacOS,
	Windows,
	;

	companion object {
		/** The platform of the JVM's [name] for it (`os.name`): `Linux`, `Mac OS X`, `Windows 11`; Linux's kin otherwise. */
		fun named(name: String): Os =
			when {
				name.startsWith("Mac") -> MacOS
				name.startsWith("Windows") -> Windows
				else -> Linux
			}

		/** The platform this process runs on. */
		val current: Os = named(System.getProperty("os.name"))
	}
}
