package prism.browser

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.XdgPlaces
import prism.index.query.AliasName
import prism.preferences.PreferenceFile
import prism.preferences.RecentSection

/**
 * How a window learns that a setting changed: it is handed the change by the one store its process has of the
 * preferences' file, as the change is made, whichever window made it. No window reads the file: it is read as the
 * store is made, and by the store as it writes a change (which is made to the file as it then is), and that is all.
 */
class PushedTest {
	@TempDir
	lateinit var root: Path

	@Test
	fun twoWindowsOnOneStoreAreBothGivenAChangeOneOfThemMakes_AndNeitherReadsTheFile() {
		val reads = AtomicInteger()

		val store =
			PreferenceFile.open(root.resolve("config/preferences.json")) {
				reads.incrementAndGet()
				PreferenceFile.read(it)
			}

		val services = Services(JvmDisk(), preferences = store, home = root)
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val sidebar = sidebarOf(XdgPlaces(), root)

		fun window() =
			Session(opened(Choosing.Exploring(), root, root, sidebar), services, scope)

		val one = window()
		val other = window()

		try {
			runBlocking {
				withTimeout(30_000) {
					one.state.first { it.listing is Listing.Read }
					other.state.first { it.listing is Listing.Read }

					// Made in one window: a section of the Recents page collapsed, which is a preference.
					one.send(BrowserEvent.RecentSectionToggled(RecentSection.NewFiles))

					val collapsed = setOf(RecentSection.NewFiles)

					assertEquals(
						collapsed,
						one.state.first { it.recentsCollapsed == collapsed }.recentsCollapsed,
					)
					assertEquals(
						collapsed,
						other.state.first { it.recentsCollapsed == collapsed }.recentsCollapsed,
					)

					// And one made by neither (a settings window's, of the same store): both are given it.
					store.update { it.copy(aliases = mapOf("docs" to "ext:pdf")) }

					val docs = checkNotNull(AliasName.of("docs"))

					one.state.first { it.aliases[docs] != null }
					other.state.first { it.aliases[docs] != null }
				}
			}

			// The file was read as the store was made, and once by the store for each change it wrote: the two
			// windows, and everything they were told, read it not at all.
			assertEquals(3, reads.get())
			assertEquals(
				setOf(RecentSection.NewFiles),
				PreferenceFile
					.read(store.file)
					.preferences.recentsCollapsed,
			)
		} finally {
			scope.cancel()
		}
	}
}
