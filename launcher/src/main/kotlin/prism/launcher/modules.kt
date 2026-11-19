package prism.launcher

import androidx.compose.runtime.Immutable

/*
 * The launcher's modules: what it finds besides applications and files, each a module that looks at what is typed.
 *
 * A module is reached in one of two ways. Every module has prefixes, which ask for it outright: `=` or `calc ` before
 * what is typed puts the launcher in the module's view, where the field is the module's alone and nothing else is
 * shown, until Escape leaves it. And a module may also be asked about everything typed bare ([Reach.AlsoBare]): it
 * says what it makes of it, if anything, and how sure it is ([Strength]), and what it makes is shown with the
 * applications and files. A module that answers wrongly costs a row, so a module's test of what is meant for it can
 * be simple.
 *
 * What a module makes of what is typed is a [Banner], a row along the top that is not chosen by the arrows, with a
 * key of its own; and [ModuleRow]s, results among the others, chosen as any result is. Everything a module gives is
 * plain data: what it shows, and what is done when it is used ([ModuleAct]), which the launcher does.
 */

/** What tells a module from every other. */
@JvmInline
value class ModuleId(
	val name: String,
)

/** What asks for a module outright, before what is typed for it. */
@Immutable
sealed interface Prefix {
	/** As it is typed. */
	val written: String

	/** What follows this in [query], where [query] begins with it; none where it does not. */
	fun after(query: String): String?

	/** A word, followed by a space: `calc 1+1`. Typed in quotes (`"calc`), it is the word, and asks for nothing. */
	data class Word(
		override val written: String,
	) : Prefix {
		init {
			require(written.isNotEmpty() && written.none(Char::isWhitespace)) {
				"a prefix is one word"
			}
		}

		override fun after(query: String): String? =
			query.takeIf { it.startsWith("$written ") }?.drop(written.length + 1)
	}

	/** A sign, followed by anything at all: `=1+1`. */
	data class Sign(
		override val written: String,
	) : Prefix {
		init {
			require(written.isNotEmpty() && written.none(Char::isLetterOrDigit)) {
				"a sign is not a word"
			}
		}

		override fun after(query: String): String? =
			query
				.takeIf {
					it.startsWith(written)
				}?.drop(written.length)
	}
}

/** How a module is reached. */
@Immutable
sealed interface Reach {
	val prefixes: List<Prefix>

	/** By its prefixes alone. */
	data class ByPrefix(
		override val prefixes: List<Prefix>,
	) : Reach

	/** By its prefixes, and from anything typed that it recognises. */
	data class AlsoBare(
		override val prefixes: List<Prefix>,
	) : Reach
}

/**
 * How sure a module is that what is typed is meant for it, which is where what it makes of it is shown: its banner
 * shown only where it is [Certain]; its rows before the applications, after them, or after the files.
 */
enum class Strength {
	Certain,
	Likely,
	Possible,
}

/** Something a module's banner or row does when it is used. */
@Immutable
sealed interface ModuleAct {
	/** [text] put on the clipboard, and the launcher put away. */
	data class Copy(
		val text: String,
	) : ModuleAct

	/** [text] put in the field in place of what is there: a name finished. */
	data class Put(
		val text: String,
	) : ModuleAct
}

/** Something in a banner's menu: what it says, and what it does. */
@Immutable
data class ModuleChoice(
	val label: String,
	val act: ModuleAct,
)

/** What a banner's own key does, and what it is said to do beside the key: "Copy". */
@Immutable
sealed interface BannerKey {
	val label: String

	/** What the module says is to be done. */
	data class Does(
		override val label: String,
		val act: ModuleAct,
	) : BannerKey

	/** A menu of what can be done, as the right arrow shows a result's. */
	data class Menu(
		override val label: String,
		val choices: List<ModuleChoice>,
	) : BannerKey {
		init {
			require(choices.isNotEmpty()) { "a menu has something in it" }
		}
	}

	/** The module's view, taking what is typed with it. */
	data class View(
		override val label: String,
	) : BannerKey
}

/** How what a banner says is to be read. */
enum class Tone {
	/** What was asked for. */
	Answer,

