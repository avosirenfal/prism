// Compose's drag and drop on the desktop (its actions, its AWT transferables) is experimental; it is the only way to it.
@file:OptIn(ExperimentalComposeUiApi::class)

package prism.browser

import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import java.awt.HeadlessException
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.DragSource
import java.awt.dnd.DragSourceAdapter
import java.awt.dnd.DragSourceDragEvent
import java.awt.dnd.DragSourceDropEvent
import java.awt.dnd.InvalidDnDOperationException
import java.awt.event.InputEvent
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import kotlinx.coroutines.delay

/*
 * Files dragged out of the browser and dropped into it, through AWT's drag and drop, which each platform's toolkit
 * speaks in its own protocol: XDND under X11 (and XWayland), a Wayland data offer under JetBrains' Wayland toolkit, a
 * dragging session on macOS, OLE on Windows. Files go as a list of files, which every one of them knows; nothing here
 * names a platform.
 *
 * What a drop does (copy, move, or link) is what the user asked for, by the keys they held, as their platform says
 * keys ask: on Linux and Windows, Ctrl copies and Shift moves; on macOS, Option copies and Cmd moves. With no key held
 * the desktops agree: a move within a volume, a copy across. A drag from another application comes with its toolkit's
 * choice made. AWT, though, says "move" of a drag of the browser's own whether a key asked for it or none did, so the
 * keys held during the browser's own drags are watched here ([OwnDrag]), and the rule applied to them ([transferOf]).
 *
 * A move is the target's to make, as on every platform: dragged out and moved by another application, the files are
 * gone from the folder shown, which is read again, and the browser deletes nothing itself.
 */

/** What the user asked of a drop: no action in particular, or one, by the keys they held. */
@Immutable
sealed interface Asked {
	data object Default : Asked

	data class Chosen(
		val transfer: Transfer,
	) : Asked
}

/** What [asked] makes of a drop: a move within a volume and a copy across, where nothing in particular was asked. */
fun transferOf(asked: Asked, sameVolume: Boolean): Transfer =
	when (asked) {
		Asked.Default -> if (sameVolume) Transfer.Move else Transfer.Copy
		is Asked.Chosen -> asked.transfer
	}

/**
 * The drag this process has started, while it lasts, and whether the keys held ask for an action: what tells "move,
 * because Shift is held" from "move, because AWT says move when nothing is". Read from AWT's own drag source, which
 * starts every drag a Compose window makes, so it says the same on every platform.
 */
internal object OwnDrag {
	/** Null while no drag of this process is under way; otherwise whether a key asks for an action. */
	@Volatile
	private var keyed: Boolean? = null

	private val keys =
		InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK or InputEvent.ALT_DOWN_MASK or
			InputEvent.META_DOWN_MASK

	private val watching: Boolean by lazy {
		try {
			DragSource.getDefaultDragSource().addDragSourceListener(
				object : DragSourceAdapter() {
					override fun dragOver(event: DragSourceDragEvent) = held(event)

					override fun dropActionChanged(event: DragSourceDragEvent) = held(event)

					override fun dragDropEnd(event: DragSourceDropEvent) {
						keyed = null
						CarriedDrag.current = null
					}
				},
			)
			true
		} catch (headless: HeadlessException) {
			false
		}
	}

	private fun held(event: DragSourceDragEvent) {
		keyed = event.gestureModifiersEx and keys != 0
	}

	/** A drag of the browser's own begun, no key held yet. */
	fun started() {
		if (watching)
			keyed = false
	}

	/** What a drop of [action], as AWT says it, asks: of the browser's own drag, by its keys; of another's, the action. */
	fun asked(action: Transfer): Asked =
		when (keyed) {
			false -> Asked.Default
			else -> Asked.Chosen(action)
		}
}

/** AWT's action, as a transfer; none for none. */
private fun transferOf(action: DragAndDropTransferAction?): Transfer? =
	when (action) {
		DragAndDropTransferAction.Copy -> Transfer.Copy
		DragAndDropTransferAction.Move -> Transfer.Move
		DragAndDropTransferAction.Link -> Transfer.Link
		else -> null
	}

