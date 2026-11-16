package prism.launcher

import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.browser.Category
import prism.index.query.Own
import prism.preferences.LauncherPreferences
import prism.window.OpenWindow

/**
 * A result's menu: what can be done to each kind of result, and what the reducer makes of opening the menu, moving
 * in it, leaving it, and doing each thing in it.
 */
class LauncherActionsTest {
	private val now = Instant.parse("2026-11-14T10:00:00Z")

	private val notes =
		Entry.File(
			Path.of("/home/u/Documents/notes.md"),
			Category.Text,
			"~/Documents",
			"Markdown",
		)
	private val documents =
		Entry.File(Path.of("/home/u/Documents"), Category.Folder, "~", "Folder")

	private fun state(query: String, files: List<Entry.File> = emptyList()): LauncherState =
		LauncherState(installed = Installed.of(Programs.all), now = now)
			.after(
				LauncherEvent.Typed(query),
				LauncherEvent.FilesArrived(query, files, files.size),
			).state

	private fun LauncherState.after(vararg events: LauncherEvent) =
		events.fold(LauncherStep(this)) { at, event -> step(at.state, event) }

	private fun labels(entry: Entry) = actionsOf(entry).map { it.label }

	/** [state] with the menu of the entry titled [title] open, and the arrows on the action labelled [label]. */
	private fun LauncherState.on(title: String, label: String): LauncherState {
		val at = entries.indexOfFirst { it.title == title }
		val opened = after(LauncherEvent.MenuOpened(at)).state
		val menu = opened.menu!!

		return opened
			.after(LauncherEvent.Moved(menu.actions.actions.indexOfFirst { it.label == label }))
			.state
	}

	@Test
	fun eachKindOfResultHasItsActionsTheFirstWhatEnterDoes() {
		assertEquals(
			listOf(
				"Open",
				"New Window",
				"New Private Window",
				"Show desktop entry in folder",
				"Copy desktop entry path",
				"Hide from launcher",
			),
			labels(Entry.App(Programs.firefox)),
		)
		assertEquals(
			listOf("Open", "Show desktop entry in folder", "Copy desktop entry path"),
			labels(Entry.Action(Programs.firefox, Programs.firefox.actions.first())),
		)
		assertEquals(
			listOf(
				"Open",
				"Show in folder",
				"Open with…",
				"Copy",
				"Copy path",
				"Copy name",
				"Open terminal here",
				"Properties",
				"Move to trash",
			),
			labels(notes),
		)
		// A folder is opened with nothing but an explorer.
		assertEquals(labels(notes) - "Open with…", labels(documents))
		// What prism does is only done, so the right arrow never does nothing.
		assertEquals(listOf("Open"), labels(Entry.Prism(Own.Settings)))
		assertEquals(listOf("Open"), labels(Entry.Command(LauncherCommand.Files)))
		// An application with no desktop entry on disk has none to show.
		assertEquals(
			listOf("Open", "Hide from launcher"),
			labels(Entry.App(Programs.terminal.copy(source = null))),
		)
		// The keys that do the same without the menu are the launcher's own.
		assertEquals(
			listOf(
				LauncherAction.Open,
				LauncherAction.Reveal,
				null,
				LauncherAction.Copy,
				LauncherAction.CopyPath,
			),
			actionsOf(notes).take(5).map { it.key },
		)
	}

