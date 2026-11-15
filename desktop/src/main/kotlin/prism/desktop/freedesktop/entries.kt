package prism.desktop.freedesktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import prism.desktop.Application
import prism.desktop.ApplicationAction
import prism.desktop.MimeType

/*
 * Desktop entries (the `.desktop` files an application installs) and the key files they are written in, as the Desktop
 * Entry specification says: groups in brackets, `Key=Value` lines, `#` comments. And an entry's `Exec` line, which is
 * not split on spaces but read with the specification's quoting, and whose field codes (`%f`, `%U`, ...) say where
 * the files it is given go.
 */

/** A key file read: each group's keys, in order. Localized keys (`Name[de]`) are kept as they are named. */
class KeyFile(
	val groups: Map<String, Map<String, String>>,
) {
	operator fun get(group: String): Map<String, String> = groups[group].orEmpty()

	companion object {
		fun parse(text: String): KeyFile {
			val groups = LinkedHashMap<String, LinkedHashMap<String, String>>()
			var current: LinkedHashMap<String, String>? = null

			for (raw in text.lines()) {
				val line = raw.trim()

				when {
					line.isEmpty() || line.startsWith("#") -> Unit
					line.startsWith("[") && line.endsWith("]") ->
						current = groups.getOrPut(line.substring(1, line.length - 1)) { LinkedHashMap() }
					'=' in line ->
						current?.putIfAbsent(
							line.substringBefore('=').trim(),
							unescaped(line.substringAfter('=').trim()),
						)
				}
			}

			return KeyFile(groups)
		}

		fun read(file: Path): KeyFile? =
			try {
				parse(Files.readString(file))
			} catch (failed: IOException) {
				null
			}

		/** A value's escapes: `\s`, `\n`, `\t`, `\r`, `\\`. (`\;` is a list's, left for the list to read.) */
		private fun unescaped(value: String): String =
			buildString {
				var at = 0

				while (at < value.length) {
					val char = value[at]

					if (char == '\\' && at + 1 < value.length) {
						when (val next = value[at + 1]) {
							's' -> append(' ')
							'n' -> append('\n')
							't' -> append('\t')
							'r' -> append('\r')
							'\\' -> append('\\')
							else -> append('\\').append(next)
						}
						at += 2
					} else {
						append(char)
						at++
					}
				}
			}
	}
}

/**
 * A list's items, as a key file writes one: apart by [separator] (`;` in a desktop entry, `,` in an icon theme's
 * index), which is inside an item where a `\` comes before it.
 */
fun itemsOf(value: String?, separator: Char = ';'): List<String> =
	value
		?.split(Regex("(?<!\\\\)" + Regex.escape(separator.toString())))
		?.map { it.replace("\\$separator", separator.toString()).trim() }
		?.filter { it.isNotEmpty() }
		.orEmpty()

/** An application a desktop entry says is installed, and whether a person choosing among applications is [shown] it. */
class Installed(
	val application: Application,
	val shown: Boolean,
)

/**
 * What the desktop entry [entry], known by [id], says is installed, on the desktop [xdg] says this is; null for an
 * entry that is not an application, or says it is deleted (`Hidden`), or has nothing to run (no `Exec`, or one that
 * does not read; a `DBusActivatable` entry with no `Exec` is started over D-Bus, which prism does not do), or whose
 * program is not there (`TryExec`).
 *
 * What it says in words is read in the user's language where it says it in that ([Xdg.languages]). It is not shown
 * where it says so (`NoDisplay`), or says it is for other desktops than this (`OnlyShowIn`, `NotShowIn`, against
 * `XDG_CURRENT_DESKTOP`); it is installed all the same, and opens what it says it opens.
 */
fun installedOf(id: String, file: Path, entry: KeyFile, xdg: Xdg): Installed? {
	val keys = entry[ENTRY]

	if (keys["Type"] != "Application" || keys["Hidden"] == "true")
		return null
	if (keys["TryExec"]?.let { it.isNotEmpty() && xdg.program(it) == null } == true)
		return null

	val command = keys["Exec"]?.let(Exec::split)?.takeIf { it.isNotEmpty() } ?: return null
	val said = Said(keys, xdg.languages)

	val actions =
		itemsOf(keys["Actions"]).mapNotNull { action ->
			val its = entry["Desktop Action $action"]

			ApplicationAction(
				id = action,
				name = Said(its, xdg.languages)["Name"] ?: return@mapNotNull null,
				command =
					its["Exec"]?.let(Exec::split)?.takeIf { it.isNotEmpty() }
						?: return@mapNotNull null,
				icon = its["Icon"]?.takeIf { it.isNotEmpty() },
			)
		}

	fun names(key: String) = itemsOf(keys[key]).map { it.lowercase() }

	val desktops = xdg.desktops
	val here =
		(keys["OnlyShowIn"] == null || names("OnlyShowIn").any { it in desktops }) &&
			names("NotShowIn").none { it in desktops }

	return Installed(
		Application(
			id = id,
			name = said["Name"] ?: id.removeSuffix(".desktop"),
			types = itemsOf(keys["MimeType"]).map(::MimeType).toSet(),
			command = command,
			inTerminal = keys["Terminal"] == "true",
			icon = keys["Icon"]?.takeIf { it.isNotEmpty() },
			source = file,
			generic = said["GenericName"],
			comment = said["Comment"],
			keywords = itemsOf(said["Keywords"]),
			actions = actions,
			wmClass = keys["StartupWMClass"]?.takeIf { it.isNotEmpty() },
		),
		shown = here && keys["NoDisplay"] != "true",
	)
}

