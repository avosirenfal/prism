package prism.launcher

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.browser.Category
import prism.preferences.LauncherPreferences

/**
 * What the launcher shows for what is typed, and what it does with what happens to it: the reducer alone, with nothing
 * started and nothing searched.
 */
class LauncherStateTest {
	private val now = Instant.parse("2026-11-14T10:00:00Z")

	private fun state(query: String = "", launches: Launches = Launches.NONE) =
		LauncherState(applications = Programs.all, launches = launches, now = now)
			.let {
				if (query.isEmpty())
					it
				else
					step(it, LauncherEvent.Typed(query)).state
			}

	private fun LauncherState.titles(section: String) =
		sections
			.firstOrNull { it.title == section }
			?.entries
			?.map { it.title }
			.orEmpty()

	private fun LauncherState.after(vararg events: LauncherEvent) =
		events.fold(LauncherStep(this)) { at, event -> step(at.state, event) }

	private fun file(path: String, category: Category = Category.Document) =
		Entry.File(Path.of(path), category, "~/Documents", "Document")

	@Test
	fun withNothingTypedEveryApplicationIsShownThoseUsedFirstAndThenWhatPrismDoes() {
		val used =
			Launches.NONE
				.used(Entry.App(Programs.terminal).key, now)
				.used(Entry.App(Programs.terminal).key, now)
				.used(Entry.App(Programs.code).key, now)

		val shown = state(launches = used)

		assertEquals(
			listOf(
				"Foot",
				"Visual Studio Code",
				"Firefox",
				"Firewall Configuration",
				"Text Editor",
				"Thunar File Manager",
			),
			shown.titles("Applications"),
		)
		assertEquals(listOf("Files", "Settings"), shown.titles("Prism"))
		// No action until something is typed that fits one, and no file.
		assertTrue(shown.entries.none { it is Entry.Action || it is Entry.File })
		assertEquals("Applications", shown.headingAt(0))
		assertNull(shown.headingAt(1))
		assertEquals("Prism", shown.headingAt(6))
	}

	@Test
	fun whatIsTypedFindsApplicationsByNameKindKeywordsAndCommandTheBestFitFirst() {
		// The start of a name before the start of a word in one, and that before what an application says it is for.
		assertEquals(
			listOf("Firefox", "Firewall Configuration"),
			state("fire").titles("Applications").take(2),
		)
		assertEquals(
			listOf("Text Editor", "Visual Studio Code"),
			state("edit").titles("Applications"),
		)
		// The kind of program it is, the words it asks to be found by, and what it is run by.
		assertEquals(listOf("Foot"), state("terminal").titles("Applications"))
		assertEquals(listOf("Firefox"), state("www").titles("Applications"))
		assertEquals(listOf("Text Editor"), state("gnome-text").titles("Applications"))
		// The first letters of a name's words.
		assertEquals(listOf("Visual Studio Code"), state("vsc").titles("Applications"))
		// Every word must be found, wherever each is.
		assertEquals(listOf("Thunar File Manager"), state("file thun").titles("Applications"))
		assertEquals(emptyList<String>(), state("fire zzz").titles("Applications"))
		// Whatever the case.
		assertEquals(state("fire").titles("Applications"), state("FIRE").titles("Applications"))
	}

	@Test
	fun anApplicationsActionsAreFoundBehindItAndNotWhereTheUserWantsNone() {
		assertEquals(
			listOf("Firefox", "New Private Window", "New Window"),
			state("firefox").titles("Applications"),
		)
		assertEquals(listOf("New Private Window"), state("private").titles("Applications"))
		// Part of its application's name alone finds the application, and not everything it can be started to do.
		assertEquals(listOf("Firefox"), state("firef").titles("Applications"))
		// A word in the action's name and one in its application's find the action.
		assertEquals(
			listOf("New Private Window", "New Window"),
			state("fire window").titles("Applications"),
		)

		val without =
			step(
				state(),
				LauncherEvent.PreferencesChanged(LauncherPreferences(actions = false)),
			).state.after(LauncherEvent.Typed("firefox")).state

		assertEquals(listOf("Firefox"), without.titles("Applications"))
	}

	@Test
	fun whatIsUsedComesBeforeWhatFitsAsWellAndHiddenApplicationsAreLeftOut() {
		assertEquals("Firefox", state("fire").entries.first().title)

		val used =
			(1..6).fold(Launches.NONE) { launches, _ ->
				launches.used(Entry.App(Programs.fire).key, now)
			}

		assertEquals("Firewall Configuration", state("fire", used).entries.first().title)

		val hidden =
			state().after(
				LauncherEvent.PreferencesChanged(
					LauncherPreferences(hidden = setOf(Programs.firefox.id)),
				),
				LauncherEvent.Typed("fire"),
			)

		assertEquals(listOf("Firewall Configuration"), hidden.state.titles("Applications"))
	}

