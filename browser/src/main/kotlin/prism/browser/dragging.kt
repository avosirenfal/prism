package prism.browser

import androidx.compose.foundation.border
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import kotlinx.coroutines.delay
import prism.window.DragKeys
import prism.window.DragOver
import prism.window.DragPicture
import prism.window.DropEffect
import prism.window.DropTarget
import prism.window.Dropped
import prism.window.LocalNativeWindow
import prism.window.Outgoing
import prism.window.Video
import prism.window.dropTarget
import prism.window.startDrag

/*
 * Files dragged out of the browser and dropped into it. A drag of the browser's own carries what it drags as itself
 * ([Dragged]) to any window of this process; files dragged in from another application come as files, through the
 * platform's own protocol, which the window speaks (prism.window).
 *
 * What a drop does (copy, move, or link) is what the user asked for, by the keys they held, as their platform says
 * keys ask: on Linux and Windows, Ctrl copies, Shift moves and both link; on macOS, Option copies, Command moves and
 * both link. With no key held the desktops agree: a move within a volume, a copy across. Files from another
 * application are dropped as no key asks, since the keys held over another application's drag are its own.
 *
 * A move is the target's to make, as on every platform: dragged out and moved by another window, the files are gone
 * from the folder shown, which is read again, and the browser deletes nothing itself.
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

/** What [keys] held during a drag ask of its drop, as [mac]'s keys or everyone else's ask it. */
fun askedOf(keys: DragKeys, mac: Boolean): Asked {
	val (copy, move) =
		if (mac)
			keys.alt to keys.meta
		else
			keys.ctrl to keys.shift

	return when {
		copy && move -> Asked.Chosen(Transfer.Link)
		copy -> Asked.Chosen(Transfer.Copy)
		move -> Asked.Chosen(Transfer.Move)
		else -> Asked.Default
	}
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

/**
 * Whether two paths are on one volume, as the browser's disk says: given by whoever runs a browser on a disk, and one
 * volume where nobody does.
 */
val LocalVolumes = staticCompositionLocalOf<(Path, Path) -> Boolean> { { _, _ -> true } }

/** How far the pointer moves, a button held, before that is a drag and not a click with an unsteady hand. */
private val DRAG_SLOP = 8.dp

/** What a drag shows of what it carries: the icon of the first of them ([glyph], marked as [category] is), and how many. */
@Immutable
data class Carrying(
	val glyph: Glyph,
	val category: Category,
	val count: Int,
)

/** How large the icon a drag shows is, and the badge on its corner that says how many it carries. */
private val PICTURE_ICON = 40.dp
private val BADGE = 18.dp

/** The most a drag's badge counts: more are said as this, and a plus. */
private const val MOST_COUNTED = 999

/**
 * What a drag shows under the pointer, as other file managers' do: the icon of the first thing dragged, as large as a
 * tile's, and, where there are several, how many, on a badge at its top right corner. The window draws it at its own
 * density, so the count is laid out as it is drawn, at that density.
 */
@Composable
fun rememberDragPictures(): (Carrying) -> DragPicture {
	val palette = LocalPalette.current
	val measurer = rememberTextMeasurer()

	return remember(palette, measurer) { { carrying -> dragPictureOf(carrying, palette, measurer) } }
}

/** [carrying] as [rememberDragPictures] draws it, in [palette]'s colours, its count laid out by [measurer]. */
fun dragPictureOf(carrying: Carrying, palette: Palette, measurer: TextMeasurer): DragPicture =
	DragPicture(DpSize(PICTURE_ICON + BADGE / 2, PICTURE_ICON + BADGE / 2)) {
		val badge = BADGE.toPx()

		// The icon down and to the left of the badge's middle, which sits on its corner.
		inset(0f, badge / 2, badge / 2, 0f) {
			drawGlyph(carrying.glyph, palette.text, palette, carrying.category)
		}

		if (carrying.count > 1) {
			val count =
				measurer.measure(
					if (carrying.count > MOST_COUNTED) "$MOST_COUNTED+" else carrying.count.toString(),
					TextStyle(color = palette.onAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
					density = this,
					layoutDirection = layoutDirection,
				)
			// A circle for a figure, a pill for more, as wide as they are.
			val width = maxOf(badge, count.size.width + badge / 2)
			val left = size.width - width

			drawRoundRect(palette.accent, Offset(left, 0f), Size(width, badge), CornerRadius(badge / 2))
			drawText(
				count,
				topLeft = Offset(left + (width - count.size.width) / 2, (badge - count.size.height) / 2),
			)
		}
	}

/** What a drag of the browser's carries: files, or entries of a place. */
sealed interface Dragged {
	data class Files(
		val paths: List<Path>,
	) : Dragged

	data class Entries(
		val entries: EntriesOut,
	) : Dragged
}

/**
 * This dragged as files: those [paths] gives when the drag starts (none: no drag), as the browser's own drag, showing
 * [picture] under the pointer. [onStart] is told as it starts, and [onEnd] what the target made of it, once it is
 * over, where it says.
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
	picture: () -> DragPicture,
	onStart: () -> Unit,
	onEnd: (Transfer?) -> Unit,
	carried: () -> Carried? = { null },
): Modifier {
	val window = LocalNativeWindow.current
	val files by rememberUpdatedState(paths)
	val shown by rememberUpdatedState(picture)
	val entries by rememberUpdatedState(carried)
	val start by rememberUpdatedState(onStart)
	val end by rememberUpdatedState(onEnd)

	// One for as long as this is drawn, reading what it is given as it is by then: the modifier is then the same from
	// one drawing to the next.
	val begin =
		remember(window) {
			{
				val dragged = files()

				val outgoing =
					if (dragged.isEmpty())
						entries()?.let(EntriesOut::of)?.let { out ->
							// Other programs are given their names.
							Outgoing(
								Dragged.Entries(out),
								emptyList(),
								text =
									out.entries.joinToString("\n") {
										it.name
									},
								picture = shown(),
							)
						}
					else
						Outgoing(Dragged.Files(dragged), dragged, picture = shown())

				if (window != null && outgoing != null) {
					start()
					window.startDrag(outgoing) { end(null) }
				}
			}
		}

	return this then DragSourceElement(begin)
}

/** A drag source that starts as [DragSourceNode] says. */
private data class DragSourceElement(
	val begin: () -> Unit,
) : ModifierNodeElement<DragSourceNode>() {
	override fun create(): DragSourceNode = DragSourceNode(begin)

	override fun update(node: DragSourceNode) {
		node.begin = begin
	}

	override fun InspectorInfo.inspectableProperties() {
		name = "filesDragged"
	}
}

/** What starts a drag: the first button held, and the pointer moved [DRAG_SLOP] from where it was pressed. */
private class DragSourceNode(
	var begin: () -> Unit,
) : DelegatingNode() {
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
							// The row stays where it is, as on most desktops: what is dragged is shown by the
							// picture the drag carries.
							begin()
							break
						}
					}
				}
			},
		)
	}
}

