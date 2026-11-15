package prism.desktop.freedesktop

import java.io.File
import kotlin.random.Random

/*
 * The name of the systemd scope an application is started in. systemd's document for desktops
 * (systemd.io/DESKTOP_ENVIRONMENTS) names one `app[-<launcher>]-<ApplicationID>-<RANDOM>.scope`, and what reads the
 * name back reads the application from it: xdg-desktop-portal works out which application is asking for a file
 * chooser from the unit its process is in, and hands that to the chooser as the caller's app id (which is what
 * prism's own picker knows an application by).
 *
 * The application's id is written as systemd escapes a name (`systemd-escape`): a `-` in it as `\x2d`, so that the
 * dashes left are the ones between the parts, and anything else a unit's name may not hold as its bytes in hex. The
 * portal undoes that as it reads the name. The random part is letters and digits only, as the portal's reading of it
 * wants.
 */

/** The scope an application is started in: a unit's name, made only by [of], so it is always one systemd takes. */
@JvmInline
value class AppScope private constructor(
	/** The unit's name in full: `app-prism-org.gnome.Evince-1a2b3c4d.scope`. */
	val unit: String,
) {
	override fun toString(): String = unit

	companion object {
		/** The launcher these scopes say started them: letters and digits only, as the name's readers expect. */
		const val LAUNCHER = "prism"

		private const val SUFFIX = ".scope"

		/** The longest name systemd takes for a unit. */
		private const val LONGEST = 255

		/** What an application with no name to go by is called. */
		private const val UNNAMED = "unknown"

		/**
		 * The scope for [application], which is a desktop entry's name (`org.gnome.Evince.desktop`, with or without
		 * its `.desktop`) or, for a command no entry starts, its program (`/usr/bin/foot`, named `foot`). [instance]
		 * tells one start of it from another. Whatever it is given, the name is one a unit can have: an id too long
		 * for one is cut short, and one with nothing in it is [UNNAMED].
		 */
		fun of(application: String, instance: Int = Random.nextInt()): AppScope {
			val random = "%08x".format(instance)
			val prefix = "app-$LAUNCHER-"
			val room = LONGEST - prefix.length - 1 - random.length - SUFFIX.length

			return AppScope("$prefix${escaped(idOf(application), room)}-$random$SUFFIX")
		}

		/** The application's id: [application] without the folders before it, or `.desktop` after it. */
		private fun idOf(application: String): String =
			File(application)
				.name
				.removeSuffix(".desktop")
				.filter { it != '\u0000' }
				.ifEmpty { UNNAMED }

		/**
		 * [id] as systemd escapes a name, in at most [room] characters: letters, digits, `:` and `_` as they are, a
		 * `.` too unless it begins the name, and every other character as its bytes, each `\xhh`. One too long is cut
		 * between characters, never in the middle of one's bytes.
		 */
		internal fun escaped(id: String, room: Int = Int.MAX_VALUE): String =
			id
				.codePoints()
				.toArray()
				.mapIndexed { at, point -> escaped(point, first = at == 0) }
				.runningFold("", String::plus)
				.last { it.length <= room }

		/** One character of a name as systemd escapes it; [first], where it begins the name. */
		private fun escaped(point: Int, first: Boolean): String {
			val plain =
				point in 'a'.code..'z'.code ||
					point in 'A'.code..'Z'.code ||
					point in '0'.code..'9'.code ||
					point == ':'.code ||
					point == '_'.code ||
					(point == '.'.code && !first)

			return if (plain)
				point.toChar().toString()
			else
				String(Character.toChars(point))
					.toByteArray(Charsets.UTF_8)
					.joinToString("") { "\\x%02x".format(it) }
		}
	}
}
