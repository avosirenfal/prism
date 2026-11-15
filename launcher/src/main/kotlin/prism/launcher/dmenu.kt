package prism.launcher

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import prism.preferences.LauncherPreferences

/*
 * The launcher's window as dmenu is used: given lines, it shows them, narrows them to what is typed, and answers
 * the one chosen (`prism dmenu`, app's dmenu.kt). It is the launcher to look at and to work (its field, its list,
 * its keys as the user has bound them), and nothing of the launcher otherwise: no applications, no files, nothing of
 * prism's own, no settings and no menu of a result. So it is a state of its own, with its own reducer and session,
 * and not the launcher's with most of it unused.
 *
 * There may be a great many lines, so they are made ready to be looked through as they are read ([Dmenu.of]), and
 * looked through for what is typed ([matching]), off the thread the window is drawn on: what is shown is what was
 * last found, and Enter pressed before what is typed has been looked for waits for it, so that what is chosen is
 * always a line that fits what was typed.
 */

/** Lines to choose one of, and how they are shown: what `prism dmenu` asks for. */
@Immutable
data class Dmenu(
	val lines: List<Line>,
	/** What the field says while nothing is typed. */
	val placeholder: String = PLACEHOLDER,
	/** What is typed as it opens. */
	val query: String = "",
	/** The title over the lines, where they have one: `{count}` in it is how many are shown. */
	val section: String? = null,
	/** What is said along the bottom of what this is a choice of, where anything is. */
	val title: String? = null,
	/** Whether the bottom line is shown: how many lines there are, and what the keys do. */
	val footer: Boolean = true,
	/** Whether what is typed is shown as dots: a password asked for. */
	val redacted: Boolean = false,
	/** How wide and how tall its window is, where it is not as the launcher's is. */
	val width: Int? = null,
	val height: Int? = null,
) {
	/** One of the lines: its [text], and where it is among them ([at], from 0). */
	class Line internal constructor(
		val at: Int,
		val text: String,
	) {
		/** The text as it is looked through: made as the line is read, and not for every key typed. */
		internal val said = Said(text, 100)

		override fun equals(other: Any?): Boolean =
			other is Line && at == other.at && text == other.text

		override fun hashCode(): Int = 31 * at + text.hashCode()

		override fun toString(): String = "$at: $text"
	}

	companion object {
		const val PLACEHOLDER = "Search…"

		/**
		 * The lines of [input] that have anything on them, in order, each as it was given. Every line is made ready
		 * to be looked through here: of a long list, somewhere other than where a window is drawn.
		 */
		fun linesOf(input: String): List<Line> =
			input
				.lineSequence()
				.filter { it.isNotBlank() }
				.mapIndexed(::Line)
				.toList()
	}
}

/** What a [Dmenu] came to. */
sealed interface DmenuAnswer {
	/** A line of it chosen. */
	data class Chose(
		val line: Dmenu.Line,
	) : DmenuAnswer

	/** Enter, on text that fits no line: the text, which is then the answer, as dmenu has it. */
	data class Typed(
		val text: String,
	) : DmenuAnswer

	/** Put away with nothing chosen: Escape, the keyboard gone elsewhere, something else shown in its place. */
	data object Dismissed : DmenuAnswer
}

/** The lines that fit what was typed ([query]), the best first. */
@Immutable
data class DmenuFound(
	val query: String,
	val lines: List<Dmenu.Line>,
)

/** A dmenu open. */
@Immutable
data class DmenuState(
	val asked: Dmenu,
	val preferences: LauncherPreferences = LauncherPreferences(),
	/** What is typed into its field. */
	val query: String = asked.query,
	/** What was last found: for an older [query], until what fits this one has been looked for. */
	val found: DmenuFound = DmenuFound("", asked.lines),
	/** Which of [lines] the arrows are on. */
	val arrowsOn: Int = 0,
	/** Whether Enter was pressed before what is typed had been looked for: the line it finds first is chosen. */
	val entering: Boolean = false,
) {
	/** The lines shown. */
	val lines: List<Dmenu.Line> get() = found.lines

	/** Which of [lines] the arrows are on: the nearest there is to where they were put. */
	val highlighted: Int get() = arrowsOn.coerceIn(0, (lines.size - 1).coerceAtLeast(0))

	/** The line the arrows are on; none where none is shown. */
	val current: Dmenu.Line? get() = lines.getOrNull(highlighted)

	/** Whether what is shown is what fits what is typed, and not what fitted something typed before. */
	val settled: Boolean get() = found.query == query

	/** The title over the lines, where there are any and they have one. */
	val heading: String?
		get() =
			asked.section
				?.takeIf { lines.isNotEmpty() }
				?.replace("{count}", "%,d".format(lines.size))

	/** This with the arrows on the line at [at], or on the nearest to it there is. */
	fun arrowsAt(at: Int): DmenuState =
		copy(
			arrowsOn = at.coerceIn(0, (lines.size - 1).coerceAtLeast(0)),
		)
}