	/** What is wrong with what was typed. */
	Wrong,
}

/** The row along the top: what a module makes of what is typed, shown, and done with its own [key], not chosen. */
@Immutable
data class Banner(
	val title: String,
	/** What kind of thing [title] is, said beside it: "UInt". */
	val badge: String? = null,
	/** What is to be said of how it came about: "Int overflow: …". */
	val notes: List<String> = emptyList(),
	val tone: Tone = Tone.Answer,
	val key: BannerKey? = null,
)

/** A result of a module's, among the launcher's others: chosen as any is, and [act] done then. */
@Immutable
data class ModuleRow(
	/** What tells it from the module's other rows. */
	val key: String,
	val title: String,
	val detail: String,
	val kind: String,
	val act: ModuleAct,
)

/** What a module makes of something typed bare. */
@Immutable
data class ModuleAnswer(
	val strength: Strength,
	val banner: Banner? = null,
	val rows: List<ModuleRow> = emptyList(),
) {
	init {
		require(banner != null || rows.isNotEmpty()) { "an answer shows something" }
	}
}

/** What a module shows in its own view, of what is typed there. */
@Immutable
data class ModuleView(
	/** What the field says while nothing is typed. */
	val placeholder: String,
	val banner: Banner? = null,
	val rows: List<ModuleRow> = emptyList(),
)

/** A module of the launcher's. */
interface LauncherModule {
	val id: ModuleId

	/** What it is called, as its view and its rows' heading say: "Calculator". */
	val title: String
	val reach: Reach

	/** What it makes of [query], typed bare; none where it does not recognise it. Asked only where it is [Reach.AlsoBare]. */
	fun bare(query: String): ModuleAnswer?

	/** What it shows of [text], typed in its view. */
	fun view(text: String): ModuleView
}

/**
 * The launcher's modules: each with an id of its own, and each prefix one module's alone, checked as they are put
 * together, so that what a prefix asks for is never a choice between two.
 */
@Immutable
class Modules private constructor(
	val all: List<LauncherModule>,
) {
	private val byId: Map<ModuleId, LauncherModule> = all.associateBy { it.id }

	/** Every prefix, the longest first: `==` is looked for before `=`. */
	private val prefixes: List<Pair<Prefix, LauncherModule>> =
		all
			.flatMap { module ->
				module.reach.prefixes.map { it to module }
			}.sortedByDescending { it.first.written.length }

	init {
		require(byId.size == all.size) { "every module has an id of its own" }

		val taken = prefixes.groupBy { it.first.written }.filterValues { it.size > 1 }.keys

		require(taken.isEmpty()) {
			"each prefix asks for one module: ${taken.joinToString()} ask for more"
		}
	}

	fun of(id: ModuleId): LauncherModule? = byId[id]

	/** The module [query] asks for by its prefix, and what is typed after it; none where it asks for none. */
	fun prefixed(query: String): Pair<LauncherModule, String>? =
		prefixes.firstNotNullOfOrNull { (prefix, module) ->
			prefix.after(query)?.let {
				module to
					it
			}
		}

	/** What each module asked about what is typed bare makes of [query], where it makes anything. */
	fun answers(query: String): List<Pair<LauncherModule, ModuleAnswer>> =
		if (query.isBlank())
			emptyList()
		else
			all.filter { it.reach is Reach.AlsoBare }.mapNotNull { module ->
				module.bare(query)?.let {
					module to
						it
				}
			}

	override fun equals(other: Any?): Boolean = other is Modules && other.all == all

	override fun hashCode(): Int = all.hashCode()

	companion object {
		val NONE = Modules(emptyList())

		fun of(modules: List<LauncherModule>): Modules = Modules(modules)

		/** The modules the launcher has. */
		val STANDARD: Modules by lazy { of(listOf(CalculatorModule)) }
	}
}

/** The view of a module the launcher is in: the [module], and what is typed into its field. */
@Immutable
data class Viewing(
	val module: ModuleId,
	val text: String,
)

/** The banner shown along the top, and the module it is of. */
@Immutable
data class Shown(
	val module: LauncherModule,
	val banner: Banner,
)
