package prism.desktop.macos

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import prism.desktop.FileTypes
import prism.desktop.MimeType
import prism.macos.UniformType
import prism.macos.UniformTypes

/*
 * A file's type on macOS is a uniform type identifier, and prism's are MIME types, so each is said as the other: a
 * uniform type as its MIME type where it has one (`public.png` is `image/png`), and otherwise as one made of its
 * identifier under the kind of thing it is (`com.apple.keynote.key`, a presentation with none, is
 * `application/x-uti.com.apple.keynote.key`; a RAW photo's `image/x-uti.…`), which is read back the same way. What
 * the gallery and the filters ask of a type (that it is an image, a video) is so the same as on Linux.
 *
 * A type is asked by a file's extension, and kept: a folder of a thousand photographs asks once. A file whose name
 * says nothing (no extension) is asked of the file itself, which is slower, and read for text where that says nothing
 * either.
 */

/** File types as uniform type identifiers say them. */
class UniformFileTypes : FileTypes {
	private val byExtension = ConcurrentHashMap<String, Known>()
	private val byIdentifier = ConcurrentHashMap<String, Known>()

	/** A type asked for, or that the system knows none: kept either way. */
	private sealed interface Known {
		data class Of(
			val type: UniformType,
		) : Known

		data object None : Known
	}

	private fun known(type: UniformType?): Known = type?.let(Known::Of) ?: Known.None

	private fun ofExtension(extension: String): UniformType? =
		(
			byExtension.getOrPut(extension) {
				known(UniformTypes.ofExtension(extension))
			} as? Known.Of
		)?.type

	private fun ofIdentifier(identifier: String): UniformType? =
		(
			byIdentifier.getOrPut(identifier) {
				known(UniformTypes.ofIdentifier(identifier))
			} as? Known.Of
		)?.type

	override fun of(path: Path): MimeType {
		val extension = path.extension.lowercase()

		if (Files.isDirectory(path))
			// A package (an application, a document kept as a folder) is what its name says; any other folder is one.
			return extension
				.takeIf { it.isNotEmpty() }
				?.let(::ofExtension)
				?.takeIf { it.conformsTo(PACKAGE) }
				?.let(::mimeOf)
				?: MimeType.FOLDER

		return extension.takeIf { it.isNotEmpty() }?.let(::ofExtension)?.let(::mimeOf)
			?: UniformTypes.ofFile(path)?.takeUnless { it.identifier == DATA }?.let(::mimeOf)
			?: byContent(path)
	}

	override fun describe(type: MimeType): String? = uniformOf(type)?.description

	override fun lineage(type: MimeType): List<MimeType> =
		(
			listOf(type) +
				uniformOf(type)
					?.supertypes
					.orEmpty()
					.mapNotNull(::ofIdentifier)
					.filterNot { it.identifier in TOO_WIDE }
					.map(::mimeOf)
		).distinct()

	override fun patternsOf(type: MimeType): List<String> =
		uniformOf(type)?.extensions.orEmpty().map {
			"*.$it"
		}

	/** The uniform type [type] says: a made-up one's identifier, or the system's for its MIME type. */
	internal fun uniformOf(type: MimeType): UniformType? =
		when {
			type == MimeType.FOLDER -> ofIdentifier(FOLDER)
			type.name
				.substringAfter(
					'/',
				).startsWith(MADE_UP) -> ofIdentifier(type.name.substringAfter(MADE_UP))
			else -> UniformTypes.ofMime(type.name)?.let { ofIdentifier(it.identifier) ?: it }
		}

	/** What a file whose name says nothing holds: nothing, text (UTF-8, with no NUL in its start), or anything. */
	private fun byContent(path: Path): MimeType =
		try {
			val start = Files.newInputStream(path).use { it.readNBytes(SNIFFED) }

			when {
				start.isEmpty() -> MimeType.EMPTY
				0.toByte() !in start &&
					Charsets.UTF_8.newDecoder().let { decoder ->
						runCatching { decoder.decode(java.nio.ByteBuffer.wrap(start)) }.isSuccess
					}
				-> MimeType.TEXT
				else -> MimeType.BINARY
			}
		} catch (unreadable: IOException) {
			MimeType.BINARY
		}

	companion object {
		private const val PACKAGE = "com.apple.package"
		private const val FOLDER = "public.folder"
		private const val DATA = "public.data"
		private const val MADE_UP = "x-uti."
		private const val SNIFFED = 512

		/** What every file is (`public.item`, `public.data`, `public.content`): no help in telling one from another. */
		private val TOO_WIDE =
			setOf("public.item", "public.data", "public.content", "public.composite-content")

		/** The kinds of thing a type is, as the first word of a MIME type says them, by what it conforms to. */
		private val MEDIA =
			listOf(
				"public.image" to "image",
				"public.movie" to "video",
				"public.audio" to "audio",
				"public.text" to "text",
			)

		/** [type] as a MIME type: its own, or one made of its identifier under the kind of thing it is. */
		fun mimeOf(type: UniformType): MimeType =
			when {
				type.identifier == FOLDER || type.identifier == "public.directory" -> MimeType.FOLDER
				type.mime != null -> MimeType(type.mime!!)
				else -> {
					val media = MEDIA.firstOrNull { type.conformsTo(it.first) }?.second ?: "application"

					MimeType("$media/$MADE_UP${type.identifier}")
				}
			}
	}
}
