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
import prism.preferences.Rows
import prism.preferences.TextPart
import prism.preferences.Typography

/*
 * How prism looks, in one place: the palette, light and dark; the type, a scale of styles from one size for each part
 * of a window; and the spacing every part keeps to. Everything visual reads from here, so the whole of prism changes
 * together, and the controls (elements.kt) and the layouts (layout.kt) are built from these alone: nothing is spaced,
 * sized or coloured anywhere else.
 */

/**
 * Every colour prism draws with, by what it is for. The surfaces go from the window's frame inward: [window] (the
 * toolbar, the status bar, a dialog), [pane] (a sidebar), [surface] (the listing), [canvas] (what a page's cards sit
 * on), [raised] (a card), and [overlay] (a menu, a popup); [sunken] is a track below its surface (a segmented control's,
 * whose chosen segment is a [thumb]). [hover] and [pressed] are translucent, so they show on any surface.
 */
@Immutable
data class Palette(
	val window: Color,
	val pane: Color,
	val surface: Color,
	val canvas: Color,
	val raised: Color,
	val overlay: Color,
	val sunken: Color,
	val thumb: Color,
	val border: Color,
	val borderStrong: Color,
	val divider: Color,
	val text: Color,
	val textMuted: Color,
	val textFaint: Color,
	val accent: Color,
	val accentHover: Color,
	val accentPressed: Color,
	val onAccent: Color,
	/** The accent as text and as a glyph's line: a link, the place shown in the sidebar. */
	val accentText: Color,
	val danger: Color,
	val dangerHover: Color,
	val onDanger: Color,
	val dangerText: Color,
	val hover: Color,
	/** A control's fill while it is pressed. */
	val pressed: Color,
	/** The ring around what the keyboard is on. */
	val focus: Color,
	/** What is drawn over a window behind a modal. */
	val scrim: Color,
	val shadow: Color,
	/** A chosen row's fill. */
	val chosen: Color,
	val field: Color,
	val fieldEdge: Color,
	val fieldEdgeHover: Color,
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
		/** Quiet greys, and one blue that is calm enough to fill a button: an IDE's light theme. */
		val Light =
			Palette(
				window = Color(0xFFF7F8FA),
				pane = Color(0xFFF2F3F5),
				surface = Color(0xFFFFFFFF),
				canvas = Color(0xFFF2F3F5),
				raised = Color(0xFFFFFFFF),
				overlay = Color(0xFFFFFFFF),
				sunken = Color(0xFFE9EAEE),
				thumb = Color(0xFFFFFFFF),
				border = Color(0xFFDFE1E5),
				borderStrong = Color(0xFFC9CCD6),
				divider = Color(0xFFEBECF0),
				text = Color(0xFF1E1F22),
				textMuted = Color(0xFF5A5D6B),
				textFaint = Color(0xFF8C8F9C),
				accent = Color(0xFF3574F0),
				accentHover = Color(0xFF2F68DA),
				accentPressed = Color(0xFF2A5CC2),
				onAccent = Color(0xFFFFFFFF),
				accentText = Color(0xFF2E5FC4),
				danger = Color(0xFFD13B3B),
				dangerHover = Color(0xFFBD3030),
				onDanger = Color(0xFFFFFFFF),
				dangerText = Color(0xFFC42B2B),
				hover = Color(0x0F1E1F22),
				pressed = Color(0x1C1E1F22),
				focus = Color(0xFF3574F0),
				scrim = Color(0x52000000),
				shadow = Color(0x33000000),
				chosen = Color(0xFFD5E3FE),
				field = Color(0xFFFFFFFF),
				fieldEdge = Color(0xFFC9CCD6),
				fieldEdgeHover = Color(0xFFA8ADBD),
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

		/** Charcoal greys, the same blue for what is pressed, a lighter one for what is pointed out. */
		val Dark =
			Palette(
				window = Color(0xFF2B2D30),
				pane = Color(0xFF27282B),
				surface = Color(0xFF1E1F22),
				canvas = Color(0xFF1E1F22),
				raised = Color(0xFF2B2D30),
				overlay = Color(0xFF2F3135),
				sunken = Color(0xFF1E1F22),
				thumb = Color(0xFF45484D),
				border = Color(0xFF393B40),
				borderStrong = Color(0xFF4E5157),
				divider = Color(0xFF35373B),
				text = Color(0xFFDFE1E5),
				textMuted = Color(0xFFA2A5AD),
				textFaint = Color(0xFF6F737A),
				accent = Color(0xFF3574F0),
				accentHover = Color(0xFF4682FA),
				accentPressed = Color(0xFF2E64D4),
				onAccent = Color(0xFFFFFFFF),
				accentText = Color(0xFF6B9BFA),
				danger = Color(0xFFC94F4F),
				dangerHover = Color(0xFFD65E5E),
				onDanger = Color(0xFFFFFFFF),
				dangerText = Color(0xFFF07A7A),
				hover = Color(0x14FFFFFF),
				pressed = Color(0x22FFFFFF),
				focus = Color(0xFF548AF7),
				scrim = Color(0x8C000000),
				shadow = Color(0x80000000),
				chosen = Color(0xFF2E436E),
				field = Color(0xFF1E1F22),
				fieldEdge = Color(0xFF4E5157),
				fieldEdgeHover = Color(0xFF6F737A),
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

/**
 * The room between things, in one scale: every gap and every inset is one of these, so that what is near is grouped
 * and what is far apart is not, the same way everywhere.
 */
object Space {
	val xxs = 2.dp
	val xs = 4.dp
	val s = 8.dp
	val m = 12.dp
	val l = 16.dp
	val xl = 24.dp
	val xxl = 32.dp
}

/** How round corners are: a check box's, a control's, and a card's or a popup's. */
object Radius {
	val small = 4.dp
	val medium = 6.dp
	val large = 10.dp
}

/**
 * The styles of one part's text, from its body's size, and the heights of what holds that text: a control (a button, a
 * field) and a row (of a list, a menu, the sidebar), each as tall as its line and its room above and below, so that
 * text made larger never outgrows what it is in.
 */
@Immutable
data class Type(
	val body: TextStyle,
	/** The body in a heavier weight: a row's name where it is the point, a label of a form. */
	val strong: TextStyle,
	/** What is said about something, beside it or under it: a description, a status. */
	val muted: TextStyle,
	/** A group's heading, over its rows: small, and faint. */
	val heading: TextStyle,
	/** A section's title. */
	val title: TextStyle,
	/** A page's or a dialog's title. */
	val headline: TextStyle,
	val button: TextStyle,
	/** A command's words, or a path, set as they are typed. */
	val mono: TextStyle,
	/** How tall a line of the body is. */
	val line: Dp,
) {
	/** How tall a control is: a button, a field, a choice. */
	val control: Dp get() = line + 14.dp

	/** How tall a row of a list is. */
	val row: Dp get() = line + 12.dp

	/** How large an icon beside the body is. */
	val icon: Dp get() = line - 2.dp

	companion object {
		/**
		 * The styles for [palette], their body [points] large (as desktops measure type: a point is 4/3 of a pixel at their
		 * usual density) and the rest in proportion, in [family] (the default, where null).
		 */
		fun of(
			palette: Palette,
			points: Float = TextPart.General.points,
			family: FontFamily? = null,
		): Type {
			val size = points * 4f / 3f

			fun style(scale: Float, color: Color, weight: FontWeight = FontWeight.Normal) =
				TextStyle(
					fontSize = (size * scale).sp,
					lineHeight = (size * scale * 1.4f).sp,
					color = color,
					fontWeight = weight,
					fontFamily = family,
				)

			return Type(
				body = style(1f, palette.text),
				strong = style(1f, palette.text, FontWeight.SemiBold),
				muted = style(0.93f, palette.textMuted),
				heading = style(0.86f, palette.textMuted, FontWeight.SemiBold),
				title = style(1.1f, palette.text, FontWeight.SemiBold),
				headline = style(1.45f, palette.text, FontWeight.SemiBold),
				button = style(1f, Color.Unspecified, FontWeight.Medium),
				mono = style(0.93f, palette.text).copy(fontFamily = FontFamily.Monospace),
				line = (size * 1.4f).dp,
			)
		}
	}
}

/** The type of each part of a window: [general]'s, the listing's and the menus', each as the user chose it. */
@Immutable
data class Types(
	val general: Type,
	val listing: Type,
	val menus: Type,
) {
	companion object {
		/** The types [typography] makes in [palette], each in the family it names as the system has it. */
		fun of(palette: Palette, typography: Typography): Types {
			fun of(part: TextPart) =
				Type.of(palette, typography.pointsOf(part), familyOf(typography.familyOf(part)))

			return Types(of(TextPart.General), of(TextPart.Listing), of(TextPart.Menus))
		}

		/** [name]'s family as the system has it, in the weights prism draws in; the default's, where null. */
		@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
		private fun familyOf(name: String?): FontFamily? =
			name?.let { family ->
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

/** The families of fonts the system has, by name, in order. */
fun systemFontFamilies(): List<String> =
	org.jetbrains.skia.FontMgr.default
		.let { fonts ->
			(0 until fonts.familiesCount).map(fonts::getFamilyName)
		}.filter { it.isNotBlank() }
		.distinct()
		.sortedBy { it.lowercase() }

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

/** The dimensions particular parts have, which no other shares. */
object Dims {
	val sidebar = 232.dp

	/** The page list of the preferences window. */
	val navigation = 208.dp

	/** A gallery's tile, its picture, and the least width a tile takes. */
	val tile = 136.dp
	val tileHeight = 156.dp

	/** How far outside a control the ring around what the keyboard is on is drawn. */
	val ring = 2.dp

	/** The room each side of a column's values, so two columns' never meet; and at the list's two edges. */
	val cell = Space.s
	val edge = Space.m

	/** The room between a gallery's tiles, and around them: somewhere to start a rectangle, or open the folder's menu. */
	val tileGap = Space.l
	val tilePad = Space.m

	/** How wide a scrollbar is, and the room kept for it beside what scrolls. */
	val scrollbar = 8.dp
	val scrollbarRoom = 12.dp
}

/** How tall a list's row is, for [rows] of [type]'s text: an icon of [rowIconOf], and room above and below it. */
fun rowHeightOf(rows: Rows, type: Type): Dp =
	maxOf(rowIconOf(rows, type), type.line) + Space.m

/** How large a list's row's icon is: larger, as Thunar draws them, or as large as the text. */
fun rowIconOf(rows: Rows, type: Type): Dp =
	when (rows) {
		Rows.Larger -> type.icon * 1.75f
		Rows.Compact -> type.icon
	}

val LocalPalette = staticCompositionLocalOf { Palette.Light }
val LocalTypes = staticCompositionLocalOf { Types.of(Palette.Light, Typography()) }

/** The type of the part of the window being drawn: its general type, unless the part says it is the listing's or a menu's. */
val LocalType = staticCompositionLocalOf { Type.of(Palette.Light) }

/** prism's palette and type around [content], and Material's theme bound to them for the few widgets it uses. */
@Composable
fun BrowserTheme(
	palette: Palette = Palette.Light,
	typography: Typography = Typography(),
	content: @Composable () -> Unit,
) {
	val types = remember(palette, typography) { Types.of(palette, typography) }

	val scheme =
		if (palette.isDark)
			darkColorScheme(
				primary = palette.accent,
				onPrimary = palette.onAccent,
				surface = palette.overlay,
				onSurface = palette.text,
				background = palette.window,
			)
		else
			lightColorScheme(
				primary = palette.accent,
				onPrimary = palette.onAccent,
				surface = palette.overlay,
				onSurface = palette.text,
				background = palette.window,
			)

	CompositionLocalProvider(
		LocalPalette provides palette,
		LocalTypes provides types,
		LocalType provides types.general,
		androidx.compose.foundation.LocalContextMenuRepresentation provides PrismContextMenus,
	) {
		MaterialTheme(colorScheme = scheme, content = content)
	}
}
