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
 * WHAT A QUERY OF FOLDER TERMS FINDS. A path term that ends in `/` (`funny/`, `nsight*` then `/`, `~/projects/`) is a
 * folder term: it says where, and tests nothing of an entry itself. Every other term is an entry term: a word, an
 * extension, `type:`, `is:`, `dir:`, a regex, a path that ends in a name (`funny/` then `*`), a path that ends in `**`.
 *
 * - With an entry term, a query finds entries, and each folder term says they are inside such a folder, at any
 *   depth: `funny/ .jpg` is the JPEGs under folders named funny, `funny/ *` everything under them.
 * - With folder terms and no entry term, a query finds the folders themselves: `funny/` is the folders named
 *   exactly funny, not what they hold. With several, it is the folders one of the terms names that are inside what
 *   each of the others names: `~/projects/ prism/` is the folders named prism under `~/projects`, in either order.
 *
 * A folder term negated (`-build/`) says where not to look, and is neither: it changes nothing of which case holds.
 * [Query.aimed] is the one place this is decided; the index searches for what it gives.
 *
 * Choices the language makes impossible are impossible here too: a term is case sensitive, case insensitive, or as the
 * search as a whole says ([Case]), never two of those; a regex is over the name, the stem or the path ([RegexOn]).
 */

/** Every term must pass. */
data class Query(
	val terms: List<Term>,
) {
	/**
	 * This query with no group in it that need be one ([Test.All]): a group's terms are terms of the query itself,
	 * and a group of one term negated is that term the other way about. What is left a group is one of several terms
	 * negated, which says "not all of these" and can be said no other way.
	 */
	fun flattened(): Query = Query(terms.flatMap(Term::flattened))

	/**
	 * This query as it is searched for: itself, where any of its terms tests an entry; and where its terms are
	 * folder terms alone, the query for those folders themselves ([Test.Folders]), with whatever it says of where
	 * not to look. A query aimed is what aiming it again gives, since the folders it asks for are entries it tests.
	 */
	fun aimed(): Query {
		val flat = flattened().terms
		val (places, rest) = flat.partition { !it.negated && it.test.folder != null }

		return if (places.isEmpty() || rest.any { !(it.negated && it.test.places) })
			this
		else
			Query(rest + Term(Test.Folders(places.mapNotNull { it.test.folder })))
	}
}

/** Whether this is a path term that ends in `/` or `**`: one that says where, of whatever sort. */
private val Test.places: Boolean get() =
	this is Test.InPath &&
		pattern.last == PathPattern.Last.Under

/**
 * This as a folder term, where it is one: a path that ends in `/` at a folder it names, so not one that ends in `**`
 * (which asks for everything under, in so many words), and not the root alone, which is no entry.
 */
private val Test.folder: Test.InPath? get() =
	(this as? Test.InPath)?.takeIf {
		it.pattern.last == PathPattern.Last.Under &&
			when (it.pattern.segments.lastOrNull()) {
				is Segment.Named -> true
				Segment.AnyLevels -> false
				null -> it.pattern.base != PathBase.Root && it.pattern.base != PathBase.Relative
			}
	}

/** A [test] a result must pass, or with [negated], must not. */
data class Term(
	val test: Test,
	val negated: Boolean = false,
) {
	/** The terms this one is, said without a group where it can be ([Query.flattened]). */
	internal fun flattened(): List<Term> {
		val inner = (test as? Test.All)?.terms?.flatMap(Term::flattened) ?: return listOf(this)

		return when {
			!negated -> inner
			inner.size == 1 -> inner.map { it.copy(negated = !it.negated) }
			else -> listOf(Term(Test.All(inner), negated = true))
		}
	}
}

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

/**
 * Which part of a name a term matches: its stem (the name without its extension); the whole name; or its extension
 * with the dot before it, which for a name that begins with a dot (a hidden file's) is the whole of the name.
 */
enum class NamePart {
	Stem,
	Whole,
	Extension,
	;

	companion object {
		/**
		 * The part a term typed as [text] matches, by where its `.` is:
		 *
		 * - none (`report`, `jpg`, `img_*`): the stem, so `jpg` is not found in `photo.jpg`, and is in `jpg.png`;
		 * - one at its start (`.jpg`, `.*metis`): the extension, or a name that begins with a dot: `photo.jpg`, and a
		 *   file named `.jpg`;
		 * - one anywhere else (`report.pdf`, `awoo.z`): the whole name, so `awoo.z` is found in `lets_awoo.zip`.
		 */
		fun of(text: String): NamePart =
			when {
				text.startsWith('.') -> Extension
				'.' in text -> Whole
				else -> Stem
			}
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
 * How a name is matched: [pattern] against [part] of it. A literal or a glob, it is found anywhere in it unless
 * [anchors] hold it to the start or the end.
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

/** When `recent:` and `new:` count from. */
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

	/** `new:WHEN`: first seen by the index since then: made, or moved in, after its root was indexed. */
	data class New(
		val since: Since,
	) : Test

	/** `mount:FSTYPE`: on a file system of that type. */
	data class Mount(
		val type: TextPattern,
	) : Test

	/**
	 * The folders themselves that a query of folder terms alone asks for ([Query.aimed]): a folder that one of
	 * [places] leads to, and that is inside what every other of them leads to. Each of [places] is a path that
	 * ends in `/`. No query is typed as this: it is what one of folder terms is searched as.
	 */
	data class Folders(
		val places: List<InPath>,
	) : Test {
		init {
			require(places.isNotEmpty()) { "folders are asked for by at least one path" }
			require(places.all { it.pattern.last == PathPattern.Last.Under }) {
				"a folder is asked for by a path that ends in “/”"
			}
		}
	}

	/**
	 * Every one of [terms]: a query within a query, which is what an alias used in one stands for (`raws@`, defined
	 * as `~/Photos ext:raw`). Negated, it is what none of its terms can say alone: not all of them at once.
	 */
	data class All(
		val terms: List<Term>,
	) : Test {
		init {
			require(terms.isNotEmpty()) { "a group holds at least one term" }
		}
	}
}

/** [source], refused with why where it is not a regex. */
private fun checkRegex(source: String) {
	try {
		Pattern.compile(source)
	} catch (wrong: PatternSyntaxException) {
		throw IllegalArgumentException("“$source” is not a regex: ${wrong.description}", wrong)
	}
}
