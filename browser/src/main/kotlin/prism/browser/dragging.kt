// Compose's drag and drop on the desktop (its actions, its AWT transferables) is experimental; it is the only way to it.
@file:OptIn(ExperimentalComposeUiApi::class)

package prism.browser

import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.draganddrop.DragAndDropSourceModifierNode
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
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
						StrayDrop.ended(event.dropSuccess)
					}
				},
			)
			// Where the display server cannot say what a drag is over ([StrayDrop]), AWT finds nothing under it and
			// shows that it cannot be dropped, all the way across. It can: the pointer says what a drop would make.
			DragSource.getDefaultDragSource().addDragSourceMotionListener { event ->
				if (StrayDrop.enabled)
					event.dragSourceContext.cursor =
						when (StrayDrop.dragging) {
							is Dragged.Entries -> DragSource.DefaultCopyDrop
							is Dragged.Files -> DragSource.DefaultMoveDrop
							null -> null
						}
			}
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
 * Entries of a place being dragged. They are nowhere on disk, so there are no files for another application to be
 * given: the drag carries a description of them ([EntriesOut]), which any window of prism reads, in this process or
 * another, and brings them out by its own actions. As text, it carries their names.
 *
 * While the drag lasts the description is kept here too. A window of this process is given the drag through the
 * platform's protocol, as another application's would be, and not every toolkit hands a description of its own
 * making back whole; what is kept here needs none of that. A window of another process reads the drag itself.
 */
internal object CarriedDrag {
	@Volatile
	var current: EntriesOut? = null

	/** Whether the description is kept here at all: not, for the tests, which then read the drag as another process would. */
	@Volatile
	var kept: Boolean = true
}

/** The description of a place's entries, as a drag's data: itself, written out for another process. */
private val ENTRIES = DataFlavor(EntriesOut::class.java, "entries of a place")

/** Whether [transferable] is a drag of a place's entries. */
private fun carriesEntries(transferable: Transferable): Boolean =
	CarriedDrag.current != null || transferable.isDataFlavorSupported(ENTRIES)

/** The entries [transferable] is a drag of: those kept here for this process's own drag, or those it describes. */
private fun entriesOf(transferable: Transferable): EntriesOut? =
	CarriedDrag.current
		?: try {
			transferable
				.takeIf {
					it.isDataFlavorSupported(ENTRIES)
				}?.getTransferData(ENTRIES) as? EntriesOut
		} catch (failed: IOException) {
			null
		} catch (wrong: UnsupportedFlavorException) {
			null
		} catch (gone: InvalidDnDOperationException) {
			null
		}