	@Test
	fun theMenuOpensOnTheResultTheArrowsAreOnAndTheArrowsAndEscapeAreItsOwnWhileItIsOpen() {
		val typed = state("fire")
		val opened = typed.after(LauncherEvent.MenuOpened())

		assertEquals(emptyList<LauncherEffect>(), opened.effects)
		assertEquals(Entry.App(Programs.firefox), opened.state.menu?.entry)
		assertEquals(
			0,
			opened.state.menu
				?.actions
				?.on,
		)

		// The arrows move in the menu, stopping at its ends, and the result stays the one it is.
		val down = opened.state.after(LauncherEvent.Moved(1), LauncherEvent.Moved(1)).state

		assertEquals(2, down.menu?.actions?.on)
		assertEquals(typed.highlighted, down.highlighted)
		assertEquals(
			5,
			down
				.after(LauncherEvent.Moved(40))
				.state.menu
				?.actions
				?.on,
		)
		assertEquals(
			0,
			down
				.after(LauncherEvent.Moved(-40))
				.state.menu
				?.actions
				?.on,
		)
		assertEquals(
			5,
			down
				.after(LauncherEvent.MovedTo(last = true))
				.state.menu
				?.actions
				?.on,
		)

		// Escape, and the left arrow, put the menu away and nothing more: what was typed is still there.
		for (leaving in listOf(LauncherEvent.Dismissed, LauncherEvent.MenuClosed)) {
			val left = down.after(leaving)

			assertNull(left.state.menu)
			assertEquals("fire", left.state.query)
			assertEquals(emptyList<LauncherEffect>(), left.effects)
		}
		// Asked for again, it is as it was; typing puts it away.
		assertEquals(down, down.after(LauncherEvent.MenuOpened()).state)
		assertNull(down.after(LauncherEvent.Typed("firef")).state.menu)
		// What comes back from work under way leaves it open.
		assertEquals(
			down.menu,
			down.after(LauncherEvent.FilesArrived("fire", emptyList(), 0)).state.menu,
		)

		// A right click on another result: its menu, and the arrows on it.
		val other = down.after(LauncherEvent.MenuOpened(1)).state

		assertEquals("Firewall Configuration", other.menu?.entry?.title)
		assertEquals(1, other.highlighted)
		// With nothing found there is nothing to have a menu.
		assertNull(state("zzzz").after(LauncherEvent.MenuOpened()).state.menu)
		// And with no menu open, there is none to leave or to choose from.
		assertEquals(LauncherStep(typed), typed.after(LauncherEvent.MenuClosed))
		assertEquals(LauncherStep(typed), typed.after(LauncherEvent.MenuChosen(1)))
	}

	@Test
	fun eachActionOfAnApplicationIsDoneFromItsMenu() {
		val typed = state("firefox")
		val firefox = Entry.App(Programs.firefox)

		fun done(label: String) = typed.on("Firefox", label).after(LauncherEvent.Entered)

		// Enter on the first is what Enter on the row is.
		assertEquals(
			listOf<LauncherEffect>(LauncherEffect.Start(firefox)),
			done("Open").effects,
		)
		assertNull(done("Open").state.menu)
		assertEquals(
			listOf<LauncherEffect>(
				LauncherEffect.Start(
					Entry.Action(Programs.firefox, Programs.firefox.actions[1]),
				),
			),
			done("New Private Window").effects,
		)
		assertEquals(
			listOf(LauncherEffect.Reveal(Programs.firefox.source!!), LauncherEffect.Close),
			done("Show desktop entry in folder").effects,
		)
		assertEquals(
			listOf(LauncherEffect.Copy("${Programs.firefox.source}"), LauncherEffect.Close),
			done("Copy desktop entry path").effects,
		)
		assertEquals(
			listOf<LauncherEffect>(LauncherEffect.Hide(Programs.firefox)),
			done("Hide from launcher").effects,
		)

		// A click on an action does it as Enter on it does.
		assertEquals(
			done("Hide from launcher").effects,
			typed.after(LauncherEvent.MenuOpened(0), LauncherEvent.MenuChosen(5)).effects,
		)

		// Hidden, it is said so, the launcher stays, and once its settings say so it is no longer found.
		val said = LauncherNotice.Done("“Firefox” is hidden. Show it again in Settings.")

		val hidden =
			done("Hide from launcher").state.after(
				LauncherEvent.Acted(said),
				LauncherEvent.PreferencesChanged(
					LauncherPreferences(hidden = setOf(Programs.firefox.id)),
				),
			)

		assertEquals(said, hidden.state.notice)
		assertTrue(hidden.effects.none { it == LauncherEffect.Close })
		assertTrue(hidden.state.entries.none { it.title == "Firefox" })
	}

