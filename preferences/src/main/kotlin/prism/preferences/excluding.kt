package prism.preferences

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a rule does with what it names: as the index's `Exclusion` is, kept here by its name. */
@Serializable
enum class Excluded {
	/** Not indexed at all. */
	@SerialName("ignored")
	Ignored,

	/** Indexed, and left out of what a search finds unless the search names where it is. */
	@SerialName("hidden")
	Hidden,
}

/**
 * What the index leaves out, as the user's differences from prism's own rules: so a rule prism comes to have later
 * is the user's too, and one it no longer has is forgotten. Each rule is kept as it is written (a path, or a query);
 * what reads them is the index's (`Exclusions.of`), which leaves out one that does not read.
 */
@Serializable
data class Excluding(
	/** Prism's own rules that are switched off. */
	val off: Set<String> = emptySet(),
	/** Prism's own rules that do something other than not index what they name. */
	val changed: Map<String, Excluded> = emptyMap(),
	/** The user's own rules, in the order they were added, and what each does. */
	val own: Map<String, Excluded> = emptyMap(),
)
