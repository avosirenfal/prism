package prism.browser

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.FreedesktopTrash
import prism.desktop.freedesktop.XdgPlaces
import prism.preferences.ColumnId
import prism.preferences.Kind
import prism.preferences.Order
import prism.preferences.Sort

/**
 * A folder that changes on disk while it is shown: what is shown is never in an order made of half of what the
 * order is by, only the entries that changed are read again, and what changes unseen changes nothing that is drawn.
 */
class ChangedTest {
	@TempDir
	lateinit var root: Path

	private val home = Fixtures.home
	private val folder = home.resolve("Many")

	/** A row of [folder] made at the [made]th second of an hour. */
	private fun row(name: String, made: Int, bytes: Long? = 1): Row =
		Fixtures
			.row(name, bytes, folder = folder)
			.copy(
				created =
					Created.At(
						Instant.parse("2026-01-01T00:00:00Z").plusSeconds(made.toLong()),
					),
			)

	/** A few hundred folders and files, each made at a second of its own, in no order of names. */
	private val entries: List<Row> =
		(0 until 300).map { row("entry-%03d".format((it * 37) % 300), it, bytes = null) } +
			(0 until 200).map { row("file-%03d.txt".format((it * 53) % 200), 1000 + it) } +
			row(".history", 5000)

	private fun shown(): BrowserState =
		step(
			opened(Choosing.Exploring(), folder, home, Fixtures.sidebar).state,
			Answer.Arrived(folder, Listing.Shown(entries)),
		).state.let {
			// Newest first, as the first click of that heading orders them; and hidden files not shown.
			after(it, BrowserEvent.Sorted(ColumnId.Created), BrowserEvent.ToggledHiddenFiles)
		}

	private fun after(state: BrowserState, vararg inputs: Input): BrowserState =
		inputs.fold(state) { at, input -> step(at, input).state }

	/** Whether what [state] shows is folders and then files, each the newest made first. */
	private fun newestFirst(state: BrowserState): Boolean {
		val rows = state.rows.list
		val (folders, files) = rows.partition { it.isFolder }

		return rows == folders + files &&
			listOf(folders, files).all { part ->
				part.zipWithNext().all { (a, b) -> a.created.at!! >= b.created.at!! }
			}
	}

	@Test
	fun aFolderOrderedByWhenItsEntriesWereMadeIsNeverShownInAnotherOrderAsItChanges() {
		val first = shown()

		assertEquals(500, first.rows.size)
		assertTrue(newestFirst(first))

		// Told that an entry was made: that one is asked for, and nothing shown changes until it is read.
		val told = step(first, Answer.Touched(folder, Touch.Entries(setOf("asdf"))))

		assertEquals(listOf<Effect>(Effect.ReadEntries(folder, setOf("asdf"))), told.effects)
		assertSame(first, told.state)

		val made =
			step(
				told.state,
				Answer.EntriesRead(folder, mapOf("asdf" to row("asdf", 9000))),
			)

		assertEquals(emptyList<Effect>(), made.effects)
		assertEquals(501, made.state.rows.size)
		assertTrue(newestFirst(made.state))
		// The newest of the files: after the folders, before every other file.
		assertEquals("asdf", made.state.rows[300].name)

		// Taken away again, it is gone, and the rest are where they were.
		val gone = step(made.state, Answer.EntriesRead(folder, mapOf("asdf" to null))).state

		assertEquals(first.rows.list, gone.rows.list)
		assertTrue(newestFirst(gone))

		// Told only that something changed, the whole folder is read; what it arrives as is whole, and in order.
		val all = step(gone, Answer.Touched(folder))

		assertEquals(listOf<Effect>(Effect.Read(folder)), all.effects)
		assertSame(gone.rows, all.state.rows)

		// What it is told of while that read is under way waits for it: the read began before, and may not hold it.
		val meanwhile = step(all.state, Answer.Touched(folder, Touch.Entries(setOf("zxcv"))))

		assertEquals(emptyList<Effect>(), meanwhile.effects)
		assertSame(gone.rows, meanwhile.state.rows)

		val read =
			step(
				meanwhile.state,
				Answer.Arrived(
					folder,
					Listing.Shown(entries.shuffled(java.util.Random(7)) + row("qwer", 9500)),
				),
			)

		// The read back, what changed meanwhile is read, after it; and then nothing more is.
		assertEquals(listOf<Effect>(Effect.ReadEntries(folder, setOf("zxcv"))), read.effects)
		assertTrue(newestFirst(read.state))
		assertEquals("qwer", read.state.rows[300].name)

		val last =
			step(read.state, Answer.EntriesRead(folder, mapOf("zxcv" to row("zxcv", 9900))))

		assertEquals(emptyList<Effect>(), last.effects)
		assertEquals(
			listOf("zxcv", "qwer"),
			last.state.rows.list.subList(300, 302).map {
				it.name
			},
		)
		assertEquals(
			listOf<Effect>(Effect.ReadEntries(folder, setOf("a"))),
			step(last.state, Answer.Touched(folder, Touch.Entries(setOf("a")))).effects,
		)
	}

