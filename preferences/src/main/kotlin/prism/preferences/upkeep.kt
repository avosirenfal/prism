package prism.preferences

import kotlinx.serialization.Serializable

/**
 * What the index does on its own to stay as the disk is, and whether what the disk is heard doing is kept to be
 * looked at. Each is on unless the user turns it off. With [scanOnStart], [watch] and [fallbackScans] all off, the
 * index changes only when it is rebuilt by hand; searches use it all the same.
 */
@Serializable
data class IndexUpkeep(
	/** Whether the index's folders are read again as prism starts, for what changed while it was not running. */
	val scanOnStart: Boolean = true,
	/**
	 * Whether changes are followed as they happen: by the eBPF probe on Linux, FSEvents on a Mac. Nothing follows
	 * them on Windows yet.
	 */
	val watch: Boolean = true,
	/** Whether the index's folders are read again every so often while nothing follows changes as they happen. */
	val fallbackScans: Boolean = true,
	/** Whether what the disk is heard doing is kept for the last minute, to be looked at in the index monitor. */
	val monitor: Boolean = true,
)
