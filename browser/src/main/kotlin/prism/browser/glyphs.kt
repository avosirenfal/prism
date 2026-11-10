package prism.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/*
 * The picker's icons, drawn: each a few shapes on a 24-unit grid, scaled to whatever size it is shown at, so they are
 * crisp at any density and take their colours from the palette. Drawn rather than taken from an icon font so that the
 * picker depends on nothing it does not need for the twenty shapes it shows.
 */

/** Every icon the picker draws. */
enum class Glyph {
	Folder,
	File,
	Back,
	Forward,
	Up,
	Chevron,
	ChevronDown,
	Search,
	Home,
	Desktop,
	Documents,
	Downloads,
	Pictures,
	Music,
	Videos,
	Drive,
	SortUp,
	SortDown,
	Check,
	ViewList,
	ViewGrid,
	Filter,
	Menu,
}

/** [glyph] at [size], in [tint]; a file's icon is marked with [category]'s colour. */
@Composable
fun GlyphIcon(
	glyph: Glyph,
	tint: Color,
	size: Dp,
	modifier: Modifier = Modifier,
	category: Category = Category.Other,
) {
	val palette = LocalPalette.current

	Canvas(modifier.size(size)) {
		val unit = this.size.minDimension / 24f

		when (glyph) {
			Glyph.Folder -> folder(unit, palette)
			Glyph.File -> file(unit, palette, category)
			Glyph.Back -> arrow(unit, tint, Offset(-1f, 0f))
			Glyph.Forward -> arrow(unit, tint, Offset(1f, 0f))
			Glyph.Up -> arrow(unit, tint, Offset(0f, -1f))
			Glyph.Chevron -> stroked(unit, tint, listOf(9f to 6f, 15f to 12f, 9f to 18f))
			Glyph.ChevronDown -> stroked(unit, tint, listOf(6f to 9f, 12f to 15f, 18f to 9f))
			Glyph.Search -> search(unit, tint)
			Glyph.Home -> home(unit, tint)
			Glyph.Desktop -> desktop(unit, tint)
			Glyph.Documents -> documents(unit, tint)
			Glyph.Downloads -> downloads(unit, tint)
			Glyph.Pictures -> pictures(unit, tint)
			Glyph.Music -> music(unit, tint)
			Glyph.Videos -> videos(unit, tint)
			Glyph.Drive -> drive(unit, tint)
			Glyph.SortUp -> stroked(unit, tint, listOf(7f to 14f, 12f to 9f, 17f to 14f))
			Glyph.SortDown -> stroked(unit, tint, listOf(7f to 10f, 12f to 15f, 17f to 10f))
			Glyph.Check -> stroked(unit, tint, listOf(5f to 12.5f, 10f to 17.5f, 19f to 7f))
			Glyph.ViewList -> viewList(unit, tint)
			Glyph.ViewGrid -> viewGrid(unit, tint)
			Glyph.Filter ->
				stroked(
					unit,
					tint,
					listOf(
						4f to 5.5f,
						20f to 5.5f,
						14f to 12.5f,
						14f to 19f,
						10f to 17f,
						10f to 12.5f,
						4f to 5.5f,
					),
				)
			// Three lines across: a window's own menu, as desktops draw it.
			Glyph.Menu ->
				for (y in listOf(6.5f, 12f, 17.5f))
					stroked(unit, tint, listOf(4.5f to y, 19.5f to y))
		}
	}
}

private fun DrawScope.at(unit: Float, x: Float, y: Float) = Offset(x * unit, y * unit)

