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
import prism.index.query.Own
import prism.preferences.LauncherPreferences

/**
 * What the launcher shows for what is typed, and what it does with what happens to it: the reducer alone, with nothing
 * started and nothing searched.
 */
class LauncherStateTest {
	private val now = Instant.parse("2026-11-14T10:00:00Z")

	private fun state(query: String = "", launches: Launches = Launches.NONE) =
		LauncherState(installed = Installed.of(Programs.all), launches = launches, now = now)
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
	fun theWordPrismAsksForPrismsOwnWhichComeBeforeEverythingElseFound() {
		// Alone, every one of them, and whatever else prism does: before the applications, the arrows on the first.
		val all = state("prism")

		assertEquals(
			listOf("Settings", "Key bindings", "System", "Search help", "Files"),
			all.titles("Prism"),
		)
		assertEquals("Prism", all.headingAt(0))
		assertEquals(Entry.Prism(Own.Settings), all.current)

		// With other words, those whose words they begin; `prism` wherever it comes, in whatever case.
		assertEquals(listOf("Settings"), state("prism settings").titles("Prism"))
		assertEquals(listOf("Settings"), state("prism set").titles("Prism"))
		assertEquals(listOf("Settings"), state("Settings PRISM").titles("Prism"))
		assertEquals(listOf("Key bindings"), state("prism bindings").titles("Prism"))
		assertEquals(listOf("System"), state("prism system").titles("Prism"))
		assertEquals(listOf("Search help"), state("prism help").titles("Prism"))
		assertEquals(emptyList<String>(), state("prism nothing").titles("Prism"))
		// Only the word itself: not a word it begins, or that holds it.
		assertEquals(emptyList<String>(), state("prisms").titles("Prism"))

		// An application that fits what is typed as well comes after them, and so do the files found for it.
		val tool = Programs.application("prism-settings", "Prism Settings Tool")

		val beside =
			step(
				LauncherState(installed = Installed.of(Programs.all + tool), now = now),
				LauncherEvent.Typed("prism set"),
			).state
				.after(
					LauncherEvent.FilesArrived(
						"prism set",
						listOf(file("/home/u/prism settings.txt")),
						1,
					),
				).state

		assertEquals(
			listOf(
				"Prism" to "Settings",
				"Application" to "Prism Settings Tool",
				"Document" to "prism settings.txt",
			),
			beside.entries.map { it.kind to it.title },
		)
		assertEquals(listOf("Prism", "Applications", "Files"), beside.sections.map { it.title })
		assertEquals(Entry.Prism(Own.Settings), beside.current)
		// The arrows go past it to the rest.
		assertEquals(
			"Prism Settings Tool",
			beside
				.after(LauncherEvent.Moved(1))
				.state.current
				?.title,
		)
	}

	@Test
	fun eachOfPrismsOwnChosenIsDoneWhereItBelongs() {
		assertEquals(
			listOf(LauncherEffect.Settings, LauncherEffect.Close),
			state("prism settings").after(LauncherEvent.Entered).effects,
		)
		assertEquals(
			listOf(LauncherEffect.Bindings, LauncherEffect.Close),
			state("prism bindings").after(LauncherEvent.Entered).effects,
		)
		assertEquals(
			listOf(LauncherEffect.System, LauncherEffect.Close),
			state("prism system").after(LauncherEvent.Entered).effects,
		)
		// Clicked, as well: the second of them all is the key bindings.
		assertEquals(
			listOf(LauncherEffect.Bindings, LauncherEffect.Close),
			state("prism").after(LauncherEvent.Chosen(1)).effects,
		)

		// Settings asked for by its own words, with no `prism`, is the same entry, after the applications as it was;
		// and it is said once where both find it.
		assertEquals(Entry.Prism(Own.Settings), state("preferences").entries.single())
		assertEquals(1, state("prism settings").entries.count { it.title == "Settings" })
		// None of them is anywhere to show in a folder.
		assertEquals(
			emptyList<LauncherEffect>(),
			state("prism system").after(LauncherEvent.Revealed).effects,
		)
	}

