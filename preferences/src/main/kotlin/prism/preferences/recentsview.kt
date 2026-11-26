package prism.preferences

import kotlinx.serialization.Serializable

/*
 * How the Recents page is shown, in every window: as tabs, one section at a time, or as groups under headings; and
 * how its "Most relevant folders" are found, which the page's tuning sets, and which is kept here so that it is set
 * once and holds everywhere.
 */

/** How the Recents page is shown: as tabs ([tabbed]) or as groups, and how its most relevant folders are found. */
@Serializable
data class RecentsView(
	val tabbed: Boolean = true,
	val relevance: Relevance = Relevance(),
)

/**
 * How a folder's relevance to a picker is weighed, from what the page knows of it. Three things, each from nothing
 * to one, are each multiplied by its weight ([recency], [frequency], [application]) and added up:
 *
 * - how lately it was used, in any way: one for a folder used now, and half as much every [recencyHalfLifeHours]
 *   since;
 * - how much it is gone to and picked from, by anyone, as the folders' uses are counted (each use fading by half in
 *   two weeks): one for the most used of the candidates, and less for the rest, on a scale of logarithms;
 * - how much the application asking uses it: the files it saved there (for a picker to save) or opened from there
 *   (for one to open), and the folder itself, picked, each fading as uses do; one for the most, as above.
 *
 * The [shown] that score most are the page's most relevant folders.
 */
@Serializable
data class Relevance(
	val recency: Double = 1.0,
	val frequency: Double = 0.6,
	val application: Double = 0.8,
	val recencyHalfLifeHours: Double = 48.0,
	val shown: Int = 8,
)