	@Test
	fun whatChangesAndIsNotShownChangesNothingThatIsDrawn() {
		val first = shown()

		assertTrue(first.rows.list.none { it.hidden })

		// A hidden file written to, while hidden files are not shown: held, and the rows shown are the very rows.
		val written =
			row(".history", 5000).copy(
				size = Shown(77, sizeOf(77)),
				modified = Instant.parse("2026-11-15T00:00:00Z"),
			)

		val unseen = step(first, Answer.EntriesRead(folder, mapOf(".history" to written))).state

		assertNotSame(first.listing, unseen.listing)
		assertSame(first.rows, unseen.rows)
		assertEquals(
			written,
			(unseen.listing as Listing.Shown).entries.single {
				it.name ==
					".history"
			},
		)

		// And one made, and one gone: the count of what the folder holds changes, and nothing else.
		val more =
			step(
				unseen,
				Answer.EntriesRead(folder, mapOf(".lock" to row(".lock", 9000), ".history" to null)),
			).state

		assertSame(first.rows, more.rows)
		assertEquals(first.entryCount, more.entryCount)

		// What is shown, told to have changed and read to be as it was (written with what it held): the very state.
		val same = entries.first { it.name == "file-007.txt" }

		assertSame(
			first,
			step(first, Answer.EntriesRead(folder, mapOf(same.name to same.copy()))).state,
		)
		// The whole folder read again and found as it was: the very state, whatever order the disk gave it in.
		assertSame(
			first,
			step(first, Answer.Arrived(folder, Listing.Shown(entries.reversed()))).state,
		)

		// A filter that leaves files out: a file changed is not seen, a folder changed is.
		val folders = step(first, BrowserEvent.ToggledKind(Kind.Folders)).state

		assertEquals(300, folders.rows.size)
		assertSame(
			folders.rows,
			step(
				folders,
				Answer.EntriesRead(folder, mapOf("asdf" to row("asdf", 9000))),
			).state.rows,
		)

		val seen =
			step(
				folders,
				Answer.EntriesRead(
					folder,
					mapOf("new folder" to row("new folder", 9000, bytes = null)),
				),
			).state

		assertNotSame(folders.rows, seen.rows)
		assertEquals("new folder", seen.rows[0].name)
		assertTrue(newestFirst(seen))
	}

	@Test
	fun whatIsChosenAndWhereTheCursorIsAreKeptAsTheFolderChanges() {
		val chosen =
			step(shown(), BrowserEvent.Clicked(RowId("file-007.txt"), Click.Only)).state

		val changed =
			step(
				chosen,
				Answer.EntriesRead(
					folder,
					mapOf("asdf" to row("asdf", 9000), "file-008.txt" to null),
				),
			).state

		assertEquals(setOf(RowId("file-007.txt")), changed.choosing.rows)
		assertEquals(RowId("file-007.txt"), changed.cursor?.at)

		// The one chosen gone, it is chosen no longer.
		val gone =
			step(
				changed,
				Answer.EntriesRead(folder, mapOf("file-007.txt" to null)),
			).state

		assertEquals(emptySet<RowId>(), gone.choosing.rows)
		assertNull(gone.cursor)

		// What is told of a folder since left is too late to matter.
		val left = step(gone, BrowserEvent.Opened(home)).state

		assertSame(left, step(left, Answer.Touched(folder, Touch.Entries(setOf("x")))).state)
		assertSame(
			left,
			step(left, Answer.EntriesRead(folder, mapOf("x" to row("x", 1)))).state,
		)

		// A folder picker's listing holds no files: one made is not among its entries.
		val picking =
			step(
				opened(Choosing.Folder(), folder, home, Fixtures.sidebar).state,
				Answer.Arrived(folder, Listing.Shown(entries)),
			).state

		val made =
			step(
				picking,
				Answer.EntriesRead(
					folder,
					mapOf("asdf" to row("asdf", 9000), "made" to row("made", 9001, bytes = null)),
				),
			).state

		assertEquals(301, (made.listing as Listing.Shown).entries.size)
	}

