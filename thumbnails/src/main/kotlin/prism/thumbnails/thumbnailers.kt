package prism.thumbnails

import java.io.IOException
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * The programs that make thumbnails. One is a command line and the files it is for, by the extensions of their names
 * and by their MIME types; any number can be declared in the preferences, for whatever prism does not picture itself
 * (a PDF's first page, an e-book's cover, a font's letters):
 *
 *     { "id": "pdf", "name": "PDF pages", "command": ["pdftoppm", "-png", "-singlefile", "-scale-to", "{size}", "{input}"],
 *       "extensions": ["pdf"], "types": ["application/pdf"] }
 *
 * In a command, `{input}` is the file's path and `{uri}` its address (for programs that take one), `{size}` how many
 * pixels across the picture is to be at most, and `{output}` a file for the program to write the picture to. A command
 * with no `{output}` prints the picture instead. Whatever picture comes of it, of any format that is decoded, is made
 * a PNG no larger than was asked: a program that writes a JPEG, or a picture larger than it was told, is as good.
 *
 * ffmpeg is one more, and prism's own: it is found on the PATH, what it is for is what that ffmpeg says it reads
 * (ffmpeg.kt), and its command is prism's to write. It can be turned off as any other can, and nothing else of it
 * changed.
 *
 * For a file, each program that is for it and is on is tried in turn, the user's first, in the order they are
 * declared, and ffmpeg last: so a program declared for `mkv` is tried before ffmpeg, which is still tried if it fails.
 */

/** What a command's word has in place of something only known as it is run. */
enum class Slot(
	val written: String,
	/** Whether a command the user writes may have it. */
	val declared: Boolean = true,
) {
	/** The file's path. */
	Input("{input}"),

	/** The file's address, `file:///…`, for a program that takes that. */
	Uri("{uri}"),

	/** A file the program is to write the picture to. */
	Output("{output}"),

	/** How many pixels across the picture is to be, at most. */
	Size("{size}"),

	/** How far into a film the picture is taken from, in seconds: ffmpeg's own. */
	Seek("{seek}", declared = false),
}

/** A piece of a word: text as it is, or a slot. */
sealed interface Piece {
	data class Text(
		val text: String,
	) : Piece

	data class Filled(
		val slot: Slot,
	) : Piece
}

/**
 * A command line with slots in it: its words, each the pieces it is made of. It is made only by [read], which
 * refuses what could not be run, so one that exists has a program to run and a file to run it on.
 */
class Template private constructor(
	val words: List<List<Piece>>,
) {
	/** Whether the program writes its picture to a file it is given, rather than printing it. */
	val writesFile: Boolean get() = Slot.Output in slots

	private val slots: Set<Slot>
		get() =
			words
				.flatten()
				.filterIsInstance<Piece.Filled>()
				.map { it.slot }
				.toSet()

	/** The words to run, each slot filled with what [filling] gives for it. */
	fun filled(filling: (Slot) -> String): List<String> =
		words.map { word ->
			word.joinToString("") {
				when (it) {
					is Piece.Text -> it.text
					is Piece.Filled -> filling(it.slot)
				}
			}
		}

	/** The command as it is written: what [read] reads back as this. */
	val written: List<String> get() = filled { it.written }

	override fun equals(other: Any?): Boolean = other is Template && other.words == words

	override fun hashCode(): Int = words.hashCode()

	override fun toString(): String = written.joinToString(" ")

	/** What reading a command came to. */
	sealed interface Read {
		data class Fit(
			val template: Template,
		) : Read

		/** Not a command that could be run, and [why]. */
		data class Unfit(
			val why: String,
		) : Read
	}

	companion object {
		private val SLOT = Regex("\\{([a-z]*)}")

		/**
		 * [words] as a command: fit where there is a program to run, every `{…}` in it is a slot there is, and it names
		 * the file it is to picture (`{input}` or `{uri}`). [own] is prism's own command, which may have the slots only
		 * prism fills.
		 */
		fun read(words: List<String>, own: Boolean = false): Read {
			if (words.isEmpty() || words.first().isBlank())
				return Read.Unfit("there is no program to run")

			val read =
				words.map { word ->
					val pieces = ArrayList<Piece>()
					var at = 0

					for (match in SLOT.findAll(word)) {
						val slot =
							Slot.entries.firstOrNull { it.written == match.value && (own || it.declared) }
								?: return Read.Unfit(
									"${match.value} is not something a command can be given: " +
										Slot.entries.filter { it.declared }.joinToString(", ") { it.written },
								)

						if (match.range.first > at)
							pieces += Piece.Text(word.substring(at, match.range.first))
						pieces += Piece.Filled(slot)
						at = match.range.last + 1
					}
					if (at < word.length)
						pieces += Piece.Text(word.substring(at))
					pieces.toList()
				}

			val template = Template(read)

			return when {
				read.first().any { it is Piece.Filled } ->
					Read.Unfit("the program to run cannot be a placeholder")
				Slot.Input !in template.slots && Slot.Uri !in template.slots ->
					Read.Unfit("the command does not say which file: it needs {input} or {uri}")
				else -> Read.Fit(template)
			}
		}

		/** One of prism's own commands, which are written to be fit. */
		internal fun own(vararg words: String): Template =
			when (val read = read(words.toList(), own = true)) {
				is Read.Fit -> read.template
				is Read.Unfit -> error("${words.toList()}: ${read.why}")
			}
	}
}

/** The files a program is for: by the extensions of their names, and by their MIME types. */
data class Handles(
	/** In lower case, without the dot. */
	val extensions: Set<String> = emptySet(),
	/** `application/pdf`; or every type of a kind, `video/` and a star. */
	val types: Set<String> = emptySet(),
) {
	/** Whether a file named with [extension] is one, without its type being asked. */
	fun byName(extension: String): Boolean = extension in extensions

	/** Whether a file of [type] is one. */
	fun byType(type: String): Boolean =
		type in types || "${type.substringBefore('/')}/*" in types

	/** Whether a file named with [extension] is one; its [type] asked only where its name does not say. */
	fun handles(extension: String, type: () -> String?): Boolean =
		byName(extension) || (types.isNotEmpty() && type()?.let(::byType) == true)
}

/** A program that makes thumbnails, as the preferences write one. */
@Serializable
data class ThumbnailerSpec(
	/** What it is known by, where it is turned off: its own, among the user's. */
	val id: String,
	/** What it is called to a person; its [id], where it has no other name. */
	val name: String? = null,
	/** Its command line, a word each, with slots ([Slot]). */
	val command: List<String>,
	val extensions: Set<String> = emptySet(),
	val types: Set<String> = emptySet(),
	/** How long it is given to make one picture, in seconds. */
	val seconds: Int = Thumbnailer.LIMIT.inWholeSeconds.toInt(),
)

/** A program that makes thumbnails. */
sealed interface Thumbnailer {
	val id: String
	val name: String
	val handles: Handles

	/** How long it is given to make one picture. */
	val limit: Duration

	/** One the user declared: its [command], run on each file it [handles]. */
	data class Program(
		override val id: String,
		override val name: String,
		val command: Template,
		override val handles: Handles,
		override val limit: Duration = LIMIT,
	) : Thumbnailer

	/** ffmpeg, as it was [found]: what it is for is what that ffmpeg reads, and its commands are prism's own. */
	data class Ffmpeg(
		val found: prism.thumbnails.Ffmpeg,
	) : Thumbnailer {
		override val id get() = FFMPEG
		override val name get() = "ffmpeg"
		override val handles get() = found.handles
		override val limit get() = LIMIT
	}

	companion object {
		/** ffmpeg's id, which no other program may have. */
		const val FFMPEG = "ffmpeg"

		/** How long a program is given unless it says: long enough for a film on a slow disk, and no hour. */
		val LIMIT: Duration = 20.seconds

		private val json = Json { ignoreUnknownKeys = true }

		/** [written], one of the preferences' programs, read: the program, or why it is none. */
		fun read(written: JsonElement): Result<Program> =
			try {
				val spec = json.decodeFromJsonElement(ThumbnailerSpec.serializer(), written)

				when (val command = Template.read(spec.command)) {
					is Template.Read.Unfit -> wrong(spec.id, command.why)
					is Template.Read.Fit ->
						when {
							spec.id.isBlank() -> wrong("a thumbnailer", "it has no id")
							spec.id == FFMPEG -> wrong(spec.id, "that id is ffmpeg's own")
							spec.extensions.isEmpty() && spec.types.isEmpty() ->
								wrong(spec.id, "it names no extensions and no types to be for")
							spec.seconds < 1 -> wrong(spec.id, "it is given no time to run")
							else ->
								Result.success(
									Program(
										spec.id,
										spec.name ?: spec.id,
										command.template,
										Handles(
											spec.extensions.map { it.removePrefix(".").lowercase() }.toSet(),
											spec.types.map { it.lowercase() }.toSet(),
										),
										spec.seconds.seconds,
									),
								)
						}
				}
			} catch (unread: SerializationException) {
				wrong("a thumbnailer", unread.message ?: unread.toString())
			} catch (unread: IllegalArgumentException) {
				wrong("a thumbnailer", unread.message ?: unread.toString())
			}

		private fun wrong(what: String, why: String): Result<Program> =
			Result.failure(IllegalArgumentException("$what: $why"))

		/** [program] as the preferences write it. */
		fun written(program: Program): JsonElement =
			json.encodeToJsonElement(
				ThumbnailerSpec.serializer(),
				ThumbnailerSpec(
					program.id,
					program.name.takeIf { it != program.id },
					program.command.written,
					program.handles.extensions,
					program.handles.types,
					program.limit.inWholeSeconds.toInt(),
				),
			)
	}
}

/**
 * The programs there are to make thumbnails with, in the order they are tried ([all]), which of them are turned
 * [off], and what was declared that is no program, and why ([broken]).
 */
data class Thumbnailers(
	val all: List<Thumbnailer>,
	val off: Set<String> = emptySet(),
	val broken: List<String> = emptyList(),
) {
	/** Those that are on. */
	val on: List<Thumbnailer> get() = all.filter { it.id !in off }

	/** The programs tried for a file named with [extension], in order; its [type] asked where a name does not say. */
	fun forFile(extension: String, type: () -> String?): List<Thumbnailer> {
		// Asked once, however many programs ask, and not at all where every one that is for it says so by its name.
		val asked = lazy(type)

		return on.filter { it.handles.handles(extension) { asked.value } }
	}

	/** Whether any program may be for a file named with [extension]: it is by name, or some program goes by type. */
	fun mayHandle(extension: String): Boolean =
		on.any { it.handles.byName(extension) || it.handles.types.isNotEmpty() }

	companion object {
		val NONE = Thumbnailers(emptyList())

		/**
		 * The programs [declared] in the preferences, each read on its own (so that one written wrong does not lose the
		 * rest, and is said in [broken]), then [ffmpeg], where there is one; those named in [off] turned off. Of two
		 * declared with one id, the first is the program.
		 */
		fun of(declared: List<JsonElement>, off: Set<String>, ffmpeg: Ffmpeg?): Thumbnailers {
			val read = declared.map(Thumbnailer::read)
			val programs = read.mapNotNull { it.getOrNull() }
			val twice = programs.groupBy { it.id }.filterValues { it.size > 1 }.keys

			return Thumbnailers(
				programs.distinctBy { it.id } + listOfNotNull(ffmpeg?.let(Thumbnailer::Ffmpeg)),
				off,
				read.mapNotNull { it.exceptionOrNull()?.message } +
					twice.map { "$it: declared more than once; the first is used" },
			)
		}
	}
}

/**
 * [subject]'s picture, [side] across at most, made by this program run by [processes]: what it printed or wrote,
 * made a PNG of that size by [pictures] where it is not one already.
 */
suspend fun Thumbnailer.run(
	subject: Subject,
	side: Int,
	processes: Processes,
	pictures: Pictures,
): Made =
	when (this) {
		is Thumbnailer.Ffmpeg -> found.picture(subject, side, processes, limit)
		is Thumbnailer.Program -> {
			val output =
				if (command.writesFile)
					try {
						Files.createTempFile("prism-thumbnail", ".png")
					} catch (failed: IOException) {
						null
					}
				else
					null

			try {
				val words =
					command.filled {
						when (it) {
							Slot.Input -> subject.file.toString()
							Slot.Uri -> subject.uri
							Slot.Size -> "$side"
							Slot.Output -> output.toString()
							Slot.Seek -> "0"
						}
					}

				when (val ran = processes.run(words, limit, Processes.MOST)) {
					is Ran.Missing -> Made.Not("${words.first()} is not installed")
					is Ran.Killed -> Made.Failed(ran.why)
					is Ran.Ended ->
						when {
							command.writesFile && output == null ->
								Made.Not("there was nowhere to have it write to")
							ran.exit != 0 -> Made.Failed(saidOf(ran))
							else ->
								pictureOf(output?.let(::readOrNull) ?: ran.out, side, pictures)
						}
				}
			} finally {
				output?.let { runCatching { Files.deleteIfExists(it) } }
			}
		}
	}

private fun readOrNull(file: java.nio.file.Path): ByteArray? =
	try {
		Files.readAllBytes(file).takeIf { it.size <= Processes.MOST }
	} catch (failed: IOException) {
		null
	}

/** What a program that ended badly said, in a line: its last words, or how it ended where it said nothing. */
internal fun saidOf(ran: Ran.Ended): String =
	ran.err
		.lineSequence()
		.map { it.trim() }
		.lastOrNull { it.isNotEmpty() }
		?: "it ended with ${ran.exit}"

/**
 * What a program made, as a picture [side] across at most: its PNG as it is where it is one that fits, and otherwise
 * whatever picture it is, decoded and made one that does.
 */
internal fun pictureOf(made: ByteArray, side: Int, pictures: Pictures): Made =
	when {
		made.isEmpty() -> Made.Failed("it made no picture")
		else ->
			(
				Picture.of(made)?.takeIf { it.extent.within(side) }
					?: pictures.png(Encoded.Bytes(made), side)
			)?.let(Made::Pictured)
				?: Made.Failed("what it made is not a picture that could be read")
	}