/**
 * Entries of a place being dragged: what they are is kept here, in this process, while the drag lasts, and the drag
 * itself carries only a mark that says so ([CARRIED]). They are nowhere on disk, so there are no files for another
 * application to be given; a window of this process, which the mark tells, reads them from here.
 */
internal object CarriedDrag {
	@Volatile
	var current: Carried? = null
}

/** What marks a drag as one of a place's entries, to this process alone. */
private val CARRIED =
	DataFlavor(
		DataFlavor.javaJVMLocalObjectMimeType + ";class=prism.browser.Carried",
		"entries of a place",
	)

/**
 * The entries of a place being dragged, where what is dragged is this process's drag of them. The drag itself is
 * not asked: a window the drag is over is given it through the platform's protocol, as another application's would
 * be, and what that says of a mark only this process knows differs between toolkits. What is kept here does not.
 */
@Suppress("UNUSED_PARAMETER")
private fun carriedOf(transferable: Transferable): Carried? = CarriedDrag.current

/** A drag of a place's entries: the mark alone, and no files. */
private class DraggedEntries(
	private val carried: Carried,
) : Transferable {
	// As text too, the entries' names: a drag that offers a platform nothing it knows is not begun by some of them,
	// and an editor it is dropped on is given something sensible.
	override fun getTransferDataFlavors(): Array<DataFlavor> =
		arrayOf(CARRIED, DataFlavor.stringFlavor)

	override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
		flavor in transferDataFlavors

	override fun getTransferData(flavor: DataFlavor): Any =
		when (flavor) {
			CARRIED -> carried
			DataFlavor.stringFlavor ->
				carried.pending.subject.things.joinToString(
					"\n",
				) { it.name }
			else -> throw UnsupportedFlavorException(flavor)
		}
}

/** A URI list, as X11 and Wayland name files: where a toolkit does not make it a list of files itself. */
private val URI_LIST = DataFlavor("text/uri-list;class=java.lang.String")

/** Whether [transferable] holds files. */
private fun carriesFiles(transferable: Transferable): Boolean =
	transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
		transferable.isDataFlavorSupported(URI_LIST)

/** The files [transferable] holds: as a list of files, or a URI list; null where it holds none, or cannot be read. */
fun filesOf(transferable: Transferable): List<Path>? =
	try {
		when {
			transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
				(transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>)
					.filterIsInstance<File>()
					.map { it.toPath() }
			transferable.isDataFlavorSupported(URI_LIST) ->
				pathsOfUris(transferable.getTransferData(URI_LIST) as String)
			else -> null
		}?.takeIf { it.isNotEmpty() }
	} catch (failed: IOException) {
		null
	} catch (wrong: UnsupportedFlavorException) {
		null
	} catch (gone: InvalidDnDOperationException) {
		null
	}

/** The files a URI list names, one a line; comments (`#`) and what is not a file left out. */
fun pathsOfUris(text: String): List<Path> =
	text
		.lines()
		.map { it.trim() }
		.filter { it.isNotEmpty() && !it.startsWith("#") }
		.mapNotNull {
			try {
				URI(it).takeIf { uri -> uri.scheme == "file" }?.let(Path::of)
			} catch (wrong: URISyntaxException) {
				null
			} catch (wrong: IllegalArgumentException) {
				null
			}
		}

/** [paths], as a drag offers them: a list of files, and their paths as text for what takes only text. */
private class DraggedFiles(
	private val paths: List<Path>,
) : Transferable {
	override fun getTransferDataFlavors(): Array<DataFlavor> =
		arrayOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor)

	override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
		flavor in transferDataFlavors

	override fun getTransferData(flavor: DataFlavor): Any =
		when (flavor) {
			DataFlavor.javaFileListFlavor -> paths.map { it.toFile() }
			DataFlavor.stringFlavor -> paths.joinToString("\n")
			else -> throw UnsupportedFlavorException(flavor)
		}
}

