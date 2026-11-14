package prism.index.query

/**
 * The search language in short, for whoever is typing a query and wonders how: a sentence or two on how a query is
 * read, and a handful of queries, each with what it finds. What the picker's search shows when asked ([EXAMPLES] are
 * read by the language's own tests, so none can come to mean something else unnoticed); docs/search.md is the whole
 * of it.
 */
object QueryHelp {
	const val SUMMARY =
		"Separate terms with spaces; results match every term. " +
			"A term without a dot matches the name without its extension, " +
			"a term starting with a dot matches the extension, " +
			"and a term with a dot elsewhere matches the whole name."

	/** A query, and what it finds, in a few words. */
	data class Example(
		val text: String,
		val finds: String,
	)

	val EXAMPLES: List<Example> =
		listOf(
			Example("report", "names containing “report” (extension ignored)"),
			Example(".pdf", "files with the .pdf extension, or hidden files starting with .pdf"),
			Example("report .pdf", "PDFs with “report” in the name"),
			Example("^report", "names starting with “report”"),
			Example("report$", "names ending with “report” (extension ignored)"),
			Example("=report", "names that are exactly “report”, with any extension"),
			Example("img_*.{jpg,png}", "wildcards: * any text, ? one character, {a,b} either one"),
			Example("\"tax return\"", "a phrase containing spaces"),
			Example("-draft", "exclude names containing “draft”"),
			Example("~/Documents/", "everything inside a folder"),
			Example("photos/*.jpg", "JPEGs directly inside any folder named photos"),
			Example("dir:photos", "anything inside a folder named photos, at any depth"),
			Example(
				"type:image",
				"by type: ${Group.entries.joinToString(", ") { it.name.lowercase() }}",
			),
			Example("is:dir", "folders only"),
			Example("is:file -is:hidden", "files only, excluding hidden ones"),
			Example("new:2d", "added in the last two days"),
			Example("recent:1w", "opened with prism in the last week"),
			Example("r/^img_\\d{4}/", "a regular expression"),
			Example(Own.WORD, "prism's settings, key bindings and System page"),
		)
}
