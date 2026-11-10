package prism.index.query

import java.time.DayOfWeek
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.time.Duration

/*
 * A search, as an immutable tree: what the search language (docs/search.md) is parsed into, and what the Kotlin DSL
 * (dsl.kt) builds. A query is terms, every one of which a result must pass; each term is a test, or a test negated.
 * Nothing here knows how a test is run: that is the index's (search.kt).
 *
 * Choices the language makes impossible are impossible here too: a term is case sensitive, case insensitive, or as the
 * search as a whole says ([Case]), never two of those; a regex is over the name, the stem or the path ([RegexOn]).
 */

/** Every term must pass. */
data class Query(
	val terms: List<Term>,
)

/** A [test] a result must pass, or with [negated], must not. */
data class Term(
	val test: Test,
	val negated: Boolean = false,
)

/** Whether a term tells upper from lower case: as the search as a whole says, or always, or never. */
enum class Case {
	Default,
	Sensitive,
	Insensitive,
	;

	/** Whether a term of this case tells them apart, in a search that does where [searchSays]. */
	fun sensitive(searchSays: Boolean): Boolean =
		when (this) {
			Default -> searchSays
			Sensitive -> true
			Insensitive -> false
		}
}

/**
 * Text to match: [Literal]ly, or a [Wildcard] glob. A bare word is a glob where it has an unescaped glob character,
 * and otherwise the text it says ([of]); a raw literal (`r"..."`) is always [Literal].
 */
sealed interface TextPattern {
	data class Literal(
		val text: String,
	) : TextPattern {
		init {
			require(text.isNotEmpty()) { "a term matches at least one character" }
		}
	}

	data class Wildcard(
		val glob: Glob,
	) : TextPattern

	companion object {
		/** A word as typed: a glob where it has an unescaped `*`, `?`, `[` or `{`, and otherwise the text it says. */
		fun of(word: String): TextPattern =
			if (Glob.isGlob(word))
				Wildcard(Glob.parse(word))
			else
				Literal(Glob.unescaped(word))
	}
}

/** Which part of a name a term matches: its stem (the name without its extension), or the whole name. */
enum class NamePart {
	Stem,
	Whole,
	;

	companion object {
		/**
		 * The part a term typed as [text] matches: the whole name where a `.` comes after its first character
		 * (`report.pdf`, `*.jpg`), and otherwise the stem (`report`, and `.git`, whose dot is its first character).
		 */
		fun of(text: String): NamePart = if (text.indexOf('.', 1) >= 0) Whole else Stem
	}
}

/** `^` before a term, which matches it only at the start of what it matches against, and `$` after, at the end. */
data class Anchors(
	val start: Boolean = false,
	val end: Boolean = false,
) {
	companion object {
		val NONE = Anchors()

		/** `=`: both. */
		val BOTH = Anchors(start = true, end = true)
	}
}

/**
 * How a name is matched: [pattern] against [part] of it. A literal is found anywhere in it unless [anchors] hold it to
 * the start or the end; a glob matches the whole of it.
 */
data class NameMatch(
	val pattern: TextPattern,
	val part: NamePart,
	val anchors: Anchors = Anchors.NONE,
)

/** Where a path pattern starts. */
sealed interface PathBase {
	/** Anywhere: the pattern matches the end of a path (`funny/rep`). */
	data object Relative : PathBase

	/** At the root: the pattern matches a whole path (`/mnt/photos/`). */
	data object Root : PathBase

	/** `~`: the home folder. */
	data object Home : PathBase

	/** `~name`: the folders the user has named [name]. */
	data class Alias(
		val name: String,
	) : PathBase {
		init {
			require(name.isNotEmpty() && '/' !in name) { "an alias is a name" }
		}
	}
}

/** One level of a path pattern above its last. */
sealed interface Segment {
	/** `**`: any number of levels, none included. */
	data object AnyLevels : Segment

	/** One level, whose name is all of [pattern]: matched exactly, or as a glob. */
	data class Named(
		val pattern: TextPattern,
	) : Segment
}

/**
 * A path: from [base], through [segments], to [last], which is an entry matched as a name is ([Last.Entry]), or with
 * a trailing `/`, everything under the folder the segments lead to ([Last.Under]).
 */
data class PathPattern(
	val base: PathBase,
	val segments: List<Segment>,
	val last: Last,
) {
	sealed interface Last {
		/** A trailing `/`: everything under the folder, at any depth. */
		data object Under : Last

		/** An entry the segments lead to, matched as a name term matches. */
		data class Entry(
			val match: NameMatch,
		) : Last
	}
}