/** Something that happened to a dmenu. */
sealed interface DmenuEvent {
	/** The field typed into. */
	data class Typed(
		val query: String,
	) : DmenuEvent

	/** The arrows: [by] lines down, or up where negative, stopping at either end. */
	data class Moved(
		val by: Int,
	) : DmenuEvent

	/** To the first line, or the last. */
	data class MovedTo(
		val last: Boolean,
	) : DmenuEvent

	/** The line the arrows are on chosen (Enter); with none that fits, what is typed. */
	data object Entered : DmenuEvent

	/** The line at [at] chosen (a click). */
	data class Chosen(
		val at: Int,
	) : DmenuEvent

	/** Escape: what is typed let go, or, with nothing typed, the dmenu put away. */
	data object Dismissed : DmenuEvent

	/** The dmenu to be put away, whatever is typed: its window closed, or the keyboard gone elsewhere. */
	data object Closed : DmenuEvent

	/** What fits [query] found. */
	data class Matched(
		val query: String,
		val lines: List<Dmenu.Line>,
	) : DmenuEvent

	/** The user's settings changed. */
	data class PreferencesChanged(
		val preferences: LauncherPreferences,
	) : DmenuEvent
}

/** Something a dmenu asks to be done. */
sealed interface DmenuEffect {
	/** The lines looked through for what fits [query]: what is told back as [DmenuEvent.Matched]. */
	data class Match(
		val query: String,
	) : DmenuEffect

	/** [answer] given to whoever asked, and the dmenu put away. */
	data class Answer(
		val answer: DmenuAnswer,
	) : DmenuEffect
}

/** A state, and what is to be done on the way to it. */
data class DmenuStep(
	val state: DmenuState,
	val effects: List<DmenuEffect> = emptyList(),
)

/** [asked] as it opens: every line shown, or, where it opens with something typed, what fits that looked for. */
fun opened(
	asked: Dmenu,
	preferences: LauncherPreferences = LauncherPreferences(),
): DmenuStep = DmenuState(asked, preferences).typed(asked.query)

/** [state] after [event], and what is to be done. */
fun step(state: DmenuState, event: DmenuEvent): DmenuStep =
	when (event) {
		is DmenuEvent.Typed -> state.typed(event.query)
		is DmenuEvent.Moved -> DmenuStep(state.arrowsAt(state.highlighted + event.by))
		is DmenuEvent.MovedTo ->
			DmenuStep(
				state.arrowsAt(
					if (event.last)
						state.lines.size - 1
					else
						0,
				),
			)
		DmenuEvent.Entered ->
			if (state.settled)
				state.entered()
			else
				DmenuStep(state.copy(entering = true))
		// What is clicked is what is in sight, whatever is typed since.
		is DmenuEvent.Chosen ->
			DmenuStep(
				state,
				listOfNotNull(
					state.lines.getOrNull(event.at)?.let {
						DmenuEffect.Answer(DmenuAnswer.Chose(it))
					},
				),
			)
		DmenuEvent.Dismissed ->
			if (state.query.isEmpty())
				DmenuStep(state, listOf(DmenuEffect.Answer(DmenuAnswer.Dismissed)))
			else
				state.typed("")
		DmenuEvent.Closed -> DmenuStep(state, listOf(DmenuEffect.Answer(DmenuAnswer.Dismissed)))
		is DmenuEvent.Matched ->
			// What fitted something typed before is dropped: what fits what is typed now is on its way.
			if (event.query != state.query)
				DmenuStep(state)
			else
				state
					.copy(found = DmenuFound(event.query, event.lines), arrowsOn = 0)
					.let {
						if (it.entering)
							it.entered()
						else
							DmenuStep(it)
					}
		is DmenuEvent.PreferencesChanged ->
			DmenuStep(
				state.copy(preferences = event.preferences),
			)
	}

/**
 * This with [query] typed: every line shown at once where it asks for nothing (and none, where there is none to
 * look through), and otherwise what was shown left until what fits it is found.
 */
