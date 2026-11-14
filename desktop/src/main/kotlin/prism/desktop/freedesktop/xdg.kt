package prism.desktop.freedesktop

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where freedesktop.org's specifications keep things, as the XDG Base Directory specification says, from [environment]
 * (the process's own, or a test's): the user's data and settings first, then the system's.
 */
class Xdg(
	private val environment: Map<String, String> = System.getenv(),
	val home: Path = Path.of(System.getProperty("user.home")),
) {
	private fun absolute(name: String): Path? =
		environment[name]?.takeIf { it.startsWith("/") }?.let { Path.of(it) }

	private fun list(name: String, otherwise: List<String>): List<Path> =
		(
			environment[name]
				?.split(
					':',
				)?.filter { it.startsWith("/") }
				?.takeIf { it.isNotEmpty() }
				?: otherwise
		).map { Path.of(it) }

	val dataHome: Path get() = absolute("XDG_DATA_HOME") ?: home.resolve(".local/share")
	val configHome: Path get() = absolute("XDG_CONFIG_HOME") ?: home.resolve(".config")
	val runtime: Path? get() = absolute("XDG_RUNTIME_DIR")

	/** The data folders, the user's first: where MIME types and applications are looked for. */
	val data: List<Path> get() =
		listOf(dataHome) +
			list("XDG_DATA_DIRS", listOf("/usr/local/share", "/usr/share"))

	/** The settings folders, the user's first. */
	val config: List<Path> get() =
		listOf(configHome) +
			list("XDG_CONFIG_DIRS", listOf("/etc/xdg"))

	/** The desktops the session says it is (`GNOME`, `KDE`, `niri`), lower case, the first most particular. */
	val desktops: List<String>
		get() =
			environment["XDG_CURRENT_DESKTOP"]
				?.split(':')
				?.map { it.lowercase() }
				?.filter { it.isNotEmpty() }
				.orEmpty()

	/**
	 * The names a key written for the user's language may have after it, in brackets, the most particular first: for
	 * `sr_YU@Latn`, that, then `sr_YU`, `sr@Latn` and `sr`, as the Desktop Entry specification matches them. The
	 * language is what messages are in (`LC_ALL`, else `LC_MESSAGES`, else `LANG`), its encoding aside; none for `C`
	 * and `POSIX`, or where none is said.
	 */
	val languages: List<String>
		get() {
			val said =
				listOf("LC_ALL", "LC_MESSAGES", "LANG")
					.firstNotNullOfOrNull { environment[it]?.takeIf(String::isNotEmpty) }
					?: return emptyList()
			val modifier = said.substringAfter('@', "").takeIf { it.isNotEmpty() }
			val named = said.substringBefore('@').substringBefore('.')
			val language = named.substringBefore('_')
			val country = named.substringAfter('_', "").takeIf { it.isNotEmpty() }

			if (language.isEmpty() || language == "C" || language == "POSIX")
				return emptyList()

			return listOfNotNull(
				country?.let { c -> modifier?.let { "${language}_$c@$it" } },
				country?.let { "${language}_$it" },
				modifier?.let { "$language@$it" },
				language,
			)
		}

	/** [program] where it is: as it is where it has a `/`, or in a folder of the `PATH`; null where it is not. */
	fun program(program: String): Path? {
		if ('/' in program)
			return Path.of(program).takeIf { Files.isExecutable(it) && !Files.isDirectory(it) }

		return environment["PATH"]
			.orEmpty()
			.split(':')
			.filter { it.isNotEmpty() }
			.map { Path.of(it, program) }
			.firstOrNull { Files.isExecutable(it) && !Files.isDirectory(it) }
	}

	/** An environment variable, as given. */
	operator fun get(name: String): String? = environment[name]
}