/** Which ancestors `dir:` looks at: a distance from [min] to [max] levels up (none: no limit), 1 being the parent. */
data class Depth(
	val min: Int = 1,
	val max: Int? = null,
) {
	init {
		require(min >= 1) { "an ancestor is at least one level up" }
		require(max == null || max >= min) {
			"a depth's range runs from its lower end to its higher"
		}
	}

	companion object {
		val ANY = Depth()

		fun exactly(levels: Int) = Depth(levels, levels)

		fun between(min: Int, max: Int) = Depth(min, max)

		fun atLeast(min: Int) = Depth(min)
	}
}

/** The folder `dir:` asks for: one named so, or one at a path. */
sealed interface DirTarget {
	/** A folder whose whole name is [pattern]: `dir:funny`, `dir:{a,b}`. */
	data class Named(
		val pattern: TextPattern,
	) : DirTarget

	/** A folder at a path: `dir:work/2024` (one whose path ends so), `dir:~proj`, `dir:/srv/data`. */
	data class Located(
		val base: PathBase,
		val segments: List<Segment>,
	) : DirTarget
}

/** What `ext:` matches an extension with. */
sealed interface ExtPattern {
	/** A word or glob, matched against the whole of an extension: `ext:pdf`, `ext:{jpg,png}`, `ext:c"PDF"`. */
	data class Text(
		val pattern: TextPattern,
		val case: Case = Case.Default,
	) : ExtPattern

	/** A regex, which must match the whole of an extension: `ext:/jpe?g/i`. */
	data class Regex(
		val source: String,
		val case: Case,
	) : ExtPattern {
		init {
			checkRegex(source)
		}
	}
}

/** What a regex term is matched against. */
enum class RegexOn {
	/** The whole name: the default. */
	Name,

	/** The stem alone: flag `s`. */
	Stem,

	/** The whole path: flag `p`. */
	Path,
}

/** `is:` values. */
enum class Flag {
	/** A name that starts with `.`. */
	Hidden,

	/** A file, or a link to one. */
	File,

	/** A folder, or a link to one. */
	Dir,

	/** Any link, wherever it leads, nowhere included. */
	Symlink,
}

/** When `recent:` counts from. */
sealed interface Since {
	/** The start of today. */
	data object Today : Since

	/** The start of yesterday. */
	data object Yesterday : Since

	/** The start of the latest [day]: today, if today is that day. */
	data class Weekday(
		val day: DayOfWeek,
	) : Since

	/** [duration] ago. */
	data class Within(
		val duration: Duration,
	) : Since {
		init {
			require(duration.isPositive()) { "a while ago is some time ago" }
		}
	}
}

/** What a term asks of an entry. */
sealed interface Test {
	/** A name term: `report`, `^report`, `=thing`, `*.jpg`, `"tax return"`, `r"img_*"`, `c"README"`. */
	data class Name(
		val match: NameMatch,
		val case: Case = Case.Default,
	) : Test

	/** A path term: `funny/rep`, `/mnt/photos/`, `~/Downloads/inv`. */
	data class InPath(
		val pattern: PathPattern,
		val case: Case = Case.Default,
	) : Test

	/** `r/.../flags`: a regex found in the name, the stem or the path. */
	data class Regex(
		val source: String,
		val on: RegexOn = RegexOn.Name,
		val case: Case = Case.Default,
	) : Test {
		init {
			checkRegex(source)
		}
	}

	/** `dir:PATTERN[:DEPTH]`: under a folder of that name, or at that path, within that distance. */
	data class Dir(
		val target: DirTarget,
		val depth: Depth = Depth.ANY,
		val case: Case = Case.Default,
	) : Test

	/** `ext:EXT`: an extension, at any of a name's extension boundaries. */
	data class Ext(
		val pattern: ExtPattern,
	) : Test

	/** `type:GROUP`: an extension of a group. */
	data class Type(
		val group: Group,
	) : Test

	/** `is:FLAG`, or `is:{a,b}`: any of [flags]. */
	data class Is(
		val flags: Set<Flag>,
	) : Test {
		init {
			require(flags.isNotEmpty()) { "is: takes at least one flag" }
		}
	}

	/** `recent:WHEN`: picked through the picker since then. */
	data class Recent(
		val since: Since,
	) : Test

	/** `mount:FSTYPE`: on a file system of that type. */
	data class Mount(
		val type: TextPattern,
	) : Test
}

/** [source], refused with why where it is not a regex. */
private fun checkRegex(source: String) {
	try {
		Pattern.compile(source)
	} catch (wrong: PatternSyntaxException) {
		throw IllegalArgumentException("“$source” is not a regex: ${wrong.description}", wrong)
	}
}