private fun DrawScope.line(unit: Float) =
	Stroke(width = 1.7f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** A polyline through [points], on the grid. */
private fun DrawScope.stroked(
	unit: Float,
	tint: Color,
	points: List<Pair<Float, Float>>,
) {
	val path =
		Path().apply {
			points.forEachIndexed { index, (x, y) ->
				if (index == 0)
					moveTo(x * unit, y * unit)
				else
					lineTo(x * unit, y * unit)
			}
		}

	drawPath(path, tint, style = line(unit))
}

private fun DrawScope.arrow(unit: Float, tint: Color, toward: Offset) {
	// Drawn pointing right, then turned: the shaft and the head.
	val turn =
		when {
			toward.x < 0 -> 180f
			toward.y < 0 -> 270f
			else -> 0f
		}

	rotate(turn) {
		stroked(unit, tint, listOf(5f to 12f, 19f to 12f))
		stroked(unit, tint, listOf(13f to 6f, 19f to 12f, 13f to 18f))
	}
}

private inline fun DrawScope.rotate(degrees: Float, block: DrawScope.() -> Unit) =
	drawContext.transform.let { transform ->
		transform.rotate(degrees, center)
		block()
		transform.rotate(-degrees, center)
	}

private fun DrawScope.folder(unit: Float, palette: Palette) {
	val back =
		Path().apply {
			moveTo(2.5f * unit, 6.5f * unit)
			quadraticTo(2.5f * unit, 4.5f * unit, 4.5f * unit, 4.5f * unit)
			lineTo(9f * unit, 4.5f * unit)
			lineTo(11f * unit, 6.5f * unit)
			lineTo(19.5f * unit, 6.5f * unit)
			quadraticTo(21.5f * unit, 6.5f * unit, 21.5f * unit, 8.5f * unit)
			lineTo(21.5f * unit, 18f * unit)
			lineTo(2.5f * unit, 18f * unit)
			close()
		}

	drawPath(back, palette.folder)
	drawRoundRect(
		palette.folderFront,
		topLeft = at(unit, 2.5f, 8.5f),
		size = Size(19f * unit, 11f * unit),
		cornerRadius = CornerRadius(2f * unit),
	)
}

private fun DrawScope.file(unit: Float, palette: Palette, category: Category) {
	val page =
		Path().apply {
			moveTo(6f * unit, 2.5f * unit)
			lineTo(14f * unit, 2.5f * unit)
			lineTo(19f * unit, 7.5f * unit)
			lineTo(19f * unit, 20f * unit)
			quadraticTo(19f * unit, 21.5f * unit, 17.5f * unit, 21.5f * unit)
			lineTo(6.5f * unit, 21.5f * unit)
			quadraticTo(5f * unit, 21.5f * unit, 5f * unit, 20f * unit)
			lineTo(5f * unit, 4f * unit)
			quadraticTo(5f * unit, 2.5f * unit, 6f * unit, 2.5f * unit)
			close()
		}

	val fold =
		Path().apply {
			moveTo(14f * unit, 2.5f * unit)
			lineTo(14f * unit, 7.5f * unit)
			lineTo(19f * unit, 7.5f * unit)
		}
	val mark = palette.mark(category)

	drawPath(page, palette.page)
	drawPath(page, palette.pageEdge, style = Stroke(1.1f * unit, join = StrokeJoin.Round))
	drawPath(fold, palette.pageEdge, style = Stroke(1.1f * unit, join = StrokeJoin.Round))

	when (category) {
		Category.Image -> {
			drawCircle(mark, 1.4f * unit, at(unit, 9.5f, 11f))
			drawPath(
				Path().apply {
					moveTo(7f * unit, 18.5f * unit)
					lineTo(11f * unit, 13.5f * unit)
					lineTo(13.5f * unit, 16f * unit)
					lineTo(15f * unit, 14.5f * unit)
					lineTo(17f * unit, 18.5f * unit)
					close()
				},
				mark,
			)
		}
		Category.Audio -> {
			drawCircle(mark, 1.9f * unit, at(unit, 10f, 17f))
			stroked(unit, mark, listOf(11.8f to 17f, 11.8f to 10.5f, 15.5f to 9.5f))
		}
		Category.Video ->
			drawPath(
				Path().apply {
					moveTo(9.5f * unit, 11f * unit)
					lineTo(15.5f * unit, 14.5f * unit)
					lineTo(9.5f * unit, 18f * unit)
					close()
				},
				mark,
			)
		Category.Archive ->
			for (row in 0 until 4)
				drawRect(
					mark,
					at(
						unit,
						if (row % 2 ==
							0
						) 10.5f else 12f,
						9f + row * 2.4f,
					),
					Size(1.6f * unit, 1.6f * unit),
				)
		Category.Code -> {
			stroked(unit, mark, listOf(10f to 11.5f, 8f to 14.5f, 10f to 17.5f))
			stroked(unit, mark, listOf(14f to 11.5f, 16f to 14.5f, 14f to 17.5f))
		}
		Category.Text, Category.Document ->
			for (row in 0 until 4)
				drawLine(
					mark,
					at(unit, 8f, 11f + row * 2.4f),
					at(unit, if (row == 3) 13f else 16f, 11f + row * 2.4f),
					strokeWidth = 1.3f * unit,
					cap = StrokeCap.Round,
				)
		Category.Pdf ->
			drawRoundRect(
				mark,
				at(unit, 6.5f, 14f),
				Size(11f * unit, 4.5f * unit),
				CornerRadius(1f * unit),
			)
		Category.Link ->
			stroked(
				unit,
				mark,
				listOf(
					8.5f to 17.5f,
					15.5f to 10.5f,
					11f to 10.5f,
					15.5f to 10.5f,
					15.5f to 15f,
				),
			)
		Category.Folder, Category.Other -> Unit
	}
}

private fun DrawScope.search(unit: Float, tint: Color) {
	drawCircle(tint, 6f * unit, at(unit, 10.5f, 10.5f), style = line(unit))
	drawLine(
		tint,
		at(unit, 15f, 15f),
		at(unit, 20f, 20f),
		strokeWidth = 1.7f * unit,
		cap = StrokeCap.Round,
	)
}

private fun DrawScope.home(unit: Float, tint: Color) {
	stroked(unit, tint, listOf(4f to 11f, 12f to 4.5f, 20f to 11f))
	stroked(unit, tint, listOf(6.5f to 9.5f, 6.5f to 19.5f, 17.5f to 19.5f, 17.5f to 9.5f))
	stroked(
		unit,
		tint,
		listOf(10.5f to 19.5f, 10.5f to 14.5f, 13.5f to 14.5f, 13.5f to 19.5f),
	)
}

private fun DrawScope.desktop(unit: Float, tint: Color) {
	drawRoundRect(
		tint,
		at(unit, 3.5f, 4.5f),
		Size(17f * unit, 11.5f * unit),
		CornerRadius(1.5f * unit),
		style = line(unit),
	)
	stroked(unit, tint, listOf(12f to 16f, 12f to 19.5f))
	stroked(unit, tint, listOf(8f to 19.5f, 16f to 19.5f))
}

private fun DrawScope.documents(unit: Float, tint: Color) {
	stroked(
		unit,
		tint,
		listOf(
			14f to 3.5f,
			6.5f to 3.5f,
			6.5f to 20.5f,
			17.5f to 20.5f,
			17.5f to 7f,
			14f to 3.5f,
			14f to 7f,
			17.5f to 7f,
		),
	)
	stroked(unit, tint, listOf(9.5f to 12f, 14.5f to 12f))
	stroked(unit, tint, listOf(9.5f to 15.5f, 14.5f to 15.5f))
}

private fun DrawScope.downloads(unit: Float, tint: Color) {
	stroked(unit, tint, listOf(12f to 4f, 12f to 14.5f))
	stroked(unit, tint, listOf(7.5f to 10f, 12f to 14.5f, 16.5f to 10f))
	stroked(unit, tint, listOf(5f to 15.5f, 5f to 19.5f, 19f to 19.5f, 19f to 15.5f))
}

private fun DrawScope.pictures(unit: Float, tint: Color) {
	drawRoundRect(
		tint,
		at(unit, 3.5f, 5f),
		Size(17f * unit, 14f * unit),
		CornerRadius(2f * unit),
		style = line(unit),
	)
	drawCircle(tint, 1.5f * unit, at(unit, 9f, 10f))
	stroked(
		unit,
		tint,
		listOf(
			4f to 17f,
			10f to 12.5f,
			14f to 15.5f,
			16f to 13.5f,
			20f to 17f,
		),
	)
}

private fun DrawScope.music(unit: Float, tint: Color) {
	drawCircle(tint, 2.3f * unit, at(unit, 7.5f, 17f), style = line(unit))
	drawCircle(tint, 2.3f * unit, at(unit, 16.5f, 15.5f), style = line(unit))
	stroked(unit, tint, listOf(9.8f to 17f, 9.8f to 6.5f, 18.8f to 5f, 18.8f to 15.5f))
}

private fun DrawScope.videos(unit: Float, tint: Color) {
	drawRoundRect(
		tint,
		at(unit, 3f, 6f),
		Size(13f * unit, 12f * unit),
		CornerRadius(2f * unit),
		style = line(unit),
	)
	stroked(unit, tint, listOf(16f to 10.5f, 21f to 7.5f, 21f to 16.5f, 16f to 13.5f))
}

private fun DrawScope.drive(unit: Float, tint: Color) {
	drawRoundRect(
		tint,
		at(unit, 3f, 8f),
		Size(18f * unit, 9f * unit),
		CornerRadius(2f * unit),
		style = line(unit),
	)
	drawCircle(tint, 1.1f * unit, at(unit, 17f, 12.5f))
	stroked(unit, tint, listOf(6.5f to 12.5f, 11.5f to 12.5f))
}

/** Rows: a short mark and a long line, three times. */
private fun DrawScope.viewList(unit: Float, tint: Color) {
	for (y in listOf(6.5f, 12f, 17.5f)) {
		stroked(unit, tint, listOf(4f to y, 5.5f to y))
		stroked(unit, tint, listOf(9f to y, 20f to y))
	}
}

/** Tiles: four squares with rounded corners. */
private fun DrawScope.viewGrid(unit: Float, tint: Color) {
	for (x in listOf(4f, 13f))
		for (y in listOf(4f, 13f))
			drawRoundRect(
				tint,
				topLeft = at(unit, x, y),
				size = Size(7f * unit, 7f * unit),
				cornerRadius = CornerRadius(1.5f * unit),
				style = line(unit),
			)
}