	@Test
	fun rowsThatCameArePutInTheirPlacesAsPuttingEveryRowInOrderAgainWould() {
		val random = java.util.Random(11)
		val epoch = Instant.parse("2026-01-01T00:00:00Z")

		val orders =
			ColumnId.entries.flatMap { column ->
				listOf(true, false).map { Order.By(Sort(column, it)) }
			} + Order.Relevance

		for (order in orders)
			repeat(20) {
				// Some gone, some changed (gone, and come as they now are), some new; times and sizes that tie.
				val gone = entries.filter { random.nextInt(10) == 0 }

				val changed =
					entries.filter { it !in gone && random.nextInt(10) == 0 }.map {
						it.copy(
							modified = epoch.plusSeconds(random.nextInt(5).toLong()),
							size = it.size?.let { _ -> Shown(random.nextInt(3).toLong(), "") },
							created = Created.Unkept.takeIf { random.nextBoolean() } ?: it.created,
						)
					}

				val made =
					(0 until random.nextInt(8)).map {
						row(
							"made-$it.${listOf("txt", "png", "")[it % 3]}",
							random.nextInt(9000),
							5L.takeIf { _ -> it % 2 == 0 },
						)
					}

				val away = (gone + changed).map { it.id }.toSet()
				val come = (changed + made).shuffled(random)

				assertEquals(
					sorted(entries.filterNot { it.id in away } + come, order).map { it.name },
					merged(sorted(entries, order), away, come, order).map { it.name },
					"$order",
				)
			}
	}