	@Test
	fun whatPrismDoesIsFoundByItsNameAndOtherWords() {
		assertEquals(listOf("Settings"), state("settings").titles("Prism"))
		assertEquals(listOf("Settings"), state("preferences").titles("Prism"))
		assertEquals(listOf("Files"), state("explorer").titles("Prism"))

		val asked = state("settings").after(LauncherEvent.Entered)

		assertEquals(listOf(LauncherEffect.Settings, LauncherEffect.Close), asked.effects)
		assertEquals(
			listOf(LauncherEffect.Explore, LauncherEffect.Close),
			state("explorer").after(LauncherEvent.Entered).effects,
		)
	}

	@Test
	fun filesAreAskedOfTheIndexAsItIsTypedAndShownAfterEverythingElse() {
		val typed = state().after(LauncherEvent.Typed("report"))
		val search = typed.effects.single() as LauncherEffect.Search

		assertEquals("report", search.text)
		assertEquals(LauncherPreferences().fileResults, search.most)
		assertTrue(typed.state.searching)

		val found =
			typed.state.after(
				LauncherEvent.FilesArrived(
					"report",
					listOf(file("/home/a/Documents/report.pdf")),
					40,
				),
			)

		assertFalse(found.state.searching)
		assertEquals(listOf("report.pdf"), found.state.titles("Files"))
		assertEquals("Files", found.state.headingAt(found.state.entries.size - 1))

		// What was found stays, rather than the list emptying at every key, until the answer for what is typed now
		// arrives; an answer for what was typed before is dropped.
		val stale =
			found.state.after(
				LauncherEvent.Typed("fire"),
				LauncherEvent.FilesArrived("report", listOf(file("/home/a/old.pdf")), 1),
			)

		assertEquals(listOf("report.pdf"), stale.state.titles("Files"))
		assertTrue(stale.state.searching)
		assertEquals(
			"Firefox",
			stale.state.entries
				.first()
				.title,
		)
		assertEquals(
			emptyList<String>(),
			stale.state
				.after(
					LauncherEvent.FilesArrived("fire", emptyList(), 0),
				).state
				.titles("Files"),
		)

		// Nothing typed asks for nothing; nor does anything, where the user wants no files or there is no index.
		assertEquals(
			emptyList<LauncherEffect>(),
			state("fire").after(LauncherEvent.Typed("")).effects,
		)
		assertEquals(
			emptyList<LauncherEffect>(),
			state()
				.after(
					LauncherEvent.PreferencesChanged(LauncherPreferences(files = false)),
					LauncherEvent.Typed("report"),
				).effects,
		)
		assertEquals(
			emptyList<LauncherEffect>(),
			state().copy(searchable = false).after(LauncherEvent.Typed("report")).effects,
		)
	}

	@Test
	fun theArrowsStopAtEitherEndAndTypingStartsFromTheFirstResult() {
		val shown = state()
		val last = shown.entries.size - 1

		assertEquals(0, shown.after(LauncherEvent.Moved(-1)).state.highlighted)
		assertEquals(
			2,
			shown.after(LauncherEvent.Moved(1), LauncherEvent.Moved(1)).state.highlighted,
		)
		assertEquals(last, shown.after(LauncherEvent.Moved(100)).state.highlighted)
		assertEquals(last, shown.after(LauncherEvent.MovedTo(last = true)).state.highlighted)
		assertEquals(
			0,
			shown
				.after(
					LauncherEvent.MovedTo(last = true),
					LauncherEvent.MovedTo(last = false),
				).state.highlighted,
		)
		assertEquals(
			0,
			shown.after(LauncherEvent.Moved(3), LauncherEvent.Typed("f")).state.highlighted,
		)
		// Fewer results than where the arrows were: on the last of them.
		assertEquals(
			1,
			shown
				.after(
					LauncherEvent.MovedTo(last = true),
					LauncherEvent.ApplicationsRead(listOf(Programs.firefox)),
					LauncherEvent.Moved(-1),
				).state.highlighted,
		)
	}

	@Test
	fun whatIsChosenIsStartedAndOnceItHasTheLauncherIsPutAwayAndItsUseKept() {
		val chosen = state("fire").after(LauncherEvent.Entered)
		val firefox = Entry.App(Programs.firefox)

		assertEquals(listOf(LauncherEffect.Start(firefox)), chosen.effects)

		val started = chosen.state.after(LauncherEvent.Started(firefox, now))

		assertEquals(
			listOf(LauncherEffect.Remember(firefox.key, now), LauncherEffect.Close),
			started.effects,
		)
		assertEquals(1.0, started.state.launches.weightOf(firefox.key, now))

		// One that could not be started says why, and the launcher stays.
		val failed =
			chosen.state.after(
				LauncherEvent.Started(firefox, now, failed = "no such program"),
			)

		assertEquals(emptyList<LauncherEffect>(), failed.effects)
		assertEquals("no such program", failed.state.notice)
		assertNull(
			failed.state
				.after(LauncherEvent.Typed("fir"))
				.state.notice,
		)

		// A click chooses the row clicked, wherever the arrows are.
		val clicked = state("fire").after(LauncherEvent.Chosen(1))

		assertEquals(
			listOf(LauncherEffect.Start(Entry.App(Programs.fire))),
			clicked.effects,
		)
		assertEquals(
			emptyList<LauncherEffect>(),
			state("fire").after(LauncherEvent.Chosen(99)).effects,
		)
	}