	@Test
	fun eachActionOfAFileIsDoneFromItsMenu() {
		val typed = state("notes", listOf(notes, documents))

		fun done(title: String, label: String) =
			typed.on(title, label).after(LauncherEvent.Entered)

		assertEquals(
			listOf<LauncherEffect>(LauncherEffect.Start(notes)),
			done("notes.md", "Open").effects,
		)
		assertEquals(
			listOf(LauncherEffect.Reveal(notes.path), LauncherEffect.Close),
			done("notes.md", "Show in folder").effects,
		)
		assertEquals(
			listOf(LauncherEffect.OpenWith(notes.path), LauncherEffect.Close),
			done("notes.md", "Open with…").effects,
		)
		assertEquals(
			listOf(LauncherEffect.Copy("/home/u/Documents/notes.md"), LauncherEffect.Close),
			done("notes.md", "Copy path").effects,
		)
		assertEquals(
			listOf(LauncherEffect.Copy("notes.md"), LauncherEffect.Close),
			done("notes.md", "Copy name").effects,
		)
		assertEquals(
			listOf(LauncherEffect.Properties(notes.path), LauncherEffect.Close),
			done("notes.md", "Properties").effects,
		)
		// A terminal in the folder a file is in, and in a folder itself.
		assertEquals(
			listOf<LauncherEffect>(LauncherEffect.Terminal(documents.path)),
			done("notes.md", "Open terminal here").effects,
		)
		assertEquals(
			listOf<LauncherEffect>(LauncherEffect.Terminal(documents.path)),
			done("Documents", "Open terminal here").effects,
		)
		assertEquals(
			listOf<LauncherEffect>(LauncherEffect.Trash(notes.path)),
			done("notes.md", "Move to trash").effects,
		)

		// What is done here is told back: with nothing to say, the launcher is put away; with something, it stays
		// and says it, and looks again for what is typed, since what was done may have changed what there is.
		val trashing = done("notes.md", "Move to trash").state

		assertEquals(
			listOf<LauncherEffect>(LauncherEffect.Close),
			trashing.after(LauncherEvent.Acted()).effects,
		)

		val moved = LauncherNotice.Done("Moved “notes.md” to the trash.")
		val told = trashing.after(LauncherEvent.Acted(moved))

		assertEquals(moved, told.state.notice)
		assertTrue(told.effects.single() is LauncherEffect.Search)

		val failed = LauncherNotice.Failed("Couldn't move “notes.md” to the trash. Read-only.")

		assertEquals(failed, trashing.after(LauncherEvent.Acted(failed)).state.notice)
	}

	@Test
	fun whatPrismDoesHasOneActionWhichIsToDoIt() {
		val settings = state("settings").after(LauncherEvent.MenuOpened())

		assertEquals(Entry.Prism(Own.Settings), settings.state.menu?.entry)
		assertEquals(
			listOf<EntryAction>(EntryAction.Open),
			settings.state.menu
				?.actions
				?.actions,
		)
		assertEquals(
			listOf(LauncherEffect.Settings, LauncherEffect.Close),
			settings.state.after(LauncherEvent.Entered).effects,
		)
	}

	private val shell = OpenWindow(1, "foot", "~/projects")
	private val editing = OpenWindow(2, "Foot", "vim notes.md")
	private val browsing = OpenWindow(3, "org.mozilla.firefox", "Mozilla Firefox")

	/** The launcher with "foot" typed, shown while [windows] were open. */
	private fun running(vararg windows: OpenWindow): LauncherState =
		LauncherState(installed = Installed.of(Programs.all, windows.toList()), now = now)
			.after(LauncherEvent.Typed("foot"))
			.state

