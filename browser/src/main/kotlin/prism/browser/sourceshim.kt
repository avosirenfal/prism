package prism.browser

import kotlinx.coroutines.flow.StateFlow
import prism.preferences.Preferences
import prism.shared.Stored

/*
 * Two names source.kt still says, kept until it can be changed (it is being rewritten elsewhere as this is written):
 * with them it compiles as it is. They are to go, with this file, once source.kt
 *
 * - imports `prism.preferences.preferences`, and
 * - says `update { arranging(…) }` and `update { folding(…) }` where it says `update { it.arranging(…) }` and
 *   `update { it.folding(…) }`: a change to a stored value is given the value as its receiver.
 */

/** [prism.preferences.preferences], for the one file that does not import it. */
internal val Stored<Preferences>.preferences: StateFlow<Preferences> get() = value

/** The preferences a change is made to, by the name a change's lambda used to have for them. */
internal val Preferences.it: Preferences get() = this