	@Test
	fun aBareQuestionMarkAsksHowAQueryIsWrittenShownInTheLaunchersPlaceUntilLeft() {
		val asked = state("?")

		assertEquals(Entry.Prism(Own.Help), asked.entries.first())
		assertEquals(Entry.Prism(Own.Help), asked.current)
		assertFalse(asked.help)
		// Only alone.
		assertTrue(state("? report").entries.none { it is Entry.Prism })

		// Chosen, it is shown here: nothing is asked of anybody, and the launcher stays.
		val shown = asked.after(LauncherEvent.Entered)

		assertTrue(shown.state.help)
		assertEquals(emptyList<LauncherEffect>(), shown.effects)
		assertEquals("?", shown.state.query)

		// What moves through the results, or chooses one, or types, does nothing while they are not there.
		for (event in listOf(
			LauncherEvent.Moved(1),
			LauncherEvent.MovedTo(last = true),
			LauncherEvent.Entered,
			LauncherEvent.Chosen(0),
			LauncherEvent.Revealed,
			LauncherEvent.CopiedPath,
			LauncherEvent.Typed("firefox"),
		))
			assertEquals(LauncherStep(shown.state), step(shown.state, event), "$event")

		// Escape, and its ×, leave it for the launcher as it was; a second Escape is then the launcher's own.
		for (leaving in listOf(LauncherEvent.Dismissed, LauncherEvent.HelpClosed)) {
			val left = step(shown.state, leaving)

			assertEquals(asked, left.state)
			assertEquals(emptyList<LauncherEffect>(), left.effects)
		}
		assertEquals(
			"",
			shown.state
				.after(LauncherEvent.Dismissed, LauncherEvent.Dismissed)
				.state.query,
		)

		// A query of the page's tried: typed into the field and searched for, the page left.
		val tried = step(shown.state, LauncherEvent.Tried("type:image"))

		assertFalse(tried.state.help)
		assertEquals("type:image", tried.state.query)
		assertEquals(
			listOf("type:image"),
			tried.effects.filterIsInstance<LauncherEffect.Search>().map { it.text },
		)

		// The launcher closed from outside is closed, whatever it shows; and what comes back for it is still taken.
		assertEquals(
			listOf(LauncherEffect.Close),
			step(shown.state, LauncherEvent.Closed).effects,
		)
		assertEquals(
			FilesFound.Found("?", emptyList(), 0),
			step(shown.state, LauncherEvent.FilesArrived("?", emptyList(), 0)).state.files,
		)
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
		assertEquals(LauncherPreferences().fileResults.number, search.most)
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
		// Fewer results than there were: the arrows are on the entry they were on, wherever it now is.
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
	fun theArrowsStayOnAnEntryWhenWhatIsShownChangesAroundItAndGoToTheFirstWhenItIsGone() {
		val shown = state()
		val on = shown.after(LauncherEvent.Moved(2)).state
		val entry = on.current!!

		// What is installed read again, in another order and with one fewer before it: the same entry.
		val fewer =
			step(
				on,
				LauncherEvent.ApplicationsRead(
					Programs.all.filterNot { Entry.App(it) == shown.entries.first() }.reversed(),
				),
			).state

		assertEquals(entry, fewer.current)
		assertEquals(fewer.entries.indexOf(entry), fewer.highlighted)

		// The entry itself gone: the first.
		val gone =
			step(
				on,
				LauncherEvent.ApplicationsRead(Programs.all.filterNot { Entry.App(it) == entry }),
			).state

		assertEquals(0, gone.highlighted)
		assertEquals(gone.entries.first(), gone.current)

		// Nothing shown at all: nothing the arrows are on, and nowhere past the end to be.
		val nothing = step(on, LauncherEvent.Typed("zzzz no such thing")).state

		assertTrue(nothing.entries.isEmpty())
		assertNull(nothing.current)
		assertEquals(0, nothing.after(LauncherEvent.Moved(5)).state.highlighted)
	}

	@Test
	fun aSearchThatWentWrongIsAnAnswerForWhatWasTypedAndOnlyForThat() {
		val typed = state("report")

		assertTrue(typed.searching)

		val failed = typed.after(LauncherEvent.SearchFailed("report")).state

		assertEquals(FilesFound.Failed("report"), failed.files)
		assertFalse(failed.searching)
		// One for what was typed before is dropped, as an answer for it is.
		assertEquals(typed.files, typed.after(LauncherEvent.SearchFailed("repo")).state.files)
		// And typing on asks again.
		assertTrue(failed.after(LauncherEvent.Typed("reports")).state.searching)
	}

	@Test
	fun whatAnApplicationSaysOfItselfIsMadeReadyOnceAndFoundAsItWas() {
		val said = Said("Visual Studio Code", 100)

		assertEquals("visual studio code", said.lower)
		assertEquals("vsc", said.initials)
		// Read again, the same applications are the same thing installed: nothing is shown anew for it.
		assertEquals(Installed.of(Programs.all), Installed.of(Programs.all))
		assertEquals(Programs.all, Installed.of(Programs.all).applications)
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
		assertEquals(LauncherNotice.Failed("no such program"), failed.state.notice)
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
		assertEquals(LauncherAction.Down, defaults.action(chord("Ctrl+J")))
		assertEquals(LauncherAction.Settings, defaults.action(chord("Ctrl+P")))
		assertNull(defaults.action(chord("Ctrl+Q")))
		// Each is in a group, in the order the page that binds them lists them.
		assertEquals(
			listOf("Result", "Navigation", "Launcher"),
			LauncherAction.entries.map { it.group.label }.distinct(),
		)

		// Bound to another action's chord, it is taken from that action; what differs is what the preferences keep,
		// by each action's name, and what is read back from them.
		val bound = defaults.bound(LauncherAction.Settings, 0, chord("Ctrl+J"))
		val written = LauncherPreferences(bindings = bound.differences())

		assertEquals(
			mapOf(
				"Settings" to listOf("Ctrl+J"),
				"Down" to listOf("Down", "Tab"),
			),
			written.bindings,
		)

		val read = launcherBindings(written)

		assertEquals(bound, read)
		assertEquals(LauncherAction.Settings, read.action(chord("Ctrl+J")))
		// Reset, each has its defaults again, and nothing is written.
		assertEquals(
			emptyMap<String, List<String>>(),
			read.reset(LauncherAction.Settings).reset(LauncherAction.Down).differences(),
		)
	}

	private fun chord(text: String) =
		prism.browser.Chord.parse(text) ?: error("“$text” is not a chord")
}