	@Test
	fun escapeLetsGoOfWhatIsTypedAndThenOfTheLauncher() {
		val cleared = state("fire").after(LauncherEvent.Dismissed)

		assertEquals("", cleared.state.query)
		assertEquals(emptyList<LauncherEffect>(), cleared.effects)
		assertEquals(
			listOf(LauncherEffect.Close),
			cleared.state.after(LauncherEvent.Dismissed).effects,
		)
		// Its window closed, or the keyboard gone elsewhere, it is put away whatever is typed.
		assertEquals(
			listOf(LauncherEffect.Close),
			state("fire").after(LauncherEvent.Closed).effects,
		)
	}

	@Test
	fun aResultIsShownInItsFolderAndItsPathCopied() {
		val found =
			state("report").after(
				LauncherEvent.FilesArrived("report", listOf(file("/home/a/Documents/report.pdf")), 1),
			)

		val path = Path.of("/home/a/Documents/report.pdf")

		assertEquals(
			listOf(LauncherEffect.Reveal(path), LauncherEffect.Close),
			found.state.after(LauncherEvent.Revealed).effects,
		)
		assertEquals(
			listOf(LauncherEffect.Copy(path.toString()), LauncherEffect.Close),
			found.state.after(LauncherEvent.CopiedPath).effects,
		)
		// An application's is its desktop entry; what prism does is nowhere.
		assertEquals(
			listOf(
				LauncherEffect.Reveal(Path.of("/usr/share/applications/firefox.desktop")),
				LauncherEffect.Close,
			),
			state("firefox").after(LauncherEvent.Revealed).effects,
		)
		assertEquals(
			emptyList<LauncherEffect>(),
			state("settings").after(LauncherEvent.Revealed).effects,
		)
	}

	@Test
	fun aUseCountsHalfAsMuchEveryTwoWeeksAndIsKeptInAFile(
		@TempDir folder: Path,
	) {
		val used = Launches.NONE.used("app:a.desktop", now).used("app:a.desktop", now)

		assertEquals(2.0, used.weightOf("app:a.desktop", now), 1e-9)
		assertEquals(1.0, used.weightOf("app:a.desktop", now.plus(Duration.ofDays(14))), 1e-9)
		assertEquals(0.0, used.weightOf("app:b.desktop", now))
		assertTrue(used.pointsOf("app:a.desktop", now) > 0)

		val file = folder.resolve("state/launches")
		val store = LaunchFile(file)

		assertEquals(Launches.NONE, store.launches)
		store.used("app:a.desktop", now)
		store.used("file:/home/a/with\ttab and space.txt", now)
		store.used("app:a.desktop", now)

		val read = LaunchFile(file).launches

		assertEquals(2.0, read.weightOf("app:a.desktop", now), 1e-9)
		assertEquals(1.0, read.weightOf("file:/home/a/with\ttab and space.txt", now), 1e-9)

		// A file that is not launches is none, and is written over by the next use.
		file.toFile().writeText("not\nlaunches\n")
		assertEquals(Launches.NONE, LaunchFile(file).launches)
	}

	@Test
	fun theKeysAreTheUsersWhereTheyHaveBoundOthers() {
		val defaults = launcherBindings()

		assertEquals(LauncherAction.Open, defaults.action(chord("Enter")))
		assertEquals(LauncherAction.Reveal, defaults.action(chord("Ctrl+Enter")))
		assertEquals(LauncherAction.Down, defaults.action(chord("Ctrl+N")))
		assertNull(defaults.action(chord("Ctrl+Q")))
		// Each is in a group, in the order the page that binds them lists them.
		assertEquals(
			listOf("The result chosen", "Moving through the results", "The launcher"),
			LauncherAction.entries.map { it.group.label }.distinct(),
		)

		// Bound to another action's chord, it is taken from that action; what differs is what the preferences keep,
		// by each action's name, and what is read back from them.
		val bound = defaults.bound(LauncherAction.Settings, 0, chord("Ctrl+N"))
		val written = LauncherPreferences(bindings = bound.differences())

		assertEquals(
			mapOf(
				"Settings" to listOf("Ctrl+N"),
				"Down" to listOf("Down", "Ctrl+J", "Tab"),
			),
			written.bindings,
		)

		val read = launcherBindings(written)

		assertEquals(bound, read)
		assertEquals(LauncherAction.Settings, read.action(chord("Ctrl+N")))
		// Reset, each has its defaults again, and nothing is written.
		assertEquals(
			emptyMap<String, List<String>>(),
			read.reset(LauncherAction.Settings).reset(LauncherAction.Down).differences(),
		)
	}

	private fun chord(text: String) =
		prism.browser.Chord.parse(text) ?: error("“$text” is not a chord")
}