	@Test
	fun aWindowIsAnApplicationsByItsEntrysNameOrByWhatTheEntrySaysItsWindowsAreCalled() {
		val windows = listOf(shell, editing, browsing)

		// By the desktop entry's name without its ending, in whatever case the window says it.
		assertEquals(listOf(shell, editing), windowsOf(Programs.terminal, windows))
		// Firefox's entry is firefox.desktop and its window says org.mozilla.firefox: not its own by name,
		assertEquals(emptyList<OpenWindow>(), windowsOf(Programs.firefox, windows))
		// and its own once the entry says what its windows are called.
		assertEquals(
			listOf(browsing),
			windowsOf(Programs.firefox.copy(wmClass = "org.mozilla.Firefox"), windows),
		)
		assertEquals(emptyList<OpenWindow>(), windowsOf(Programs.code, windows))
		assertEquals(emptyList<OpenWindow>(), windowsOf(Programs.terminal, emptyList()))
	}

	@Test
	fun anApplicationThatIsRunningIsBroughtForwardFromItsMenuAndStartedByEnterAsEver() {
		val one = running(shell, browsing)
		val foot = one.entries.first() as Entry.App

		assertEquals(Programs.terminal, foot.application)
		assertEquals(listOf(shell), foot.windows)
		// Bringing its window forward comes right after what Enter does.
		assertEquals(
			listOf(
				"Open",
				"Focus window",
				"Show desktop entry in folder",
				"Copy desktop entry path",
				"Hide from launcher",
			),
			labels(foot),
		)
		// Enter on the row starts it, as it does one that is not running; and that use is kept under the same name.
		assertEquals(
			listOf<LauncherEffect>(LauncherEffect.Start(foot)),
			one.after(LauncherEvent.Entered).effects,
		)
		assertEquals(Entry.App(Programs.terminal).key, foot.key)
		// From the menu, its window is brought forward and the launcher put away: nothing is started.
		assertEquals(
			listOf(LauncherEffect.Focus(shell), LauncherEffect.Close),
			one.on("Foot", "Focus window").after(LauncherEvent.Entered).effects,
		)

		// With several windows, each is brought forward by its own action, which says which it is.
		val two = running(shell, editing)

		assertEquals(
			listOf("Open", "Focus window: ~/projects", "Focus window: vim notes.md"),
			labels(two.entries.first()).take(3),
		)
		assertEquals(
			listOf(LauncherEffect.Focus(editing), LauncherEffect.Close),
			two.on("Foot", "Focus window: vim notes.md").after(LauncherEvent.Entered).effects,
		)

		// What is installed read again, the windows open are those they were.
		val reread = one.after(LauncherEvent.ApplicationsRead(Programs.all.reversed())).state

		assertEquals(listOf(shell), (reread.entries.first() as Entry.App).windows)
	}

	@Test
	fun whereNoWindowIsToldOfNothingIsOfferedToBeBroughtForward() {
		// The desktop tells of none (it has no way to), or the application has none open: either way, no windows.
		val none = running()
		val other = running(browsing)

		for (state in listOf(none, other)) {
			val foot = state.entries.first()

			assertEquals(Entry.App(Programs.terminal), foot)
			assertTrue(actionsOf(foot).none { it is EntryAction.Focus })
		}
		// One of its other things to do is no window of its own to bring forward.
		assertTrue(
			actionsOf(Entry.Action(Programs.firefox, Programs.firefox.actions.first()))
				.none { it is EntryAction.Focus },
		)
	}

	@Test
	fun showingActionsIsAKeyOfTheLaunchersLikeAnyOther() {
		val defaults = launcherBindings()
		val right = prism.browser.Chord.parse("Right")!!
		val other = prism.browser.Chord.parse("Ctrl+O")!!

		assertEquals(LauncherAction.ShowActions, defaults.action(right))
		// The right arrow types nothing, so the page that binds the launcher's keys takes it.
		assertTrue(!right.typesText)
		assertEquals(emptyList<Any>(), defaults.clashes(LauncherAction.ShowActions, right))

		val bound = defaults.bound(LauncherAction.ShowActions, 0, other)
		val read = launcherBindings(LauncherPreferences(bindings = bound.differences()))

		assertEquals(mapOf("ShowActions" to listOf("Ctrl+O")), bound.differences())
		assertEquals(LauncherAction.ShowActions, read.action(other))
		assertNull(read.action(right))
	}
}