/** A drop about to be made: how many files (where it is known), made what (where that is), where. */
@Immutable
data class Dropping(
	val count: Int?,
	val transfer: Transfer?,
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
	val folder by rememberUpdatedState(into)
	val mac = LocalNativeWindow.current?.video == Video.Cocoa

	val target =
		remember(spot) {
			object : DropTarget {
				fun same(paths: List<Path>): Boolean {
					val first = paths.firstOrNull()
					val there = folder

					return first == null || there == null || volumes(first, there)
				}

				fun dropping(over: DragOver): Dropping? =
					when (over) {
						// Another application's files, which are known once they are dropped.
						DragOver.Foreign -> Dropping(null, null, folder)
						is DragOver.Own ->
							when (val local = over.local) {
								is Dragged.Files ->
									Dropping(
										local.paths.size,
										transferOf(askedOf(over.keys, mac), same(local.paths)),
										folder,
									)
								// Entries of a place are brought out, which leaves them where they were: a copy.
								is Dragged.Entries ->
									Dropping(local.entries.entries.size, Transfer.Copy, folder).takeIf {
										bring !=
											null
									}
								else -> null
							}
					}

				override fun hover(over: DragOver?) = hover(over?.let(::dropping))

				override fun effect(over: DragOver): DropEffect? =
					when (dropping(over)?.transfer) {
						Transfer.Copy -> DropEffect.Copy
						Transfer.Move -> DropEffect.Move
						Transfer.Link -> DropEffect.Link
						null -> null
					}

				override fun drop(dropped: Dropped): Boolean =
					when (dropped) {
						is Dropped.Files ->
							true.also {
								drop(dropped.paths, transferOf(Asked.Default, same(dropped.paths)))
							}
						is Dropped.Text -> false
						is Dropped.Own ->
							when (val local = dropped.local) {
								is Dragged.Files ->
									true.also {
										drop(local.paths, transferOf(askedOf(dropped.keys, mac), same(local.paths)))
									}
								is Dragged.Entries -> bring?.let { it(local.entries) } != null
								else -> false
							}
					}
			}
		}

	return dropTarget(target)
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
		// Another application's files: what a drop makes of them is known once they are, and where they come from.
		dropping.transfer == null -> "Drop $what $where"
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
			Modifier.border(2.dp, palette.accent, RoundedCornerShape(Radius.medium))
		else
			Modifier,
	)
}
