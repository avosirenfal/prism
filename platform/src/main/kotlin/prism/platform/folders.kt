package prism.platform

import java.nio.file.Path

/** A folder of prism's own, and how it is said to a person: `$XDG_CACHE_HOME/prism`, `~/Library/Caches/prism`. */
data class OwnFolder(
	val path: Path,
	val said: String,
) {
	/** The file or folder [name] in this one, said under it. */
	fun resolve(name: String): OwnFolder = OwnFolder(path.resolve(name), "$said/$name")
}

/**
 * Where prism keeps what is its own, each kind where its platform keeps that kind: on Linux, the XDG Base Directory
 * specification's folders; on macOS, the user's Library; on Windows, the user's application data.
 */
data class OwnFolders(
	/** What the user sets (the preferences): kept, and moved with them to another machine. */
	val config: OwnFolder,
	/** What can always be made again (the index). */
	val cache: OwnFolder,
	/** What prism keeps of its own doing: the usage, the folders gone to, what a daemon started apart has said. */
	val state: OwnFolder,
	/**
	 * Where a session's prism listens: the user's alone, and where sockets are kept; or why the session has no such
	 * place (a Linux session without `XDG_RUNTIME_DIR`), and then every prism is on its own.
	 */
	val runtime: Runtime,
) {
	companion object {
		/** prism's folders on [os], from [environment] and the user's [home]: what this process uses, by default. */
		fun of(
			os: Os = Os.current,
			environment: Map<String, String> = System.getenv(),
			home: Path = Path.of(System.getProperty("user.home")),
		): OwnFolders =
			when (os) {
				Os.Linux -> linux(XdgHomes(environment, home))
				Os.MacOS -> macOS(environment, home)
				Os.Windows -> windows(environment, home)
			}

		private fun linux(xdg: XdgHomes): OwnFolders =
			OwnFolders(
				config = OwnFolder(xdg.config, "\$XDG_CONFIG_HOME").resolve(PRISM),
				cache = OwnFolder(xdg.cache, "\$XDG_CACHE_HOME").resolve(PRISM),
				state = OwnFolder(xdg.state, "\$XDG_STATE_HOME").resolve(PRISM),
				runtime = Runtime.of(xdg.runtime, "\$XDG_RUNTIME_DIR"),
			)

		/**
		 * The Library's folders, as Apple's guidelines put each kind: the preferences and what prism keeps of its own
		 * doing in Application Support (Preferences is for property lists, which the preferences are not), the index
		 * in Caches. The socket is in the user's own temporary folder (`$TMPDIR`, under /var/folders), which is theirs
		 * alone, as a Linux session's runtime folder is, and short enough a path for a socket's name (104 bytes, on
		 * macOS).
		 */
		private fun macOS(environment: Map<String, String>, home: Path): OwnFolders {
			val library = OwnFolder(home.resolve("Library"), "~/Library")
			val support = library.resolve("Application Support").resolve(PRISM)

			return OwnFolders(
				config = support,
				cache = library.resolve("Caches").resolve(PRISM),
				state = support,
				runtime =
					Runtime.of(
						environment["TMPDIR"]
							?.takeIf {
								it.startsWith("/")
							}?.let(Path::of),
						"\$TMPDIR",
					),
			)
		}

		/**
		 * The user's application data: what is set in the roaming folder (`%APPDATA%`), which follows a user between
		 * machines, and the rest in the local one (`%LOCALAPPDATA%`), the socket included (Windows' sockets are files
		 * too, since Windows 10).
		 */
		private fun windows(environment: Map<String, String>, home: Path): OwnFolders {
			fun named(variable: String, otherwise: String): OwnFolder =
				OwnFolder(
					environment[variable]?.let(Path::of)?.takeIf(Path::isAbsolute)
						?: home.resolve(otherwise),
					"%$variable%",
				)

			val local = named("LOCALAPPDATA", "AppData\\Local").resolve(PRISM)

			return OwnFolders(
				config = named("APPDATA", "AppData\\Roaming").resolve(PRISM),
				cache = local.resolve("cache"),
				state = local,
				runtime = Runtime.At(local.resolve("run")),
			)
		}

		internal const val PRISM = "prism"
	}
}

/** Where a session's prism listens, or why there is nowhere. */
sealed interface Runtime {
	data class At(
		val folder: OwnFolder,
	) : Runtime

	data class Missing(
		/** What the session lacks: `$XDG_RUNTIME_DIR is not set`. */
		val why: String,
	) : Runtime

	companion object {
		/** prism's folder in [folder], which the variable [said] names; missing, saying so, where it names none. */
		internal fun of(folder: Path?, said: String): Runtime =
			folder?.let { At(OwnFolder(it, said).resolve(OwnFolders.PRISM)) }
				?: Missing("${said.removePrefix("$")} is not set")
	}
}

/**
 * The user's folders of the XDG Base Directory specification, from [environment]: each the variable's, where it is an
 * absolute path (the specification says a relative one is to be ignored), and its default under [home] otherwise.
 */
class XdgHomes(
	private val environment: Map<String, String> = System.getenv(),
	val home: Path = Path.of(System.getProperty("user.home")),
) {
	private fun absolute(name: String): Path? =
		environment[name]?.takeIf { it.startsWith("/") }?.let { Path.of(it) }

	val data: Path get() = absolute("XDG_DATA_HOME") ?: home.resolve(".local/share")
	val config: Path get() = absolute("XDG_CONFIG_HOME") ?: home.resolve(".config")
	val cache: Path get() = absolute("XDG_CACHE_HOME") ?: home.resolve(".cache")
	val state: Path get() = absolute("XDG_STATE_HOME") ?: home.resolve(".local/state")
	val runtime: Path? get() = absolute("XDG_RUNTIME_DIR")
}