	@Test
	fun theJvmsWatcherSaysWhichEntriesOfAFolderChangedAndNothingOfWhatIsOnlyRead() {
		val watched = root.resolve("told").createDirectories()
		val sub = watched.resolve("sub").createDirectories()
		val told = java.util.concurrent.LinkedBlockingQueue<Touch>()

		/** Everything said of [act], gathered: a write may be told as one event or as several. */
		fun said(act: () -> Unit): Touch {
			act()

			val first =
				told.poll(10, java.util.concurrent.TimeUnit.SECONDS) ?: return Touch.NOTHING

			Thread.sleep(300)

			return generateSequence { told.poll() }.fold(first, Touch::plus)
		}

		/** Nothing said of [act], for long enough that it would have been. */
		fun unsaid(act: () -> Unit): Boolean {
			act()

			return told.poll(700, java.util.concurrent.TimeUnit.MILLISECONDS) == null
		}

		JvmFolderWatcher.watch(watched) { told += it }.use {
			val file = watched.resolve("a.txt")

			// Made, written to, its permissions and its date changed: each is said of it, by its name.
			assertEquals(Touch.Entries(setOf("a.txt")), said { file.writeText("a") })
			assertEquals(Touch.Entries(setOf("a.txt")), said { file.writeText("ab") })
			assertEquals(Touch.Entries(setOf("a.txt")), said { file.toFile().setExecutable(true) })
			// Read, or the folder listed (which is all showing it does): nothing is said, so showing a folder
			// never makes it be read again.
			assertTrue(unsaid { Files.readString(file) })
			assertTrue(unsaid { listingOf(watched) })
			assertTrue(unsaid { entriesOf(watched, setOf("a.txt", "sub")) })
			// What changes inside a folder of it is that folder's to say.
			assertTrue(unsaid { sub.resolve("inner").writeText("x") })
			// Renamed, it is gone by one name and come by another; removed, gone.
			assertEquals(
				Touch.Entries(setOf("a.txt", "b.txt")),
				said { Files.move(file, watched.resolve("b.txt")) },
			)
			assertEquals(
				Touch.Entries(setOf("b.txt")),
				said {
					Files.delete(watched.resolve("b.txt"))
				},
			)
		}
	}

	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aWaitIsSaidOnlyOnceItHasLastedAMomentAndNothingIsDrawnOfAFolderUntilItIsRead() =
		runComposeUiTest {
			val slow = root.resolve("slow").createDirectories()

			slow.resolve("a.txt").writeText("a")

			val real = JvmDisk(trash = FreedesktopTrash(root.resolve("bin")))
			val read = java.util.concurrent.CountDownLatch(1)

			val waiting =
				object : Disk by real {
					override fun read(folder: Path): Listing.Read {
						read.await()

						return real.read(folder)
					}
				}

			val running = arrayOfNulls<Session>(1)

			mainClock.autoAdvance = false
			setContent {
				val scope = rememberCoroutineScope()

				val session =
					remember {
						Session(
							opened(Choosing.Exploring(), slow, root, sidebarOf(XdgPlaces(), root)),
							Services(waiting),
							scope,
						)
					}

				running[0] = session
				BrowserTheme {
					CompositionLocalProvider(LocalMotion provides false) { SessionBrowser(session) }
				}
			}

			// Being read, and for less than the moment a wait is given: nothing says so, and nothing is drawn of it.
			mainClock.advanceTimeBy(Wait.GRACE_MILLIS / 2)
			assertEquals(Wait.Reading, running[0]!!.state.value.wait)
			onNodeWithTag("status").assertTextEquals("")
			onNodeWithTag("waiting").assertDoesNotExist()
			onNodeWithTag("row:a.txt").assertDoesNotExist()

			// Longer than that: the status line says it is, with its spinner; still nothing of the folder.
			mainClock.advanceTimeBy(Wait.GRACE_MILLIS)
			onNodeWithTag("status").assertTextEquals("Loading…")
			onNodeWithTag("waiting").assertExists()
			onNodeWithTag("row:a.txt").assertDoesNotExist()

			// Read: what it holds, and no more of the wait.
			read.countDown()
			mainClock.autoAdvance = true
			waitUntil(timeoutMillis = 15_000) { running[0]!!.state.value.listing is Listing.Read }
			waitForIdle()
			onNodeWithTag("status").assertTextEquals("1 item")
			onNodeWithTag("waiting").assertDoesNotExist()
			onNodeWithTag("row:a.txt").assertExists()
		}

	/** A watcher that says what the test tells it to. */
	private class Told : FolderWatcher {
		val telling = AtomicReference<((Touch) -> Unit)?>(null)

		override fun watch(folder: Path, changed: (Touch) -> Unit): AutoCloseable {
			telling.set(changed)

			return AutoCloseable { telling.set(null) }
		}
	}

