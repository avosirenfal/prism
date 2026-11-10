package prism.desktop.freedesktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import prism.desktop.Application
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

/** A `;`-separated list's items, `\;` being a `;` inside one. */
fun itemsOf(value: String?): List<String> =
	value
		?.split(Regex("(?<!\\\\);"))
		?.map { it.replace("\\;", ";").trim() }
		?.filter { it.isNotEmpty() }
		.orEmpty()

/**
 * The application a desktop entry says, known by [id]; null for an entry that is not an application, or is hidden
 * from the user (`NoDisplay`, `Hidden`), or has nothing to run.
 */
fun applicationOf(id: String, file: Path, entry: KeyFile): Application? {
	val keys = entry["Desktop Entry"]

	if (keys["Type"] != "Application" || keys["Hidden"] == "true")
		return null

	val command = Exec.split(keys["Exec"] ?: return null) ?: return null

	if (command.isEmpty())
		return null

	return Application(
		id = id,
		name = keys["Name"] ?: id.removeSuffix(".desktop"),
		types = itemsOf(keys["MimeType"]).map(::MimeType).toSet(),
		command = command,
		inTerminal = keys["Terminal"] == "true",
		icon = keys["Icon"],
		source = file,
	)
}

/** Whether a desktop entry says to leave it out of what a person chooses from (an entry for a service, say). */
fun hiddenFromMenus(entry: KeyFile): Boolean =
	entry["Desktop Entry"]["NoDisplay"] == "true"

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
