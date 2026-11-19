// Parser combinators: grammars made of small parsers, each a function from a text and a position to a value and the
// position after it, or to where and why it did not match. What the search language is read by (index), and the
// calculator's language (calc). Knows nothing of either.
plugins {
	id("prism.kotlin-jvm")
}
