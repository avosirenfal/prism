package prism.desktop.freedesktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import prism.desktop.FileTypes
import prism.desktop.MimeType

/*
 * File types as shared-mime-info says them: the database update-mime-database writes into each data folder's `mime/`.
 * A file's type is found by its name first, as the specification says a desktop should: a literal name (`Makefile`),
 * then the longest extension that names one (`.tar.gz` before `.gz`), then any other pattern, the heaviest weight
 * winning among those that match. A name that matches nothing is looked at: an empty file is empty, one that reads as
 * text is text, and the rest are bytes. (The database's magic, which tells types apart by their first bytes, is not
 * read yet: a name says enough nearly always.)
 */

/** shared-mime-info's database, from the `mime/` folder of each of [Xdg.data]. */
class SharedMimeInfo(
	private val xdg: Xdg,
) : FileTypes {
	/** One pattern of `globs2`: its weight, its type, and whether case counts. */
	private class Glob(
		val weight: Int,
		val type: MimeType,
		val pattern: String,
		val caseSensitive: Boolean,
	) {
		val regex: Regex by lazy {
			Regex(
				pattern
					.map {
						when (it) {
							'*' -> ".*"
							'?' -> "."
							else -> Regex.escape(it.toString())
						}
					}.joinToString(""),
				if (caseSensitive)
					emptySet()
				else
					setOf(RegexOption.IGNORE_CASE),
			)
		}
	}

	/** The database read: literal names, extensions, other patterns, aliases and parents. */
	private class Database(
		val literals: Map<String, Glob>,
		/** Extensions where case counts, as they are written; and the rest, in lower case. */
		val exactExtensions: Map<String, Glob>,
		val extensions: Map<String, Glob>,
		val others: List<Glob>,
		val aliases: Map<MimeType, MimeType>,
		val parents: Map<MimeType, List<MimeType>>,
	)

	private val database: Database by lazy { read() }
	private val descriptions = ConcurrentHashMap<MimeType, String>()

	private fun read(): Database {
		// The user's folder first: a type it defines takes the place of the system's.
		val folders = xdg.data.map { it.resolve("mime") }

		val globs =
			folders.reversed().flatMap { folder ->
				lines(folder.resolve("globs2")).mapNotNull { line ->
					val parts = line.split(':')

					if (parts.size < 3)
						null
					else
						Glob(
							parts[0].toIntOrNull() ?: 50,
							MimeType(parts[1]),
							parts[2],
							parts.getOrNull(3)?.split(',')?.contains("cs") == true,
						)
				}
			}

		val literal = globs.filter { it.pattern.none { c -> c in "*?[" } }
		val extension =
			globs.filter {
				it.pattern.startsWith("*.") && it.pattern.drop(2).none { c -> c in "*?[" }
			}

		return Database(
			literals =
				literal.groupBy { it.pattern }.mapValues { (_, it) ->
					it.maxBy(Glob::weight)
				},
			exactExtensions =
				extension
					.filter { it.caseSensitive }
					.groupBy { it.pattern.drop(2) }
					.mapValues { (_, it) -> it.maxBy(Glob::weight) },
			extensions =
				extension
					.filterNot { it.caseSensitive }
					.groupBy { it.pattern.drop(2).lowercase(Locale.ROOT) }
					.mapValues { (_, it) -> it.maxBy(Glob::weight) },
			others =
				(globs - literal.toSet() - extension.toSet()).sortedByDescending {
					it.weight
				},
			aliases =
				folders
					.reversed()
					.flatMap { lines(it.resolve("aliases")) }
					.mapNotNull { it.split(' ').takeIf { p -> p.size == 2 } }
					.associate { (alias, type) -> MimeType(alias) to MimeType(type) },
			parents =
				folders
					.reversed()
					.flatMap { lines(it.resolve("subclasses")) }
					.mapNotNull { it.split(' ').takeIf { p -> p.size == 2 } }
					.groupBy({ MimeType(it[0]) }, { MimeType(it[1]) }),
		)
	}

	override fun of(path: Path): MimeType {
		if (Files.isDirectory(path))
			return MimeType.FOLDER

		return byName(path.fileName?.toString().orEmpty())?.let(::canonical) ?: byContent(path)
	}

	/** The type [name] says, if it says one. */
	fun byName(name: String): MimeType? {
		database.literals[name]?.let { return it.type }

		// Each extension from the longest: `a.tar.gz` is `tar.gz` before it is `gz`.
		val dots = name.indices.filter { name[it] == '.' && it > 0 }

		for (dot in dots) {
			val extension = name.substring(dot + 1)
			val glob =
				database.exactExtensions[extension]
					?: database.extensions[extension.lowercase(Locale.ROOT)]

			if (glob != null)
				return glob.type
		}

		return database.others.firstOrNull { it.regex.matches(name) }?.type
	}

	/** An unnamed type by its bytes: empty, text (UTF-8 with no NUL in its first bytes), or bytes. */
	private fun byContent(path: Path): MimeType =
		try {
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
				!Files.isRegularFile(path)
			)
				MimeType.BINARY
			else
				Files.newInputStream(path).use { input ->
					val head = input.readNBytes(1024)

					when {
						head.isEmpty() -> MimeType.EMPTY
						looksLikeText(head) -> MimeType.TEXT
						else -> MimeType.BINARY
					}
				}
		} catch (failed: IOException) {
			MimeType.BINARY
		} catch (denied: SecurityException) {
			MimeType.BINARY
		}

	override fun patternsOf(type: MimeType): List<String> {
		val wanted = canonical(type)
		val every = wanted.name.endsWith("/*")
		val all =
			(
				database.literals.values + database.exactExtensions.values +
					database.extensions.values +
					database.others
			)

		return all
			.filter { glob ->
				if (every)
					glob.type.media == wanted.media
				else
					wanted in lineage(glob.type)
			}.map { it.pattern }
			.distinct()
	}

	/** [type] by its own name rather than an alias. */
	fun canonical(type: MimeType): MimeType = database.aliases[type] ?: type

	override fun lineage(type: MimeType): List<MimeType> {
		val start = canonical(type)
		val seen = LinkedHashSet<MimeType>()
		val queue = ArrayDeque(listOf(start))

		while (queue.isNotEmpty()) {
			val next = queue.removeFirst()

			if (seen.add(next))
				queue.addAll(database.parents[next].orEmpty().map(::canonical))
		}

		// Every text is plain text, and every file is bytes, the specification says, said or not.
		if (start.media == "text")
			seen += MimeType.TEXT
		if (start != MimeType.FOLDER)
			seen += MimeType.BINARY

		return seen.toList()
	}

	override fun describe(type: MimeType): String? =
		descriptions[type] ?: xdg.data
			.asSequence()
			.map { it.resolve("mime/${canonical(type).name}.xml") }
			.firstNotNullOfOrNull(::commentIn)
			?.also { descriptions[type] = it }

	/** The `<comment>` with no language: the type's name in English. */
	private fun commentIn(file: Path): String? =
		try {
			Regex(
				"<comment>([^<]*)</comment>",
			).find(Files.readString(file))?.groupValues?.get(1)?.let(::unescaped)
		} catch (failed: IOException) {
			null
		}

	private fun unescaped(text: String) =
		text
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&quot;", "\"")
			.replace("&apos;", "'")
			.replace("&amp;", "&")

	private fun lines(file: Path): List<String> =
		try {
			Files.readAllLines(file).filter { it.isNotBlank() && !it.startsWith("#") }
		} catch (failed: IOException) {
			emptyList()
		}

	private companion object {
		/** Whether [bytes] read as text: no NUL, and valid UTF-8 but perhaps for a character cut at the end. */
		fun looksLikeText(bytes: ByteArray): Boolean {
			if (bytes.any { it == 0.toByte() })
				return false

			val decoder =
				Charsets.UTF_8
					.newDecoder()
					.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
					.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)

			// A character can be cut at the end of what was read: up to three bytes are let go before deciding.
			return (0..3).any { cut ->
				try {
					decoder.reset().decode(
						java.nio.ByteBuffer.wrap(bytes, 0, (bytes.size - cut).coerceAtLeast(0)),
					)
					true
				} catch (wrong: java.nio.charset.CharacterCodingException) {
					false
				}
			}
		}
	}
}
