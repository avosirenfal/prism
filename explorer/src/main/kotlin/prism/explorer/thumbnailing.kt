package prism.explorer

import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import prism.preferences.PreferenceStore
import prism.thumbnails.Budget
import prism.thumbnails.Ffmpeg
import prism.thumbnails.Relocated
import prism.thumbnails.Relocation
import prism.thumbnails.ThumbnailCache
import prism.thumbnails.ThumbnailSettings
import prism.thumbnails.ThumbnailSize
import prism.thumbnails.Thumbnailer
import prism.thumbnails.Thumbnailers
import prism.thumbnails.Totals

/*
 * The thumbnails' settings, as their page edits them. All but one are as every other setting is: changed on the page,
 * and written when the user applies. The folder thumbnails are kept in is not: there are files in the folder it is
 * changed from, and what becomes of them has to be said as it is changed, so it is changed at once, by a button of
 * its own, and written at once ([Relocator]), whatever else on the page waits to be applied.
 *
 * An example. Thumbnails are kept in `~/.cache/thumbnails`, which holds twelve thousand, and the user types
 * `/mnt/fast/thumbnails` and presses Change. They are asked what to do with the twelve thousand: move them, delete
 * them, or leave them where they are. Whichever they choose, the new folder is written to the preferences then, and
 * is where thumbnails are kept from that moment. Moving shows how far it has got and can be stopped: every
 * thumbnail moved by then is in the new folder and is used from there, and the rest are where they were, as if they
 * had been left.
 */

/** What the Thumbnails page shows that it does not set: what the running prism says of its thumbnails. */
data class ThumbnailFacts(
	/** Where thumbnails are kept where no folder is set. */
	val default: Path = ThumbnailCache.defaultRoot(),
	/** How many are kept, and how many bytes they take; null until they have been counted. */
	val totals: Totals? = null,
	/** Whether there is an ffmpeg. */
	val ffmpeg: Found = Found.Looking,
) {
	/** Whether ffmpeg is there to make thumbnails with. */
	sealed interface Found {
		/** Not known yet: it is being looked for. */
		data object Looking : Found

		data object Missing : Found

		data class Installed(
			val ffmpeg: Ffmpeg,
		) : Found
	}
}

/** A folder for thumbnails changed [from] one [to] another: [written] is what the preferences then hold. */
data class FolderChange(
	val from: Path,
	val to: Path,
	/** The folder as it is written: null for the platform's own, which is written as no folder set. */
	val written: String?,
)

/** What is done with the thumbnails in the folder they are no longer kept in. */
enum class Leftover {
	Move,
	Delete,
	Leave,
}

/** What the page is doing about a change of folder, over everything else on it. */
sealed interface Relocating {
	val change: FolderChange

	/** Asking what is to become of the thumbnails in the folder changed from. */
	data class Asking(
		override val change: FolderChange,
	) : Relocating

	/** Moving them, or [removing] them: how far it has got ([at]; null before the first). */
	data class Working(
		override val change: FolderChange,
		val removing: Boolean,
		val at: Relocated? = null,
	) : Relocating
}

/** What the user asks of a change of folder. */
sealed interface Relocate {
	/** The folder changed, as [change] says: asked of the page's Change and Use default buttons. */
	data class Proposed(
		val change: FolderChange,
	) : Relocate

	/** What to do with what the old folder holds, chosen. */
	data class Chosen(
		val leftover: Leftover,
	) : Relocate

	/** Never mind: the folder is not changed, where it has not been; a move or a removal under way is stopped. */
	data object Stopped : Relocate
}

