package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.ADDRESS
import java.nio.file.Path

/*
 * What kind of file a file is, as macOS says it: a uniform type identifier (`public.png`), which names a type and the
 * types it conforms to (`public.image`, `public.data`), and which may have a MIME type (`image/png`) and file name
 * extensions. Asked of UniformTypeIdentifiers' `UTType`, and for a file itself of its URL (`NSURLContentTypeKey`),
 * which knows a folder, a package and an application from a file by more than its name.
 */

/** A uniform type, as Kotlin's values. */
data class UniformType(
	/** `public.png`. */
	val identifier: String,
	/** `image/png`, where it has one. */
	val mime: String?,
	/** "PNG image", in the user's language. */
	val description: String?,
	/** The extensions its files are named with, the preferred first: `png`. */
	val extensions: List<String>,
	/** Every type it conforms to, the nearest first: `public.image`, then `public.data`, then `public.item`. */
	val supertypes: List<String>,
) {
	/** Whether it is, or conforms to, [identifier]. */
	fun conformsTo(identifier: String): Boolean =
		identifier == this.identifier || identifier in supertypes
}

/** The uniform types the system knows. */
object UniformTypes {
	private val utType get() = ObjC.cls("UTType", Frameworks.uniformTypes)

	/** The type of the type identifier [identifier]; null where the system knows none. */
	fun ofIdentifier(identifier: String): UniformType? =
		ObjC.pooled {
			utType.send("typeWithIdentifier:", nsString(identifier)).orNull()?.let(::read)
		}

	/** The type of the MIME type [mime]; null where the system knows none. */
	fun ofMime(mime: String): UniformType? =
		ObjC.pooled { utType.send("typeWithMIMEType:", nsString(mime)).orNull()?.let(::read) }

	/** The type of files named with [extension]; null where the system knows none (it makes up a dynamic one, which says nothing). */
	fun ofExtension(extension: String): UniformType? =
		ObjC.pooled {
			utType
				.send("typeWithFilenameExtension:", nsString(extension))
				.orNull()
				?.takeUnless { it.bool("isDynamic") }
				?.let(::read)
		}

	/** The type of the file at [path], as its URL says (by what it is, then its name); null where it is not there. */
	fun ofFile(path: Path): UniformType? =
		ObjC.pooled {
			Arena.ofConfined().use { arena ->
				val value = arena.allocate(ADDRESS)

				val got =
					fileUrl(path).bool(
						"getResourceValue:forKey:error:",
						value,
						Frameworks.foundation.constant("NSURLContentTypeKey"),
						java.lang.foreign.MemorySegment.NULL,
					)

				Id(value.get(ADDRESS, 0L)).takeIf { got }?.orNull()?.let(::read)
			}
		}

	private fun read(type: Id): UniformType {
		// Every type it conforms to, unordered as the set has them: nearest first is the one that conforms to most.
		val supertypes =
			type
				.send("supertypes")
				.members()
				.map {
					it.send("identifier").text().orEmpty() to
						it.send("supertypes").integer("count")
				}.sortedByDescending { it.second }
				.map { it.first }

		return UniformType(
			identifier = type.send("identifier").text().orEmpty(),
			mime = type.send("preferredMIMEType").text(),
			description = type.send("localizedDescription").text(),
			extensions =
				type.send("tags")["public.filename-extension"].elements().mapNotNull {
					it.text()
				},
			supertypes = supertypes,
		)
	}
}
