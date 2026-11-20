package prism.index

import kotlin.time.Duration

/*
 * How an index is kept as the disk changes, as whoever shows it says it: what follows the changes as they happen,
 * or what is done instead, or that nothing is. The daemon says it of itself ([prism.daemon.Daemon.keeping]); the
 * preferences' Indexing page shows it.
 */

/** What follows the changes to the disk as they happen. */
enum class FeedKind {
	/** The eBPF probe, in Linux's kernel. */
	EbpfProbe,

	/** macOS's FSEvents. */
	FsEvents,

	/** Something else: a test's. */
	Other,
}

/** Why nothing follows the changes as they happen. */
sealed interface Unfollowed {
	/** The user turned it off. */
	data object TurnedOff : Unfollowed

	/** It could not be started, or stopped: [why], in words. */
	data class Failed(
		val why: String,
	) : Unfollowed
}

/** How an index is kept as the disk changes. */
sealed interface Keeping {
	/** Each change is made as it happens, as [by] hears it, [through] what (a pinned probe, the process's own). */
	data class Followed(
		val by: FeedKind,
		val through: String,
	) : Keeping

	/** Nothing follows the changes ([why]), so the index's folders are read again [every] so often. */
	data class Rescanned(
		val why: Unfollowed,
		val every: Duration,
	) : Keeping

	/** Nothing follows the changes ([why]), and nothing reads the folders again: the index changes when it is rebuilt. */
	data class Unkept(
		val why: Unfollowed,
	) : Keeping
}