/** A drag of a place's entries: their description, and their names as text. */
private class DraggedEntries(
	private val entries: EntriesOut,
) : Transferable {
	override fun getTransferDataFlavors(): Array<DataFlavor> =
		arrayOf(ENTRIES, DataFlavor.stringFlavor)

	override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
		flavor in transferDataFlavors

	override fun getTransferData(flavor: DataFlavor): Any =
		when (flavor) {
			ENTRIES -> entries
			DataFlavor.stringFlavor -> entries.entries.joinToString("\n") { it.name }
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

/** How far the pointer moves, a button held, before that is a drag and not a click with an unsteady hand. */
private val DRAG_SLOP = 8.dp

/** What a drag of this process's carries: files, or entries of a place. */
internal sealed interface Dragged {
	data class Files(
		val paths: List<Path>,
	) : Dragged

	data class Entries(
		val entries: EntriesOut,
	) : Dragged
}

/** Which window something is drawn in, to tell one of prism's from another: whoever draws a window gives it its own. */
val LocalWindowToken = staticCompositionLocalOf { Any() }

/**
 * A drop the display server never delivered, made good.
 *
 * Under XWayland on a compositor that does not tell windows where they are (niri, through xwayland-satellite), the X
 * server's idea of where each window is on screen is not where it is. AWT finds what a drag is over by asking the X
 * server which window is at the pointer, so a drag from one of prism's windows to another is never seen to be over
 * the other, and when the button is let go the drag ends having been dropped on nothing.
 *
 * What does still happen, on every compositor, is that the window the pointer is really over is told the pointer has
 * come into it, the moment the button is let go. So where a drag of this process's ends undelivered, and within a
 * moment the pointer comes into something that takes drops in another of prism's windows, that is where it was
 * dropped. The two can arrive in either order, so each waits a moment for the other.
 *
 * It is only done where it is needed ([enabled]): under XWayland. On a display server that delivers drops, a drag let
 * go over nothing was let go over nothing.
 */
internal object StrayDrop {
	private const val MOMENT_NANOS = 400_000_000L

	/** Under XWayland: a Wayland session, and AWT speaking X11. `-Dprism.strayDrop=true` or `false` says otherwise. */
	val enabled: Boolean by lazy {
		System.getProperty("prism.strayDrop")?.toBoolean()
			?: try {
				System.getenv("WAYLAND_DISPLAY") != null &&
					java.awt.Toolkit
						.getDefaultToolkit()
						.javaClass.name
						.contains("XToolkit")
			} catch (headless: java.awt.AWTError) {
				false
			}
	}

	private class Under(
		val dragged: Dragged,
		val from: Any,
	)

	private class Stray(
		val drag: Under,
		val at: Long,
	)

	private class Offer(
		val window: Any,
		val take: (Dragged) -> Boolean,
		val at: Long,
	)

	// All read and written on AWT's event thread, where drags end and pointers move.
	private var under: Under? = null
	private var stray: Stray? = null
	private var offer: Offer? = null

	/** What the drag under way is of; null while none of this process's is. */
	val dragging: Dragged? get() = under?.dragged

	/** A drag of [dragged] begun in the window [from]. */
	fun started(dragged: Dragged, from: Any) {
		under = Under(dragged, from)
		stray = null
	}

	/** The drag ended: delivered, or not; one that was not is given to where the pointer just came in, or waits for it. */
	fun ended(delivered: Boolean) {
		val drag = under
		val now = System.nanoTime()

		under = null
		if (!enabled || delivered || drag == null)
			return

		val came = offer?.takeIf { now - it.at < MOMENT_NANOS && it.window !== drag.from }

		if (came == null || !came.take(drag.dragged))
			stray = Stray(drag, now)
	}

	/**
	 * The pointer has come over something in [window] that takes drops, [take] giving it one (and saying whether it
	 * took it). A drag that has just ended undelivered, from another window, is dropped there; otherwise this is
	 * remembered a moment, for a drag about to end.
	 */
	fun offered(window: Any, take: (Dragged) -> Boolean) {
		val now = System.nanoTime()
		val left = stray?.takeIf { now - it.at < MOMENT_NANOS && it.drag.from !== window }

		if (left != null) {
			if (take(left.drag.dragged))
				stray = null

			return
		}

		// The innermost thing under the pointer says so first: what is around it, the same instant, does not replace it.
		val last = offer

		if (last == null || last.window !== window || now - last.at > SAME_INSTANT_NANOS)
			offer = Offer(window, take, now)
	}

	private const val SAME_INSTANT_NANOS = 4_000_000L
}

/**
 * This dragged as files: those [paths] gives when the drag starts (none: no drag), as the browser's own drag. [onStart]
 * is told as it starts, and [onEnd] what the target made of it, once it is over: a move made by another application is
 * the files gone from where they were.
 *
 * Where there are no files to drag, [carried] may give entries of a place instead: dragged as themselves, to be
 * brought out into the folder they are dropped on, in any window of prism.
 *
 * A drag starts when the first button is held and the pointer has moved a little way ([DRAG_SLOP]), as desktops have
 * it: a click with a hand that is not quite still is a click, and the other buttons drag nothing.
 */
@Composable
fun Modifier.filesDragged(
	paths: () -> List<Path>,
	onStart: () -> Unit,
	onEnd: (Transfer?) -> Unit,
	carried: () -> Carried? = { null },
): Modifier {
	val window = LocalWindowToken.current
	val files by rememberUpdatedState(paths)
	val entries by rememberUpdatedState(carried)
	val start by rememberUpdatedState(onStart)
	val end by rememberUpdatedState(onEnd)

	// One for as long as this is drawn, reading what it is given as it is by then: the modifier is then the same from
	// one drawing to the next, and nothing is told the window system again that has not changed.
	val data =
		remember(window) {
			{ offset: Offset ->
				val dragged = files()

				if (dragged.isEmpty()) {
					entries()?.let(EntriesOut::of)?.let { out ->
						start()
						OwnDrag.started()
						StrayDrop.started(Dragged.Entries(out), window)
						if (CarriedDrag.kept)
							CarriedDrag.current = out
						DragAndDropTransferData(
							DragAndDropTransferable(DraggedEntries(out)),
							listOf(DragAndDropTransferAction.Copy),
							offset,
							// What is carried is let go when the drag ends (OwnDrag), which is after a drop has read it.
							onTransferCompleted = {},
						)
					}
				} else {
					start()
					OwnDrag.started()
					StrayDrop.started(Dragged.Files(dragged), window)
					DragAndDropTransferData(
						DragAndDropTransferable(DraggedFiles(dragged)),
						listOf(
							DragAndDropTransferAction.Move,
							DragAndDropTransferAction.Copy,
							DragAndDropTransferAction.Link,
						),
						offset,
						onTransferCompleted = { end(transferOf(it)) },
					)
				}
			}
		}

	return this then DragSourceElement(data)
}

/** A drag source that starts as [DragSourceNode] says, giving what [data] gives for where it started. */
private data class DragSourceElement(
	val data: (Offset) -> DragAndDropTransferData?,
) : ModifierNodeElement<DragSourceNode>() {
	override fun create(): DragSourceNode = DragSourceNode(data)

	override fun update(node: DragSourceNode) {
		node.data = data
	}

	override fun InspectorInfo.inspectableProperties() {
		name = "filesDragged"
	}
}

/**
 * What starts a drag: the first button held, and the pointer moved [DRAG_SLOP] from where it was pressed. Compose's
 * own drag source starts one after a pixel or two of movement with any button, which makes drags of clicks.
 */
private class DragSourceNode(
	var data: (Offset) -> DragAndDropTransferData?,
) : DelegatingNode() {
	private var area = Size.Zero

	private val source =
		delegate(
			DragAndDropSourceModifierNode { offset ->
				// Nothing is drawn under the pointer as it is dragged: the row stays where it is, as on most desktops.
				data(offset)?.let { startDragAndDropTransfer(it, area) {} }
			},
		)

	init {
		delegate(
			SuspendingPointerInputModifierNode {
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false)

					if (!currentEvent.buttons.isPrimaryPressed)
						return@awaitEachGesture

					val slop = DRAG_SLOP.toPx()

					while (true) {
						val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break

						if (!change.pressed)
							break

						if ((change.position - down.position).getDistance() >= slop) {
							area = Size(size.width.toFloat(), size.height.toFloat())
							source.requestDragAndDropTransfer(down.position)
							break
						}
					}
				}
			},
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
	onEntries: ((EntriesOut) -> Unit)? = null,
	onDrop: (List<Path>, Transfer) -> Unit,
): Modifier {
	val volumes = LocalVolumes.current
	val hover by rememberUpdatedState(onHover)
	val drop by rememberUpdatedState(onDrop)
	val bring by rememberUpdatedState(onEntries)
	val window = LocalWindowToken.current
	val entered = onEntries != null
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
					if (bring != null && carriesEntries(event.awtTransferable))
						return Dropping(CarriedDrag.current?.entries?.size, Transfer.Copy, folder)

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
					if (carriesEntries(event.awtTransferable)) {
						hover(null)

						return bring?.let { taken -> entriesOf(event.awtTransferable)?.let(taken) } != null
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

	// The same from one drawing to the next, so that taking drops here is told the window system once, and not again
	// each time the browser is drawn: a row that takes drops is a row the window system is told of.
	val accepts =
		remember(entered) {
			{ event: DragAndDropEvent ->
				carriesFiles(event.awtTransferable) ||
					(entered && carriesEntries(event.awtTransferable))
			}
		}

	// A drop the display server did not deliver ([StrayDrop]): taken as the files, or the entries, it was.
	val take =
		remember(spot) {
			{ dragged: Dragged ->
				when (dragged) {
					is Dragged.Files -> {
						val first = dragged.paths.firstOrNull()
						val same = first == null || folder == null || volumes(first, folder!!)

						drop(dragged.paths, transferOf(Asked.Default, same))
						true
					}
					is Dragged.Entries -> bring?.let { it(dragged.entries) } != null
				}
			}
		}

	return dragAndDropTarget(accepts, target).then(
		if (StrayDrop.enabled)
			Modifier.pointerInput(window, take) {
				awaitPointerEventScope {
					while (true) {
						val event = awaitPointerEvent()
						val came =
							event.type == PointerEventType.Enter || event.type == PointerEventType.Move

						if (came && !event.buttons.areAnyPressed)
							StrayDrop.offered(window, take)
					}
				}
			}
		else
			Modifier,
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
		onEntries = { onEvent(BrowserEvent.DroppedEntries(it, spot)) },
		onDrop = { paths, transfer -> onEvent(BrowserEvent.Dropped(paths, spot, transfer)) },
	).then(
		if (over)
			Modifier.border(2.dp, palette.accent, RoundedCornerShape(Dims.radius))
		else
			Modifier,
	)
}
