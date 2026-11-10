package prism.app

import prism.cli.ParamType
import prism.picker.Palette
import prism.portal.ColorScheme
import prism.portal.sessionColorScheme
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Which palette the picker is drawn in. By default, the one the desktop's colour scheme asks for, as the portal
 * reports it and each time it changes: dark where the user prefers dark, light otherwise, and light where there is no
 * session bus or no portal to ask. A user who names a palette gets that one throughout.
 */

/** Which palette: the desktop's, or one named. */
enum class Theme(
	val word: String,
) {
	Auto("auto"),
	Light("light"),
	Dark("dark"),
	;

	companion object {
		val TYPE = ParamType.choice(entries) { it.word }
	}
}

/** The palette [theme] gives where the desktop prefers [scheme]. */
fun paletteOf(theme: Theme, scheme: ColorScheme): Palette =
	when (theme) {
		Theme.Light -> Palette.Light
		Theme.Dark -> Palette.Dark
		Theme.Auto ->
			if (scheme == ColorScheme.PreferDark)
				Palette.Dark
			else
				Palette.Light
	}

/**
 * The desktop's colour scheme as a window follows it: [first], what it is as the window opens, and [changes], each
 * one after. One connection serves both, open until this is closed.
 */
class Appearance private constructor(
	val first: ColorScheme,
	val changes: Flow<ColorScheme>,
	private val scope: CoroutineScope,
) : AutoCloseable {
	override fun close() {
		scope.cancel()
	}

	companion object {
		/**
		 * How long a window waits for the portal's first answer before it opens light. The portal answers in
		 * milliseconds; this is for a session whose portal is slow to start, so the window is never held up long.
		 */
		private val FIRST_ANSWER = 500.milliseconds

		/**
		 * The desktop's scheme followed for [theme]: asked of the session bus [environment] names only where [theme]
		 * is [Theme.Auto].
		 */
		fun follow(
			theme: Theme,
			environment: Map<String, String> = System.getenv(),
		): Appearance {
			val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
			val changes =
				if (theme == Theme.Auto)
					sessionColorScheme(environment).shareIn(scope, SharingStarted.Eagerly, replay = 1)
				else
					flowOf(ColorScheme.NoPreference)

			val first =
				runBlocking { withTimeoutOrNull(FIRST_ANSWER) { changes.first() } }
					?: ColorScheme.NoPreference

			return Appearance(first, changes, scope)
		}
	}
}