/**
 * What a group's [keys] say in words, in the first of [languages] they say it in, or in none (`Name[de]`, `Name`);
 * a key that says nothing is one that is not there.
 */
private class Said(
	private val keys: Map<String, String>,
	private val languages: List<String>,
) {
	operator fun get(key: String): String? =
		(languages.map { "$key[$it]" } + key).firstNotNullOfOrNull { name ->
			keys[name]?.takeIf { it.isNotEmpty() }
		}
}

/** The group every desktop entry has, which says what it is. */
private const val ENTRY = "Desktop Entry"

/** An `Exec` line: split into words, and its field codes filled in. */
object Exec {
	/**
	 * [line] as its words, as the specification quotes them: a word in double quotes keeps its spaces, and in it `\"`,
	 * `` \` ``, `\$` and `\\` are those characters. Null for a line that leaves a quote open.
	 */
	fun split(line: String): List<String>? {
		val words = ArrayList<String>()
		val word = StringBuilder()
		var quoted = false
		var started = false
		var at = 0

		while (at < line.length) {
			val char = line[at]

			when {
				quoted && char == '\\' && at + 1 < line.length && line[at + 1] in "\"`$\\" -> {
					word.append(line[at + 1])
					at++
				}
				char == '"' -> {
					quoted = !quoted
					started = true
				}
				!quoted && char == ' ' -> {
					if (started || word.isNotEmpty())
						words += word.toString()
					word.clear()
					started = false
				}
				else -> word.append(char)
			}
			at++
		}

		if (quoted)
			return null
		if (started || word.isNotEmpty())
			words += word.toString()

		return words
	}

	/**
	 * The commands [command] (an application's words, with field codes) makes to open [paths]: one with them all where
	 * it takes a list (`%F`, `%U`), or one each where it takes one (`%f`, `%u`); one with none where it takes none.
	 * `%i` is the icon, `%c` the name, `%k` the entry's path; the deprecated codes are dropped.
	 */
	fun expand(
		command: List<String>,
		paths: List<Path>,
		application: Application,
	): List<List<String>> {
		val takesMany = command.any { it == "%F" || it == "%U" }
		val takesOne = command.any { "%f" in it || "%u" in it }
		val groups =
			when {
				takesMany -> listOf(paths)
				takesOne -> paths.map { listOf(it) }.ifEmpty { listOf(emptyList()) }
				else -> listOf(emptyList())
			}

		return groups.map { files ->
			command.flatMap { word ->
				expanded(
					word,
					files,
					application,
				)
			}
		}
	}

	private fun expanded(
		word: String,
		files: List<Path>,
		application: Application,
	): List<String> =
		when (word) {
			"%F" -> files.map { it.toString() }
			"%U" -> files.map { it.toUri().toString() }
			"%i" -> application.icon?.let { listOf("--icon", it) }.orEmpty()
			else ->
				if (word.contains('%'))
					listOfNotNull(
						fieldCodes(word, files.firstOrNull(), application).takeUnless {
							it.isEmpty() && (word == "%f" || word == "%u")
						},
					)
				else
					listOf(word)
		}

	private fun fieldCodes(word: String, file: Path?, application: Application): String =
		buildString {
			var at = 0

			while (at < word.length) {
				if (word[at] == '%' && at + 1 < word.length) {
					when (word[at + 1]) {
						'f' -> append(file?.toString().orEmpty())
						'u' -> append(file?.toUri()?.toString().orEmpty())
						'c' -> append(application.name)
						'k' -> append(application.source?.toString().orEmpty())
						'%' -> append('%')
						// %d, %D, %n, %N, %v, %m are deprecated, and dropped.
						else -> Unit
					}
					at += 2
				} else {
					append(word[at])
					at++
				}
			}
		}
}
