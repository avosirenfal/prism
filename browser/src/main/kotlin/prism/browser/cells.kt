package prism.browser

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/*
 * Text that a listing draws in every row, laid out once and drawn from there. A folder's rows repeat their cells far more
 * than they differ (a month's name, a year, an hour, a type), and laying out a line of text is most of what a row costs
 * as it comes into sight: a list dragged by its bar brings a screenful of rows at a time. So the cells' layouts are kept
 * in one cache ([LocalCells], which [BrowserTheme] gives), and a row lays out only what no row before it showed.
 *
 * A cell is one line, cut short with an ellipsis where it is wider than its room; or runs of text side by side, each in
 * a width of its own or its own width, drawn by one node rather than one for each: a date's parts, each in the width of
 * the widest of its kind; or a number in figures of one width ([figuresOf]), a figure at a time, so that every size is
 * drawn from the same ten figures and the words beside them, whatever the size.
 */

/** Where cells find their text laid out already. */
val LocalCells =
	staticCompositionLocalOf<TextMeasurer> { error("no cells: drawn outside BrowserTheme") }

/** How many lines of text [LocalCells] keeps laid out. */
internal const val CELLS_KEPT = 1024

/** A run of a cell's text: [text], in [width] (as wide as it is, where null), aligned within it as [align] says. */
@Immutable
data class Run(
	val text: String,
	val width: Dp? = null,
	val align: TextAlign = TextAlign.Start,
)

/**
 * [text] in figures of one width (tabular), as runs a figure at a time between the words around them: `4,096 bytes` as
 * 4, `,`, 0, 9, 6 and ` bytes`. Drawn in a style whose figures are all as wide, it looks as it would laid out whole.
 */
fun figuresOf(text: String): List<Run> =
	Regex("\\d|\\D+").findAll(text).map { Run(it.value) }.toList()

/**
 * [text] in [style], one line, cut short with an ellipsis where it is wider than the room it is given, and as [align]
 * says within it; read by tests and assistive tools as itself.
 */
@Composable
fun CellText(
	text: String,
	style: TextStyle,
	modifier: Modifier = Modifier,
	align: TextAlign = TextAlign.Start,
) = CellText(listOf(Run(text)), style, text, modifier, align)

/**
 * [runs] side by side in [style], read as [said], and together as [align] says within the room they are given. Where
 * they are wider than it, they are laid out as one line instead, cut short with an ellipsis.
 */
@Composable
fun CellText(
	runs: List<Run>,
	style: TextStyle,
	said: String,
	modifier: Modifier = Modifier,
	align: TextAlign = TextAlign.Start,
) = Spacer(modifier.then(CellElement(runs, style, said, align, LocalCells.current)))

private data class CellElement(
	val runs: List<Run>,
	val style: TextStyle,
	val said: String,
	val align: TextAlign,
	val cells: TextMeasurer,
) : ModifierNodeElement<CellNode>() {
	override fun create() = CellNode(this)

	override fun update(node: CellNode) = node.update(this)
}

/** A run laid out, and where in the cell it starts. */
private class Laid(
	val layout: TextLayoutResult,
	val x: Int,
)

private class CellNode(
	private var cell: CellElement,
) : Modifier.Node(),
	LayoutModifierNode,
	DrawModifierNode,
	SemanticsModifierNode {
	/** Each run's style: the cell's, aligned as the run is. Made once, since the cache compares styles. */
	private var styles = stylesOf(cell)
	private var laid: List<Laid> = emptyList()

	fun update(cell: CellElement) {
		val was = this.cell

		this.cell = cell
		if (cell.runs != was.runs || cell.style != was.style || cell.align != was.align ||
			cell.cells !== was.cells
		) {
			styles = stylesOf(cell)
			invalidateMeasurement()
			invalidateDraw()
		}
		if (cell.said != was.said)
			invalidateSemantics()
	}

	override fun MeasureScope.measure(
		measurable: Measurable,
		constraints: Constraints,
	): MeasureResult {
		// The cache tells one layout from another by what they were laid out with, the density among it: as a value,
		// since this scope is each node's own, and would have every node lay out its text anew.
		val density = Density(density, fontScale)
		val fixed = constraints.maxWidth.takeIf { constraints.hasFixedWidth }
		val bounded = constraints.maxWidth.takeIf { constraints.hasBoundedWidth }

		fun laidOut(text: String, style: TextStyle, room: Int?): TextLayoutResult =
			cell.cells.measure(
				text,
				style,
				overflow = TextOverflow.Ellipsis,
				maxLines = 1,
				constraints =
					when {
						room != null -> Constraints.fixedWidth(room)
						bounded != null -> Constraints(maxWidth = bounded)
						else -> Constraints()
					},
				layoutDirection = layoutDirection,
				density = density,
			)

		val runs = cell.runs

		// Laid out whole, text aligns itself within the room it is laid out in, by its style: one run with no width
		// of its own, or runs that are wider than the cell together, as one line cut short. Runs side by side are
		// aligned together, as the cell says.
		fun whole(text: String, style: TextStyle) = listOf(Laid(laidOut(text, style, fixed), 0))

		val single = runs.singleOrNull()?.takeIf { it.width == null }

		val side =
			if (single != null) {
				null
			} else {
				var x = 0

				runs
					.mapIndexed { index, run ->
						val room = run.width?.roundToPx()
						val layout = laidOut(run.text, styles[index], room)

						Laid(layout, x).also { x += room ?: layout.size.width }
					}.let { it to x }
					.takeIf { (_, content) -> bounded == null || content <= bounded }
			}

		val pieces =
			side?.first
				?: single?.let { whole(it.text, styles[0]) }
				?: whole(runs.joinToString("") { it.text }, styleOf(cell.style, cell.align))

		val content =
			side?.second ?: pieces
				.single()
				.layout.size.width
		val width = constraints.constrainWidth(fixed ?: content)
		val height =
			constraints.constrainHeight(pieces.maxOfOrNull { it.layout.size.height } ?: 0)

		val offset =
			if (side == null)
				0
			else
				when (cell.align) {
					TextAlign.End, TextAlign.Right -> width - content
					TextAlign.Center -> (width - content) / 2
					else -> 0
				}.coerceAtLeast(0)

		laid = pieces.map { Laid(it.layout, it.x + offset) }

		val placeable = measurable.measure(Constraints.fixed(width, height))

		return layout(width, height) { placeable.place(0, 0) }
	}

	override fun ContentDrawScope.draw() {
		for (run in laid)
			drawText(run.layout, topLeft = Offset(run.x.toFloat(), 0f))
	}

	override fun SemanticsPropertyReceiver.applySemantics() {
		text = AnnotatedString(cell.said)
	}

	private companion object {
		fun stylesOf(cell: CellElement): List<TextStyle> =
			cell.runs.map { run ->
				// A cell of one run is aligned as the cell is; each of several, within its own width, as it is.
				styleOf(
					cell.style,
					if (cell.runs.size == 1 &&
						run.width == null
					)
						cell.align
					else
						run.align,
				)
			}

		fun styleOf(style: TextStyle, align: TextAlign): TextStyle =
			if (style.textAlign == align)
				style
			else
				style.copy(textAlign = align)
	}
}
