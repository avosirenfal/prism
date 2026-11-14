package prism.index.query

/**
 * The search language in short, for whoever is typing a query and wonders how: a sentence or two on how a query is
 * read, and a handful of queries, each with what it finds. What the picker's search shows when asked ([EXAMPLES] are
 * read by the language's own tests, so none can come to mean something else unnoticed); docs/search.md is the whole
 * of it.
 */
object QueryHelp {
	const val SUMMARY =
		"Words are separated by spaces, and a result has to fit every one of them. " +
			"A plain word is looked for in a name without its extension; " +
			"a word with a dot in it, in the whole name."

	/** A query, and what it finds, in a few words. */
	data class Example(
		val text: String,
		val finds: String,
	)

	val EXAMPLES: List<Example> =
		listOf(
			Example("report", "names with “report” in them, the extension aside"),
			Example(".pdf", "the extension .pdf, and hidden names that start so"),
			Example("report .pdf", "both: PDFs with “report” in their names"),
			Example("^report", "names that start with it"),
			Example("report$", "names that end with it, the extension aside"),
			Example("=report", "names that are exactly it, whatever their extension"),
			Example("*.{jpg,png}", "a glob: * is anything, ? one character, {a,b} either"),
			Example("\"tax return\"", "words with a space between them"),
			Example("-draft", "not: without “draft” in the name"),
			Example("~/Documents/", "everything under a folder"),
			Example("photos/*.jpg", "directly inside any folder named photos"),
			Example("dir:photos", "anywhere under a folder named photos"),
			Example(
				"type:image",
				"by kind: ${Group.entries.joinToString(", ") { it.name.lowercase() }}",
			),
			Example("is:dir", "folders only"),
			Example("is:file -is:hidden", "files only, and none of the hidden ones"),
			Example("new:2d", "what turned up in the last two days"),
			Example("recent:1w", "what you picked in the last week"),
			Example("r/^img_\\d{4}/", "a regular expression"),
		)
}