/** The thumbnails' settings as their page edits them: [kept] as the preferences keep them, and the folder as [typed]. */
data class ThumbnailEdits(
	val kept: ThumbnailSettings = ThumbnailSettings(),
	/** What is typed in the folder's field, where anything has been since the folder was last changed. */
	val typed: String? = null,
) {
	/** What the folder's field shows: what is typed, or the folder thumbnails are kept in. */
	fun folderShown(default: Path): String = typed ?: kept.root { default }.toString()

	/** What changing the folder to what is typed would be; null where nothing is typed, or it is the folder kept in. */
	fun proposed(default: Path): Proposal? {
		val text = typed?.trim().orEmpty()
		val from = kept.root { default }
		val to = ThumbnailSettings.folderOf(text)

		return when {
			typed == null -> null
			text.isEmpty() -> Proposal.Unfit("Enter a folder.")
			to == null -> Proposal.Unfit("Enter the folder's full path, from the root.")
			to == from -> null
			else -> Proposal.Fit(FolderChange(from, to, to.toString().takeIf { to != default }))
		}
	}

	/** What going back to the platform's own folder would be; null where that is where thumbnails are kept. */
	fun toDefault(default: Path): FolderChange? =
		kept.root { default }.takeIf { it != default }?.let { FolderChange(it, default, null) }

	/** What typing in the folder's field makes of these. */
	fun typing(text: String): ThumbnailEdits = copy(typed = text)

	/** These, the folder changed to what is [written], and nothing typed. */
	fun relocated(written: String?): ThumbnailEdits =
		copy(kept = kept.copy(folder = written), typed = null)

	/** These, keeping [budget]. */
	fun budgeted(budget: Budget): ThumbnailEdits = copy(kept = kept.copy(budget = budget))

	/** These, every thumbnail made at [size]; or at the size it is drawn, for none. */
	fun sized(size: ThumbnailSize?): ThumbnailEdits = copy(kept = kept.copy(size = size))

	/** These, making thumbnails for [nearby] files either side of what is shown. */
	fun ahead(nearby: Int): ThumbnailEdits = copy(kept = kept.copy(nearby = nearby))

	/** These, the program [id] turned [on], or off. */
	fun switched(id: String, on: Boolean): ThumbnailEdits =
		copy(
			kept =
				kept.copy(
					off =
						if (on)
							kept.off - id
						else
							kept.off + id,
				),
		)

	/** The programs these would give, with the ffmpeg there is. */
	fun thumbnailers(ffmpeg: Ffmpeg?): Thumbnailers =
		Thumbnailers.of(kept.thumbnailers, kept.off, ffmpeg)

	/** What is typed for the folder, read. */
	sealed interface Proposal {
		data class Fit(
			val change: FolderChange,
		) : Proposal

		data class Unfit(
			val why: String,
		) : Proposal
	}

	companion object {
		/** The sizes the cache can be kept to, in bytes: each twice the one before. */
		val BYTES: List<Long> = (0..8).map { (256L shl 20) shl it }

		/** The numbers of files it can be kept to. */
		val FILES: List<Int> =
			listOf(5_000, 10_000, 25_000, 50_000, 100_000, 200_000, 500_000, 1_000_000)

		/** How many files either side of what is shown can be made ahead. */
		val NEARBY: List<Int> = listOf(0, 50, 100, 150, 300, 500, 1000)

		/** Where [value] is among [steps]: at the nearest of them. */
		fun <T : Number> nearest(steps: List<T>, value: T): Int =
			steps.indices.minBy { kotlin.math.abs(steps[it].toDouble() - value.toDouble()) }

		/** How a program's files are said: its first few extensions, and how many more there are. */
		fun filesOf(thumbnailer: Thumbnailer, few: Int = 8): String {
			val extensions = thumbnailer.handles.extensions.sorted()
			val types = thumbnailer.handles.types.sorted()
			val named = extensions.ifEmpty { types }

			return named.take(few).joinToString(", ") +
				(named.size - few).takeIf { it > 0 }?.let { " and $it more" }.orEmpty()
		}
	}
}

/**
 * What changes the folder thumbnails are kept in, for a settings window: what it is doing is [state], which the
 * window draws over its pages. The preferences are written the moment the user has said what becomes of the old
 * folder's thumbnails ([written] is told, for the window to take the folder as changed), and whatever is then done
 * with them is done in [scope], a thumbnail at a time, on [io].
 */
class Relocator(
	private val preferences: PreferenceStore,
	private val scope: CoroutineScope,
	private val written: (String?) -> Unit,
	private val io: CoroutineDispatcher = Dispatchers.IO,
) {
	private val doing = MutableStateFlow<Relocating?>(null)

	/** What is being asked or done about a change of folder; null where nothing is. */
	val state: StateFlow<Relocating?> = doing.asStateFlow()

	private var working: Job? = null

	fun ask(relocate: Relocate) {
		when (relocate) {
			is Relocate.Proposed -> propose(relocate.change)
			is Relocate.Chosen ->
				(doing.value as? Relocating.Asking)?.let {
					choose(it.change, relocate.leftover)
				}
			Relocate.Stopped -> stop()
		}
	}

	/** The folder to be changed: at once where the old one holds nothing, and asked about where it holds anything. */
	private fun propose(change: FolderChange) {
		if (doing.value != null)
			return

		scope.launch {
			if (withContext(io) { Relocation.holds(ThumbnailCache(change.from)) })
				doing.value = Relocating.Asking(change)
			else
				write(change)
		}
	}

	private fun choose(change: FolderChange, leftover: Leftover) {
		// Written first: from here on, thumbnails are kept in the new folder, whatever becomes of the old one's.
		write(change)

		val from = ThumbnailCache(change.from)

		when (leftover) {
			Leftover.Leave -> doing.value = null
			Leftover.Move, Leftover.Delete -> {
				val removing = leftover == Leftover.Delete

				doing.value = Relocating.Working(change, removing)
				working =
					scope.launch {
						try {
							val told = { at: Relocated ->
								doing.value =
									Relocating.Working(change, removing, at)
							}

							if (removing)
								Relocation.delete(from, told)
							else
								Relocation.move(from, ThumbnailCache(change.to), told)
						} finally {
							// Done, or stopped part of the way: either way there is nothing more to show.
							doing.value = null
						}
					}
			}
		}
	}

	private fun write(change: FolderChange) {
		preferences.update { copy(thumbnailing = thumbnailing.copy(folder = change.written)) }
		written(change.written)
	}

	/** Asked, the folder is not changed after all; moving or removing, it is stopped where it has got to. */
	private fun stop() {
		when (doing.value) {
			is Relocating.Asking -> doing.value = null
			is Relocating.Working -> working?.cancel()
			null -> {}
		}
	}
}
