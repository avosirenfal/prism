package barion.picker

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The picker's look, in one place: a palette for light and one for dark (the quiet greys and the single blue accent
 * of a desktop's own dialogs), the type sizes, and the few dimensions every part shares. Everything visual reads
 * from here, so the whole picker changes together, and a palette is data, so a theme from the desktop (the portal's
 * own colour scheme setting) is a palette made from it.
 */

/** Every colour the picker draws with. */
@Immutable
data class Palette(
	val window: Color,
	val pane: Color,
	val surface: Color,
	val border: Color,
	val divider: Color,
	val text: Color,
	val textMuted: Color,
	val textFaint: Color,
	val accent: Color,
	val onAccent: Color,
	val hover: Color,
	val chosen: Color,
	val chosenEdge: Color,
	val field: Color,
	val fieldEdge: Color,
	val folder: Color,
	val folderFront: Color,
	val page: Color,
	val pageEdge: Color,
	val image: Color,
	val audio: Color,
	val video: Color,
	val archive: Color,
	val code: Color,
	val writing: Color,
	val document: Color,
	val pdf: Color,
	val isDark: Boolean,
) {
	/** The colour [category]'s icon is marked with. */
	fun mark(category: Category): Color =
		when (category) {
			Category.Folder -> folder
			Category.Image -> image
			Category.Audio -> audio
			Category.Video -> video
			Category.Archive -> archive
			Category.Code -> code
			Category.Text -> writing
			Category.Document -> document
			Category.Pdf -> pdf
			Category.Link, Category.Other -> textMuted
		}

	companion object {
		val Light =
			Palette(
				window = Color(0xFFF3F3F3),
				pane = Color(0xFFF0F0F0),
				surface = Color(0xFFFCFCFC),
				border = Color(0xFFE0E0E0),
				divider = Color(0xFFEAEAEA),
				text = Color(0xFF1B1B1B),
				textMuted = Color(0xFF5C5C5C),
				textFaint = Color(0xFF8A8A8A),
				accent = Color(0xFF005FB8),
				onAccent = Color(0xFFFFFFFF),
				hover = Color(0xFFEDEDED),
				chosen = Color(0xFFDDEBF7),
				chosenEdge = Color(0xFF99C2E8),
				field = Color(0xFFFFFFFF),
				fieldEdge = Color(0xFFD0D0D0),
				folder = Color(0xFFE8B03E),
				folderFront = Color(0xFFF7CB5E),
				page = Color(0xFFFFFFFF),
				pageEdge = Color(0xFFB9B9B9),
				image = Color(0xFF2E9E6B),
				audio = Color(0xFFD94E8F),
				video = Color(0xFF7A5AE0),
				archive = Color(0xFFA36A2E),
				code = Color(0xFF2B7BD6),
				writing = Color(0xFF6B7785),
				document = Color(0xFF2A5FAF),
				pdf = Color(0xFFD13B3B),
				isDark = false,
			)

		val Dark =
			Palette(
				window = Color(0xFF202020),
				pane = Color(0xFF1C1C1C),
				surface = Color(0xFF272727),
				border = Color(0xFF3A3A3A),
				divider = Color(0xFF323232),
				text = Color(0xFFF2F2F2),
				textMuted = Color(0xFFB8B8B8),
				textFaint = Color(0xFF8C8C8C),
				accent = Color(0xFF60CDFF),
				onAccent = Color(0xFF00203A),
				hover = Color(0xFF2F2F2F),
				chosen = Color(0xFF16384F),
				chosenEdge = Color(0xFF2E6A92),
				field = Color(0xFF2D2D2D),
				fieldEdge = Color(0xFF454545),
				folder = Color(0xFFD9A23A),
				folderFront = Color(0xFFF0C25A),
				page = Color(0xFF3A3A3A),
				pageEdge = Color(0xFF5A5A5A),
				image = Color(0xFF4CC28A),
				audio = Color(0xFFF07AB0),
				video = Color(0xFFA48BFF),
				archive = Color(0xFFD29A5A),
				code = Color(0xFF5AA8FF),
				writing = Color(0xFF9AA6B4),
				document = Color(0xFF6E9EF0),
				pdf = Color(0xFFF0645F),
				isDark = true,
			)
	}
}

/** The type sizes, all from one body size. */
@Immutable
data class Type(
	val body: TextStyle,
	val muted: TextStyle,
	val heading: TextStyle,
	val title: TextStyle,
	val button: TextStyle,
) {
	companion object {
		fun of(palette: Palette): Type =
			Type(
				body = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = palette.text),
				muted = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = palette.textMuted),
				heading =
					TextStyle(
						fontSize = 12.sp,
						lineHeight = 16.sp,
						color = palette.textFaint,
						fontWeight = FontWeight.SemiBold,
					),
				title =
					TextStyle(
						fontSize = 13.sp,
						lineHeight = 18.sp,
						color = palette.text,
						fontWeight = FontWeight.SemiBold,
					),
				button =
					TextStyle(
						fontSize = 13.sp,
						lineHeight = 18.sp,
						fontWeight = FontWeight.Medium,
					),
			)
	}
}

/** The dimensions the parts share. */
object Dims {
	val sidebar = 228.dp
	val toolbar = 52.dp
	val row = 30.dp
	val header = 30.dp
	val footer = 104.dp
	val icon = 20.dp
	val gap = 8.dp
	val gapSmall = 4.dp
	val radius = 6.dp
	val control = 32.dp
	val nameColumn = 0.46f
	val dateColumn = 0.22f
	val typeColumn = 0.18f
	val sizeColumn = 0.14f
}

val LocalPalette = staticCompositionLocalOf { Palette.Light }
val LocalType = staticCompositionLocalOf { Type.of(Palette.Light) }

/** The picker's palette and type around [content], and Material's theme bound to them for the few widgets it uses. */
@Composable
fun PickerTheme(palette: Palette = Palette.Light, content: @Composable () -> Unit) {
	val scheme =
		if (palette.isDark)
			darkColorScheme(
				primary = palette.accent,
				onPrimary = palette.onAccent,
				surface = palette.surface,
				onSurface = palette.text,
				background = palette.window,
			)
		else
			lightColorScheme(
				primary = palette.accent,
				onPrimary = palette.onAccent,
				surface = palette.surface,
				onSurface = palette.text,
				background = palette.window,
			)

	CompositionLocalProvider(
		LocalPalette provides palette,
		LocalType provides Type.of(palette),
	) {
		MaterialTheme(colorScheme = scheme, content = content)
	}
}