/**
 * Whether two paths are on one volume, as the browser's disk says: given by whoever runs a browser on a disk, and one
 * volume where nobody does.
 */
val LocalVolumes = staticCompositionLocalOf<(Path, Path) -> Boolean> { { _, _ -> true } }

/**
 * This dragged as files: those [paths] gives when the drag starts (none: no drag), as the browser's own drag. [onStart]
 * is told as it starts, and [onEnd] what the target made of it, once it is over: a move made by another application is
 * the files gone from where they were.
 *
 * Where there are no files to drag, [carried] may give entries of a place instead: dragged as themselves, to be
 * brought out into the folder they are dropped on, in any window of this process.
 */
fun Modifier.filesDragged(
	paths: () -> List<Path>,
	onStart: () -> Unit,
	onEnd: (Transfer?) -> Unit,
	carried: () -> Carried? = { null },
): Modifier =
	dragAndDropSource { offset ->
		val files = paths()

		if (files.isEmpty())
			return@dragAndDropSource carried()?.let { entries ->
				onStart()
				OwnDrag.started()
				CarriedDrag.current = entries
				DragAndDropTransferData(
					DragAndDropTransferable(DraggedEntries(entries)),
					listOf(DragAndDropTransferAction.Copy),
					offset,
					// What is carried is let go when the drag ends (OwnDrag), which is after a drop has read it.
					onTransferCompleted = {},
				)
			}

		files.let { dragged ->
			onStart()
			OwnDrag.started()
			DragAndDropTransferData(
				DragAndDropTransferable(DraggedFiles(dragged)),
				listOf(
					DragAndDropTransferAction.Move,
					DragAndDropTransferAction.Copy,
					DragAndDropTransferAction.Link,
				),
				offset,
				onTransferCompleted = { onEnd(transferOf(it)) },
			)
		}
	}

/** A drop about to be made: how many files, made what, where. */
@Immutable
data class Dropping(
	val count: Int?,
	val transfer: Transfer,
	val into: Path?,
)

/**
 * This a place files are dropped, on [spot], which stands for the folder [into] (to tell a move from a copy by): told
 * what a drop would be as files are dragged over it ([onHover], and null as they leave), and given the files and what
 * to make of them as they are dropped.
 */
@Composable
fun Modifier.filesDropped(
	spot: DropSpot,
	into: Path?,
	onHover: (Dropping?) -> Unit,
	onCarried: ((Carried) -> Unit)? = null,
	onDrop: (List<Path>, Transfer) -> Unit,
): Modifier {
	val volumes = LocalVolumes.current
	val hover by rememberUpdatedState(onHover)
	val drop by rememberUpdatedState(onDrop)
	val bring by rememberUpdatedState(onCarried)
	val folder by rememberUpdatedState(into)

	val target =
		remember(spot) {
			object : DragAndDropTarget {
				/** The files of the drag over this, and whether they are on this folder's volume: read once it is over it. */
				private var read: Pair<List<Path>?, Boolean>? = null

				fun readOf(event: DragAndDropEvent): Pair<List<Path>?, Boolean> =
					read ?: filesOf(event.awtTransferable).let { dragged ->
						val first = dragged?.firstOrNull()

						(dragged to (first == null || folder == null || volumes(first, folder!!))).also {
							read =
								it
						}
					}

				fun dropping(event: DragAndDropEvent): Dropping? {
					// Entries of a place are brought out, which leaves them where they were: a copy, whatever is held.
					carriedOf(event.awtTransferable)?.let {
						return Dropping(it.pending.subject.things.size, Transfer.Copy, folder)
					}

					val action = transferOf(event.action) ?: return null
					val (dragged, same) = readOf(event)

					return Dropping(dragged?.size, transferOf(OwnDrag.asked(action), same), folder)
				}

				override fun onEntered(event: DragAndDropEvent) = hover(dropping(event))

				// Moving too: what is under the pointer can change beneath it (a folder opened), and the keys held.
				override fun onMoved(event: DragAndDropEvent) = hover(dropping(event))

				override fun onChanged(event: DragAndDropEvent) = hover(dropping(event))

				override fun onExited(event: DragAndDropEvent) = hover(null)

				override fun onEnded(event: DragAndDropEvent) {
					hover(null)
					read = null
				}

				override fun onDrop(event: DragAndDropEvent): Boolean {
					carriedOf(event.awtTransferable)?.let { carried ->
						hover(null)

						return bring?.let { it(carried) } != null
					}

					val made = dropping(event)
					val dragged = readOf(event).first

					hover(null)

					return if (made == null || dragged == null)
						false
					else
						true.also { drop(dragged, made.transfer) }
				}
			}
		}

	return dragAndDropTarget(
		{
			carriesFiles(it.awtTransferable) ||
				(onCarried != null && carriedOf(it.awtTransferable) != null)
		},
		target,
	)
}

