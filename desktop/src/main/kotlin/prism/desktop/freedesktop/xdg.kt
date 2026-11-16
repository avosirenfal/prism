package prism.desktop.freedesktop

import java.nio.file.Files
import java.nio.file.Path
import prism.platform.XdgHomes

/**
 * Where freedesktop.org's specifications keep things, as the XDG Base Directory specification says, from [environment]
 * (the process's own, or a test's): the user's data and settings first, then the system's.
 */
class Xdg(
	private val environment: Map<String, String> = System.getenv(),
	val home: Path = Path.of(System.getProperty("user.home")),
) {
	private fun list(name: String, otherwise: List<String>): List<Path> =
		(
			environment[name]
				?.split(
					':',
				)?.filter { it.startsWith("/") }
				?.takeIf { it.isNotEmpty() }
				?: otherwise
		).map { Path.of(it) }

	private val homes = XdgHomes(environment, home)

	val dataHome: Path get() = homes.data
	val configHome: Path get() = homes.config

	/** Where what can be made again is kept: thumbnails, among much else. */
	val cacheHome: Path get() = homes.cache
	val runtime: Path? get() = homes.runtime

	/** The data folders, the user's first: where MIME types and applications are looked for. */
	val data: List<Path> get() =
		listOf(dataHome) +
			list("XDG_DATA_DIRS", profiles() + listOf("/usr/local/share", "/usr/share"))

	/**
	 * Where Nix keeps what is installed, the user's own first, where those folders are there: what a session on NixOS
	 * names in `XDG_DATA_DIRS`, and all there is to find where a service was started without the session's
	 * environment, since NixOS has nothing in `/usr/share`.
	 */
	private fun profiles(): List<String> =
		listOfNotNull(
			home.resolve(".nix-profile/share").toString(),
			environment["USER"]?.let { "/etc/profiles/per-user/$it/share" },
			"/run/current-system/sw/share",
		).filter { Files.isDirectory(Path.of(it)) }

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
