package prism.browser

import prism.index.Extension

/*
 * What a file is, by its name: the category its icon is drawn for, and the words the type column says. One table,
 * read both ways, so an extension cannot be one category's icon and another's words.
 */

private class Typed(
	val category: Category,
	val type: String,
)

private val BY_EXTENSION: Map<String, Typed> =
	buildMap {
		fun add(category: Category, type: String, vararg extensions: String) =
			extensions.forEach { put(it, Typed(category, type)) }

		add(Category.Image, "PNG image", "png")
		add(Category.Image, "JPEG image", "jpg", "jpeg")
		add(Category.Image, "GIF image", "gif")
		add(Category.Image, "WebP image", "webp")
		add(Category.Image, "SVG image", "svg")
		add(Category.Image, "AVIF image", "avif")
		add(Category.Image, "HEIF image", "heic", "heif")
		add(Category.Audio, "MP3 audio", "mp3")
		add(Category.Audio, "FLAC audio", "flac")
		add(Category.Audio, "Ogg audio", "ogg", "opus")
		add(Category.Audio, "WAV audio", "wav")
		add(Category.Video, "MP4 video", "mp4", "m4v")
		add(Category.Video, "Matroska video", "mkv")
		add(Category.Video, "WebM video", "webm")
		add(Category.Video, "QuickTime video", "mov")
		add(Category.Archive, "ZIP archive", "zip")
		add(Category.Archive, "Tar archive", "tar")
		add(Category.Archive, "Gzip archive", "gz", "tgz")
		add(Category.Archive, "XZ archive", "xz")
		add(Category.Archive, "Zstandard archive", "zst")
		add(Category.Archive, "7-Zip archive", "7z")
		add(Category.Code, "Kotlin source", "kt", "kts")
		add(Category.Code, "Java source", "java")
		add(Category.Code, "Rust source", "rs")
		add(Category.Code, "C source", "c", "h")
		add(Category.Code, "C++ source", "cpp", "hpp", "cc")
		add(Category.Code, "Python script", "py")
		add(Category.Code, "Shell script", "sh", "bash", "zsh")
		add(Category.Code, "JavaScript", "js", "mjs")
		add(Category.Code, "TypeScript", "ts", "tsx")
		add(Category.Code, "Nix expression", "nix")
		add(Category.Code, "JSON", "json")
		add(Category.Code, "YAML", "yaml", "yml")
		add(Category.Code, "TOML", "toml")
		add(Category.Code, "HTML", "html", "htm")
		add(Category.Code, "CSS", "css")
		add(Category.Text, "Text", "txt")
		add(Category.Text, "Markdown", "md")
		add(Category.Text, "Log", "log")
		add(Category.Text, "CSV", "csv")
		add(Category.Document, "Word document", "docx", "doc")
		add(Category.Document, "OpenDocument text", "odt")
		add(Category.Document, "Spreadsheet", "xlsx", "ods")
		add(Category.Document, "Presentation", "pptx", "odp")
		add(Category.Pdf, "PDF document", "pdf")
	}

/** The extension of [name], lower case, or null for a name without one (a dotfile's leading dot is not one). */
fun extensionOf(name: String): String? =
	name.substringAfterLast('.', "").lowercase().takeIf {
		it.isNotEmpty() &&
			name.lastIndexOf('.') > 0
	}

/**
 * Each known extension's category, by the extension as a number ([Extension]): the same table as [BY_EXTENSION], laid
 * out to be looked up for every one of a million results of a search without a text made or a number boxed for any.
 */
private object ByPacked {
	private val sorted = BY_EXTENSION.entries.sortedBy { Extension.of("a.${it.key}").packed }
	private val extensions =
		LongArray(sorted.size) { Extension.of("a.${sorted[it].key}").packed }
	private val categories = Array(sorted.size) { sorted[it].value.category }

	init {
		// An extension too long to be a number would be one no name is ever found to have.
		check(
			extensions.none {
				it == Extension.NONE.packed
			},
		) { "an extension longer than ${Extension.LONGEST}" }
	}

	operator fun get(extension: Extension): Category {
		val at = extensions.binarySearch(extension.packed)

		return if (at >= 0)
			categories[at]
		else
			Category.Other
	}
}

/** What a file whose name has this [extension] is, for its icon: what [categoryOf] says of the name itself. */
fun categoryOf(extension: Extension): Category = ByPacked[extension]

/** What a file of this name is, for its icon. */
fun categoryOf(name: String): Category =
	extensionOf(name)?.let { BY_EXTENSION[it]?.category } ?: Category.Other

/** What the type column says of a file of this name in [category]. */
fun typeOf(name: String, category: Category): String =
	when (category) {
		Category.Folder -> "Folder"
		Category.Link -> "Link"
		else ->
			extensionOf(name)?.let { BY_EXTENSION[it]?.type ?: "${it.uppercase()} file" } ?: "File"
	}