/** What a drop on [spot] would be, while files are over it. */
@Immutable
data class Hint(
	val dropping: Dropping,
	val spot: DropSpot,
)

/**
 * The drop the files dragged over the browser would make, said under its listing: every place files can be dropped (a
 * row, a place in the sidebar, a step of the path, the listing itself) says it here, since the pointer the platform
 * draws cannot be told which action the browser will make.
 */
@Stable
class DropHint {
	var shown: Hint? by mutableStateOf(null)
		private set

	/** [spot]'s drop shown, or, for none, no longer shown, unless another spot's is shown by now. */
	fun over(spot: DropSpot, dropping: Dropping?) {
		if (dropping != null)
			shown = Hint(dropping, spot)
		else if (shown?.spot == spot)
			shown = null
	}

	fun clear() {
		shown = null
	}
}

val LocalDropHint = staticCompositionLocalOf { DropHint() }

/**
 * What [hint] says, in a browser [exploring] or a picker: "Move 2 items into “Documents”", "Copy “a.txt” here", or, for
 * a picker given files on its listing, that it goes to them.
 */
fun wordsOf(hint: Hint, exploring: Boolean): String {
	val dropping = hint.dropping
	val what =
		when (val count = dropping.count) {
			null -> "them"
			1 -> "1 item"
			else -> "$count items"
		}

	val where =
		when (hint.spot) {
			DropSpot.Here -> "here"
			is DropSpot.Into -> dropping.into?.let { "into “${it.fileName ?: it}”" } ?: "there"
		}

	return when {
		hint.spot == DropSpot.Here && !exploring -> "Go to what is dropped"
		dropping.transfer == Transfer.Copy -> "Copy $what $where"
		dropping.transfer == Transfer.Move -> "Move $what $where"
		else -> "Link to $what $where"
	}
}

/** How long files dragged over a folder stay before it is opened, for them to be dropped further in. */
private const val SPRING_MILLIS = 900L

/**
 * A folder files can be dropped on, [folder]: drawn as one while files are over it, said in the [DropHint], opened by
 * [onOpen] (where there is one) once they stay a moment, as desktops open a folder a drag waits over, and given them
 * as they are dropped.
 */
@Composable
fun Modifier.folderDrop(
	folder: Path,
	onOpen: (() -> Unit)?,
	onEvent: (BrowserEvent) -> Unit,
): Modifier {
	val hint = LocalDropHint.current
	val palette = LocalPalette.current
	val spot = remember(folder) { DropSpot.Into(folder) }
	var over by remember(folder) { mutableStateOf(false) }
	val open by rememberUpdatedState(onOpen)

	LaunchedEffect(over) {
		if (over) {
			delay(SPRING_MILLIS)
			open?.invoke()
		}
	}

	return filesDropped(
		spot,
		folder,
		onHover = {
			over = it != null
			hint.over(spot, it)
		},
		onCarried = { onEvent(BrowserEvent.DroppedCarried(it, spot)) },
		onDrop = { paths, transfer -> onEvent(BrowserEvent.Dropped(paths, spot, transfer)) },
	).then(
		if (over)
			Modifier.border(2.dp, palette.accent, RoundedCornerShape(Dims.radius))
		else
			Modifier,
	)
}
