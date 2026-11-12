package prism.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import prism.preferences.DateFormat
import prism.preferences.Fonts
import prism.preferences.Rows
import prism.preferences.TextSizes

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
	/** A control's fill while it is pressed. */
	val pressed: Color,
	/** The ring around what the keyboard is on. */
	val focus: Color,
	/** What is drawn over the picker behind a notice. */
	val scrim: Color,
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
				pressed = Color(0xFFE2E2E2),
				focus = Color(0xFF1B1B1B),
				scrim = Color(0x66000000),
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
				pressed = Color(0xFF383838),
				focus = Color(0xFFFFFFFF),
				scrim = Color(0x99000000),
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
	/** These, in [family]. */
	fun inFamily(family: FontFamily?): Type =
		if (family == null)
			this
		else
			Type(
				body.copy(fontFamily = family),
				muted.copy(fontFamily = family),
				heading.copy(fontFamily = family),
				title.copy(fontFamily = family),
				button.copy(fontFamily = family),
			)

	companion object {
		/**
		 * The styles for [palette], their text [size] points (sp) and the rest of them in proportion, in [family] (the
		 * default, where null).
		 */
		fun of(
			palette: Palette,
			size: Float = TextSizes.DEFAULT,
			family: FontFamily? = null,
		): Type {
			fun sp(at13: Float) = (at13 * size / TextSizes.DEFAULT).sp

			return Type(
				body = TextStyle(fontSize = sp(13f), lineHeight = sp(18f), color = palette.text),
				muted =
					TextStyle(
						fontSize = sp(12f),
						lineHeight = sp(16f),
						color = palette.textMuted,
					),
				heading =
					TextStyle(
						fontSize = sp(12f),
						lineHeight = sp(16f),
						color = palette.textFaint,
						fontWeight = FontWeight.SemiBold,
					),
				title =
					TextStyle(
						fontSize = sp(13f),
						lineHeight = sp(18f),
						color = palette.text,
						fontWeight = FontWeight.SemiBold,
					),
				button =
					TextStyle(
						fontSize = sp(13f),
						lineHeight = sp(18f),
						fontWeight = FontWeight.Medium,
					),
			).inFamily(family)
		}
	}
}

/** How large the text of each part of the window is, as the user's preferences say. */
val LocalTextSizes = staticCompositionLocalOf { TextSizes() }

/** The font of each part of the window, as the user's preferences name them: the default's, where null. */
@Immutable
data class FontFamilies(
	val general: FontFamily? = null,
	val listing: FontFamily? = null,
	val menus: FontFamily? = null,
) {
	companion object {
		/** The families [fonts] names, each as the system has it, in the weights prism draws in. */
		fun of(fonts: Fonts): FontFamilies =
			FontFamilies(familyOf(fonts.general), familyOf(fonts.listing), familyOf(fonts.menus))

		@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
		private fun familyOf(name: String?): FontFamily? =
			name?.takeIf { it.isNotBlank() }?.let { family ->
				FontFamily(
					listOf(
						FontWeight.Normal,
						FontWeight.Medium,
						FontWeight.SemiBold,
						FontWeight.Bold,
					).map {
						SystemFont(family, it)
					},
				)
			}
	}
}

val LocalFontFamilies = staticCompositionLocalOf { FontFamilies() }

/** The families of fonts the system has, by name, in order. */
fun systemFontFamilies(): List<String> =
	org.jetbrains.skia.FontMgr.default
		.let { fonts ->
			(0 until fonts.familiesCount).map(fonts::getFamilyName)
		}.filter { it.isNotBlank() }
		.distinct()
		.sortedBy { it.lowercase() }

/** How much larger [size] is than the text the parts are measured for: what a part's heights are scaled by. */
fun scaleOf(size: Float): Float = size / TextSizes.DEFAULT

/** How dates are written, as the user's preferences say. */
val LocalDateFormat = staticCompositionLocalOf { DateFormat.Written }

/** How tall the list's rows are, and how large their icons. */
val LocalRows = staticCompositionLocalOf { Rows.Larger }

/** How many times its usual size the sidebar's quick access, and the window that arranges it, are drawn. */
val LocalSidebarScale = staticCompositionLocalOf { 1f }

/**
 * [content] drawn [scale] times its usual size, as a web browser zooms a page: its text, and everything measured in dp,
 * so that it lays itself out again at that size rather than being stretched.
 */
@Composable
fun Scaled(scale: Float, content: @Composable () -> Unit) {
	val density = LocalDensity.current

	CompositionLocalProvider(
		LocalDensity provides Density(density.density * scale, density.fontScale),
		content = content,
	)
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
	val button = 112.dp

	/** A filter's chip, and the checkbox beside them. */
	val chip = 28.dp

	/** The row of filters above the listing. */
	val filters = 44.dp

	/** A gallery's tile, its picture, and the least width a tile takes. */
	val tile = 136.dp
	val tileHeight = 152.dp
	val picture = 96.dp

	/** How far outside a control the ring around what the keyboard is on is drawn. */
	val ring = 2.dp
	val nameColumn = 0.46f
	val dateColumn = 0.22f
	val typeColumn = 0.18f
	val sizeColumn = 0.14f

	/** Where a search's result is, in the list of results. */
	val folderColumn = 0.34f

	/** The room each side of a column's values, so two columns' never meet; and at the list's two edges. */
	val cell = 8.dp
	val edge = 12.dp

	/** The room between a gallery's tiles, and around them: somewhere to start a rectangle, or open the folder's menu. */
	val tileGap = 16.dp
	val tilePad = 12.dp
}

/** How tall a list's row is, at its usual size. */
fun rowHeightOf(rows: Rows): Dp =
	when (rows) {
		Rows.Larger -> 46.dp
		Rows.Compact -> 30.dp
	}

/** How large a list's row's icon is, at its usual size: larger, as Thunar draws them, or as small as the text. */
fun rowIconOf(rows: Rows): Dp =
	when (rows) {
		Rows.Larger -> 34.dp
		Rows.Compact -> 20.dp
	}

val LocalPalette = staticCompositionLocalOf { Palette.Light }
val LocalType = staticCompositionLocalOf { Type.of(Palette.Light) }

/** The picker's palette and type around [content], and Material's theme bound to them for the few widgets it uses. */
@Composable
fun BrowserTheme(
	palette: Palette = Palette.Light,
	sizes: TextSizes = TextSizes(),
	fonts: Fonts = Fonts(),
	content: @Composable () -> Unit,
) {
	val families = remember(fonts) { FontFamilies.of(fonts) }

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
		LocalType provides Type.of(palette, sizes.general, families.general),
		LocalTextSizes provides sizes,
		LocalFontFamilies provides families,
	) {
		MaterialTheme(colorScheme = scheme, content = content)
	}
}