private fun DmenuState.typed(query: String): DmenuStep =
	copy(query = query, arrowsOn = 0, entering = false).let {
		if (wordsOf(query).isEmpty() || asked.lines.isEmpty())
			DmenuStep(it.copy(found = DmenuFound(query, asked.lines)))
		else if (query == found.query)
			DmenuStep(it)
		else
			DmenuStep(it, listOf(DmenuEffect.Match(query)))
	}

/**
 * Enter, with what is shown being what fits what is typed: the line the arrows are on is the answer; with no line
 * that fits, what is typed is; and with nothing typed either, there is nothing to answer, and it stays.
 */
private fun DmenuState.entered(): DmenuStep {
	val answer =
		current?.let(DmenuAnswer::Chose)
			?: query.takeIf { it.isNotEmpty() }?.let(DmenuAnswer::Typed)

	return DmenuStep(copy(entering = false), listOfNotNull(answer?.let(DmenuEffect::Answer)))
}

/** How many lines are looked through between one look at whether the looking is still wanted and the next. */
private const val LOOKED_AT_ONCE = 4096

/**
 * The [lines] that fit [query], the best first, and among equals in the order they were given: found as an
 * application is ([scoreOf]), every word typed somewhere in the line. Stopped, where whoever asked no longer wants
 * it (something else was typed).
 */
internal suspend fun matching(lines: List<Dmenu.Line>, query: String): List<Dmenu.Line> {
	val words = wordsOf(query)
	val scored = ArrayList<Pair<Dmenu.Line, Int>>()

	lines.forEachIndexed { at, line ->
		if (at % LOOKED_AT_ONCE == 0)
			currentCoroutineContext().ensureActive()
		scoreOf(words, line.said)?.let { scored += line to it }
	}

	// Stable, so equals stay as they were given.
	return scored.sortedByDescending { it.second }.map { it.first }
}

/**
 * A dmenu open: its [state], changed by what is [send]t to it, its lines looked through in [scope] on [matching];
 * and its [answer], once it has one, after which nothing more comes of it.
 */
class DmenuSession(
	asked: Dmenu,
	private val scope: CoroutineScope,
	preferences: LauncherPreferences = LauncherPreferences(),
	private val matching: CoroutineDispatcher = Dispatchers.Default,
) {
	private val first = opened(asked, preferences)
	private val held = MutableStateFlow(first.state)
	private val answered = CompletableDeferred<DmenuAnswer>()

	val state: StateFlow<DmenuState> = held.asStateFlow()

	/** What it came to: the first answer there is. */
	val answer: Deferred<DmenuAnswer> get() = answered

	/** The looking under way, which the next takes the place of. */
	private var looking: Job? = null

	init {
		first.effects.forEach(::carryOut)
	}

	/** [event] taken in: the state changed as the reducer says, and what it asks for done. */
	fun send(event: DmenuEvent) {
		val effects =
			synchronized(this) {
				step(held.value, event).also { held.value = it.state }.effects
			}

		effects.forEach(::carryOut)
	}

	private fun carryOut(effect: DmenuEffect) {
		when (effect) {
			is DmenuEffect.Match ->
				synchronized(this) {
					looking?.cancel()
					looking =
						scope.launch(matching) {
							send(
								DmenuEvent.Matched(
									effect.query,
									matching(held.value.asked.lines, effect.query),
								),
							)
						}
				}
			is DmenuEffect.Answer -> answered.complete(effect.answer)
		}
	}
}

/** What [action] sends a dmenu; nothing, for what is the launcher's alone (its settings, a result's menu or folder). */
internal fun dmenuEventOf(action: LauncherAction, page: Int): DmenuEvent? =
	when (action) {
		LauncherAction.Open -> DmenuEvent.Entered
		LauncherAction.Down -> DmenuEvent.Moved(1)
		LauncherAction.Up -> DmenuEvent.Moved(-1)
		LauncherAction.PageDown -> DmenuEvent.Moved(page)
		LauncherAction.PageUp -> DmenuEvent.Moved(-page)
		LauncherAction.First -> DmenuEvent.MovedTo(last = false)
		LauncherAction.Last -> DmenuEvent.MovedTo(last = true)
		LauncherAction.Dismiss -> DmenuEvent.Dismissed
		LauncherAction.ShowActions, LauncherAction.Reveal, LauncherAction.CopyPath,
		LauncherAction.Settings,
		-> null
	}