	@Test
	fun onlyTheEntriesThatChangedAreReadAgainAndEveryRowIsWholeAsItArrives() =
		runBlocking {
			val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
			val watched = root.resolve("watched").createDirectories()

			repeat(1000) { watched.resolve("file-%04d.txt".format(it)).writeText("$it") }
			watched.resolve(".history").writeText("")

			val real = JvmDisk(trash = FreedesktopTrash(root.resolve("bin")))
			val folders = AtomicInteger()
			val asked = CopyOnWriteArrayList<String>()

			val counting =
				object : Disk by real {
					override fun read(folder: Path): Listing.Read =
						real.read(folder).also { folders.incrementAndGet() }

					override fun entries(folder: Path, names: Set<String>): Map<String, Row?> =
						real.entries(folder, names).also { asked += names }
				}

			val watcher = Told()

			val session =
				Session(
					opened(Choosing.Exploring(), watched, root, sidebarOf(XdgPlaces(), root)),
					Services(counting, watcher = watcher),
					scope,
				)

			// Every state there is, as it is made: none may show a row that is not whole.
			val states = CopyOnWriteArrayList<BrowserState>()
			val watching = scope.launch { session.state.collect { states += it } }

			try {
				withTimeout(30_000) {
					session.state.first { it.listing is Listing.Read }
					session.send(BrowserEvent.ToggledHiddenFiles)

					val first = session.state.first { it.rows.size == 1000 }

					assertEquals(1, folders.get())

					// One file touched, by something that is not prism: it is read, and no other.
					val touched = watched.resolve("file-0500.txt")

					Files.setLastModifiedTime(
						touched,
						FileTime.from(Instant.parse("2031-01-01T00:00:00Z")),
					)
					watcher.telling.get()!!(Touch.Entries(setOf("file-0500.txt")))

					val after =
						session.state.first { state ->
							state.rows[RowId("file-0500.txt")]?.modified ==
								Instant.parse("2031-01-01T00:00:00Z")
						}

					assertEquals(listOf("file-0500.txt"), asked.toList())
					assertEquals(1, folders.get())
					assertEquals(1000, after.rows.size)

					// A hidden file written to again and again: read each time, and the rows drawn are the very rows.
					repeat(3) { round ->
						watched.resolve(".history").writeText("x".repeat(round + 1))
						watcher.telling.get()!!(Touch.Entries(setOf(".history")))
						session.state.first { state ->
							(state.listing as Listing.Shown)
								.entries
								.single { it.name == ".history" }
								.size
								?.value == round + 1L
						}
					}
					assertSame(after.rows, session.state.value.rows)
					assertEquals(1, folders.get())
					assertEquals(
						listOf("file-0500.txt", ".history", ".history", ".history"),
						asked.toList(),
					)

					// Many made at once are one change, each read once; one that came and went meanwhile is not there.
					val made = (0 until 50).map { "made-%02d.txt".format(it) }

					made.forEach { watched.resolve(it).writeText(it) }
					made.forEach { watcher.telling.get()!!(Touch.Entries(setOf(it))) }
					watcher.telling.get()!!(Touch.Entries(setOf("came-and-went")))

					val grown = session.state.first { it.rows.size == 1050 }

					assertEquals((made + "came-and-went").sorted(), asked.drop(4).sorted())
					assertEquals(1, folders.get())
					assertTrue(grown.rows.list.none { it.name == "came-and-went" })
				}
			} finally {
				watching.cancel()
				scope.cancel()
			}

			// Whatever was shown on the way, every row of it said when it was made, or that its file system does not.
			assertTrue(
				states.all { state ->
					state.rows.list.all { it.created is Created.At || it.created == Created.Unkept }
				},
			)
		}

	@Test
	fun aFolderIsReadWithWhenEachOfItsEntriesWasMadeAndAnEntryAloneIsReadAsItsRowIs() {
		val read = root.resolve("read").createDirectories()

		read.resolve("a.txt").writeText("aaa")
		read.resolve("sub").createDirectories()
		Files.createSymbolicLink(read.resolve("to-sub"), read.resolve("sub"))
		Files.createSymbolicLink(read.resolve("to-nothing"), read.resolve("nothing"))

		val listing = listingOf(read) as Listing.Shown
		val rows = listing.entries.associateBy { it.name }

		assertEquals(setOf("a.txt", "sub", "to-sub", "to-nothing"), rows.keys)
		assertEquals(3L, rows.getValue("a.txt").size?.value)
		assertTrue(rows.getValue("sub").isFolder)
		// A link is what it leads to, where it leads somewhere.
		assertTrue(rows.getValue("to-sub").isFolder)
		assertEquals(Category.Link, rows.getValue("to-nothing").category)
		assertTrue(
			listing.entries.all {
				it.created is Created.At ||
					it.created == Created.Unkept
			},
		)
		// Each of them, asked for alone, is the row the folder's reading made of it; one not there is none.
		assertEquals(
			rows + ("gone" to null),
			entriesOf(read, rows.keys + "gone"),
		)
		assertEquals(rows.getValue("a.txt"), rowOf(read.resolve("a.txt")))
		assertNull(rowOf(read.resolve("gone")))
		// A folder that is not there cannot be read, and says why.
		assertEquals(
			Listing.Unreadable("No such file or directory"),
			listingOf(root.resolve("nowhere")),
		)
	}
}
