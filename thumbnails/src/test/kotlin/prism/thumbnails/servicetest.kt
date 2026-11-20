package prism.thumbnails

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The service (service.kt), around a real cache in a folder of the test's: where a file's picture is to be had, what
 * is made and what is remembered of what could not be, the order things are made in and what stops their being made,
 * the thumbnails other programs add, and the cache kept within its budget and taken to another folder.
 */
class ThumbnailServiceTest {
	@TempDir
	lateinit var work: Path

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	@AfterEach
	fun stop() {
		scope.cancel()
		assertEquals(emptyList<String>(), problems.toList())
	}

	/**
	 * A program that makes thumbnails, as the test has it behave: run as `fake FILE`, it is noted to have [started],
	 * and ends when the test lets it ([held]: held for none, it ends at once), with a picture unless the file's name
	 * says it is broken.
	 */
	private class Fake : Processes {
		val started = CopyOnWriteArrayList<String>()
		val stopped = CopyOnWriteArrayList<String>()
		val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

		@Volatile
		var held = false

		fun release(name: String) =
			gates.getOrPut(name) { CompletableDeferred() }.complete(Unit)

		override suspend fun run(words: List<String>, limit: Duration, most: Int): Ran {
			val name = Path.of(words.last()).fileName.toString()

			started += name
			if (held)
				try {
					gates.getOrPut(name) { CompletableDeferred() }.await()
				} catch (cancelled: CancellationException) {
					stopped += name
					throw cancelled
				}

			return if (name.startsWith("broken"))
				Ran.Ended(1, ByteArray(0), "cannot read $name")
			else
				Ran.Ended(0, ImagePictures.sample(200, 100), "")
		}
	}

	private val fake = Fake()

	private val film =
		Json.parseToJsonElement(
			"""{"id": "fake", "command": ["fake", "{input}"], "extensions": ["vid", "png"]}""",
		)

	private val cacheRoot: Path get() = work.resolve("cache/thumbnails")

	private fun settings(budget: Budget = Budget(), root: Path = cacheRoot) =
		ThumbnailSettings(
			folder = root.toString(),
			budget = budget,
			thumbnailers = listOf(film),
		)

	/** What watches a cache's folders in a test: nothing, until the test says a name changed. */
	private class Told : Watching {
		val watched = ConcurrentHashMap<Path, (Set<String>?) -> Unit>()

		override fun watch(folder: Path, changed: (Set<String>?) -> Unit): AutoCloseable {
			watched[folder] = changed

			return AutoCloseable { watched.remove(folder, changed) }
		}
	}

	private val told = Told()

	private fun service(
		settings: ThumbnailSettings = settings(),
		parallel: Int = 2,
		now: () -> Instant = Instant::now,
	) = ThumbnailService(
		scope,
		settings,
		pictures = ImagePictures,
		processes = fake,
		// With a value, and not a parent: one given null alone is never completed.
		ffmpeg = CompletableDeferred<Ffmpeg?>(value = null),
		usage = work.resolve("state/thumbnails.used"),
		watching = told,
		now = now,
		parallel = parallel,
		report = { doing, failure -> problems += "$doing: ${failure.stackTraceToString()}" },
	)

	/** What a service said went wrong that should not have: nothing, in any test. */
	private val problems = CopyOnWriteArrayList<String>()

	private fun file(name: String, bytes: ByteArray = "film".toByteArray()): Subject =
		Subject.of(
			work.resolve("files").createDirectories().resolve(name).also {
				it.writeBytes(bytes)
			},
		)!!

	private suspend fun eventually(what: String, check: () -> Boolean) =
		try {
			withTimeout(10.seconds) {
				while (!check())
					delay(10)
			}
		} catch (late: kotlinx.coroutines.TimeoutCancellationException) {
			throw AssertionError("never: $what")
		}

	@Test
	fun aSmallPictureIsItsOwnAndALargeOneHasAThumbnailMadeOnce() =
		runBlocking {
			val service = service()

			val icon =
				Subject.of(
					ImagePictures.sampleFile(work.resolve("files"), "icon.png", 64, 64),
				)!!

			val photo =
				Subject.of(
					ImagePictures.sampleFile(work.resolve("files"), "photo.jpg", 3000, 2000),
				)!!

			assertEquals(Found.Itself, service.find(icon, 256))

			val found = service.find(photo, 200) as Found.Kept

			// Kept as the standard has it: at the size at least as large as it is drawn, named by the file's address.
			assertEquals(
				ThumbnailCache(cacheRoot).entry(photo.file, ThumbnailSize.Large),
				found.thumbnail,
			)
			assertEquals(Extent(256, 170), Png.extent(Files.readAllBytes(found.thumbnail)))
			assertEquals(photo.uri, PngText.read(found.thumbnail)!!["Thumb::URI"])
			// Decoded here, not by a program; and asked again, it is found, not made.
			assertEquals(emptyList<String>(), fake.started)
			Files.setLastModifiedTime(found.thumbnail, FileTime.fromMillis(1000))
			assertEquals(found, service.find(photo, 200))
			assertEquals(1000, Files.getLastModifiedTime(found.thumbnail).toMillis())
			// Drawn larger, it is made at the larger size, and the smaller one is still there for a smaller tile.
			assertEquals(
				ThumbnailCache(cacheRoot).entry(photo.file, ThumbnailSize.XLarge),
				(service.find(photo, 400) as Found.Kept).thumbnail,
			)
			assertEquals(found, service.find(photo, 100))
			// Drawn larger than any thumbnail is kept, it is the picture itself that is decoded.
			assertEquals(Found.Itself, service.find(photo, 2048))
			eventually("both counted") { service.totals.files == 2 }
		}

	@Test
	fun aPictureTheDecoderCannotReadGoesOnToAProgram() =
		runBlocking {
			val service = service()
			// Named as a PNG, and nothing the decoder makes anything of.
			val odd = file("odd.png", ByteArray(5000) { 7 })

			assertTrue(service.find(odd, 256) is Found.Kept)
			assertEquals(listOf("odd.png"), fake.started)
		}

	@Test
	fun thatAFileHasNoThumbnailIsRememberedUntilItChanges() =
		runBlocking {
			val service = service()
			val broken = file("broken film.vid")

			assertEquals(Found.None, service.find(broken, 256))
			assertEquals(listOf("broken film.vid"), fake.started)
			assertTrue(ThumbnailCache(cacheRoot).failed(broken))
			// Asked again, at any size, nothing is run.
			assertEquals(Found.None, service.find(broken, 256))
			assertEquals(Found.None, service.find(broken, 600))
			assertEquals(1, fake.started.size)
			// Another service, as prism started again, knows it too.
			assertEquals(Found.None, service().find(broken, 256))
			assertEquals(1, fake.started.size)

			// The file changed: tried again.
			Files.setLastModifiedTime(broken.file, FileTime.from(broken.modified.plusSeconds(60)))
			assertEquals(Found.None, service.find(Subject.of(broken.file)!!, 256))
			assertEquals(2, fake.started.size)
		}

	@Test
	fun aFileNothingMakesAPictureOfHasNoneAndNothingIsRemembered() =
		runBlocking {
			val notes = file("notes.txt")
			val nothing = service(settings().copy(off = setOf("fake")))

			assertEquals(Found.None, nothing.find(notes, 256))
			assertEquals(Found.None, nothing.find(file("film.vid"), 256))
			assertEquals(emptyList<String>(), fake.started)
			assertEquals(0, ThumbnailCache(cacheRoot).entries().count())

			// The program turned on again, the film has its picture: nothing said it could not have one.
			nothing.configure(settings())
			assertTrue(nothing.find(file("film.vid"), 256) is Found.Kept)
			assertEquals(Found.None, nothing.find(notes, 256))
		}

	@Test
	fun whereItsOwnSizeCannotBeMadeASmallerOneAnotherProgramMadeIsShown() =
		runBlocking {
			val service = service()
			val broken = file("broken.vid")

			val theirs =
				ThumbnailCache(
					cacheRoot,
				).write(broken, ThumbnailSize.Normal, ImagePictures.sample(128, 64))!!

			assertEquals(Found.Kept(theirs), service.find(broken, 256))
			// And remembered not to be tried, it is still what is shown.
			assertEquals(Found.Kept(theirs), service.find(broken, 256))
			assertEquals(1, fake.started.size)
		}

	@Test
	fun whatIsShownIsMadeBeforeWhatIsOnlyNear() =
		runBlocking {
			val service = service(parallel = 2)
			val near = (1..6).map { file("near$it.vid") }
			val owner = Any()

			fake.held = true
			service.near(owner, near.map { Wanted(it, 256) })
			// What is near is made one at a time, where two are made at once: a place is left for what is shown.
			eventually("the first near one begun") { fake.started.size == 1 }
			delay(200)
			assertEquals(listOf("near1.vid"), fake.started)

			val shown = async { service.find(file("shown.vid"), 256) }

			eventually("the shown one begun") { "shown.vid" in fake.started }
			// While something shown waits, nothing more that is near is begun, though one of them ends.
			fake.release("near1.vid")
			delay(200)
			assertEquals(listOf("near1.vid", "shown.vid"), fake.started)

			fake.release("shown.vid")
			assertTrue(shown.await() is Found.Kept)
			// Then the rest, in the order they were said, the nearest first.
			fake.held = false
			(2..6).forEach { fake.release("near$it.vid") }
			eventually("the rest made") { fake.started.size == 7 }
			assertEquals((2..6).map { "near$it.vid" }, fake.started.drop(2))
			eventually("all kept") {
				near.all {
					ThumbnailCache(cacheRoot).find(it, ThumbnailSize.entries) !=
						null
				}
			}
		}

	@Test
	fun whatIsNoLongerWantedIsNotBegunAndWhatHasBegunIsStopped() =
		runBlocking {
			val service = service(parallel = 2)

			fake.held = true

			// Two tiles shown take the two places; a third waits its turn.
			val first = async { service.find(file("a.vid"), 256) }
			val second = async { service.find(file("b.vid"), 256) }

			eventually("two begun") { fake.started.size == 2 }

			val third = async { service.find(file("c.vid"), 256) }

			delay(200)
			assertEquals(setOf("a.vid", "b.vid"), fake.started.toSet())
			// Scrolled away before its turn: it is never begun.
			third.cancelAndJoin()
			// Scrolled away while it is being made: the program is stopped.
			first.cancelAndJoin()
			eventually("the program stopped") { fake.stopped.toList() == listOf("a.vid") }
			fake.release("b.vid")
			assertTrue(second.await() is Found.Kept)
			delay(200)
			assertFalse("c.vid" in fake.started)
			// Stopped is not failed: nothing is remembered of either.
			assertEquals(1, ThumbnailCache(cacheRoot).entries().count())
		}

	@Test
	fun whatTwoWaitForIsMadeOnceAndStoppedOnlyWhenNeitherWaits() =
		runBlocking {
			val service = service()
			val shared = file("shared.vid")

			fake.held = true

			val one = async { service.find(shared, 256) }
			val other = async { service.find(shared, 250) }

			eventually("begun") { fake.started.isNotEmpty() }
			delay(100)
			one.cancelAndJoin()
			delay(100)
			assertEquals(emptyList<String>(), fake.stopped)
			fake.release("shared.vid")
			assertTrue(other.await() is Found.Kept)
			assertEquals(listOf("shared.vid"), fake.started)
		}

	@Test
	fun aWindowThatGoesElsewhereOrIsClosedStopsWhatWasNearIt() =
		runBlocking {
			val service = service(parallel = 3)
			val folder = (1..8).map { file("f$it.vid") }
			val owner = Any()

			fake.held = true
			service.near(owner, folder.map { Wanted(it, 256) })
			eventually("two begun") { fake.started.size == 2 }

			// Scrolled on: the second is still near, the first no longer; the first is stopped, the second goes on.
			service.near(owner, folder.drop(1).map { Wanted(it, 256) })
			eventually("the first stopped") { fake.stopped.toList() == listOf("f1.vid") }
			eventually("the third begun in its place") { fake.started.size >= 3 }
			// The first two were begun together, in either order; the third, once one of their places was free.
			assertEquals(setOf("f1.vid", "f2.vid"), fake.started.take(2).toSet())
			assertEquals("f3.vid", fake.started[2])

			// Gone to another folder: everything begun is stopped, and nothing more is.
			service.forget(owner)
			eventually(
				"all stopped",
			) { fake.stopped.toSet() == setOf("f1.vid", "f2.vid", "f3.vid") }
			fake.held = false
			delay(300)
			assertEquals(3, fake.started.size)
			assertEquals(0, ThumbnailCache(cacheRoot).entries().count())
		}

	/** [count] thumbnails another program left in [root], the [count]th written last, a day apart. */
	private fun others(
		root: Path,
		count: Int,
		at: Instant = Instant.parse("2026-01-01T00:00:00Z"),
	): List<Path> {
		val cache = ThumbnailCache(root)
		val png = ImagePictures.sample(32, 32)

		return (1..count).map { number ->
			cache
				.write(
					Subject(work.resolve("their/$number.jpg"), Instant.ofEpochSecond(5)),
					ThumbnailSize.Large,
					png,
				)!!
				.also {
					Files.setLastModifiedTime(
						it,
						FileTime.from(at.plusSeconds(number * 86_400L)),
					)
				}
		}
	}

	@Test
	fun thumbnailsOtherProgramsMadeArePrismsToKeepAndToRemove() =
		runBlocking {
			val theirs = others(cacheRoot, 700)
			val service = service(settings(Budget(files = 500)))

			// Counted as it starts, and over its budget: the oldest go, whoever made them.
			eventually("brought within the budget") { theirs.count { Files.exists(it) } == 450 }
			assertTrue(theirs.take(250).none { Files.exists(it) })
			assertTrue(theirs.drop(250).all { Files.exists(it) })
			eventually("counted") { service.totals.files == 450 }

			// Another program writes one while prism runs: the folder's watcher says so, and it is counted.
			val fresh =
				ThumbnailCache(cacheRoot).write(
					Subject(work.resolve("their/new.jpg"), Instant.ofEpochSecond(5)),
					ThumbnailSize.Large,
					ImagePictures.sample(32, 32),
				)!!

			told.watched.getValue(cacheRoot.resolve("large"))(setOf(fresh.fileName.toString()))
			eventually("the new one counted") { service.totals.files == 451 }
			// And removes one: no longer counted.
			Files.delete(theirs.last())
			told.watched.getValue(
				cacheRoot.resolve("large"),
			)(setOf(theirs.last().fileName.toString()))
			eventually("the removed one forgotten") { service.totals.files == 450 }
			// A folder that says only that it changed is counted anew.
			Files.delete(fresh)
			told.watched.getValue(cacheRoot.resolve("large"))(null)
			delay(200)
			assertEquals(450, service.totals.files)

			// What is known of them is kept for the next run.
			service.close()
			assertEquals(
				450,
				Catalogue(work.resolve("state/thumbnails.used"))
					.also {
						it.read()
					}.totals.files,
			)
		}

	@Test
	fun thumbnailsPrismMakesCountTowardsTheBudgetAndWhatIsShownOftenOutlastsWhatIsNot() =
		runBlocking {
			var clock = Instant.parse("2026-06-01T00:00:00Z")
			val theirs = others(cacheRoot, 480, at = Instant.parse("2025-01-01T00:00:00Z"))
			val service = service(settings(Budget(files = 500)), now = { clock })
			val favourite = file("favourite.vid")

			eventually("counted") { service.totals.files == 480 }
			// Shown on three days running: established.
			repeat(3) {
				assertTrue(service.find(favourite, 256) is Found.Kept)
				clock = clock.plusSeconds(86_400)
			}
			// A folder of forty films scrolled through: over the budget, and the oldest of what was never shown go.
			(1..40).forEach { service.find(file("film$it.vid"), 256) }
			eventually("trimmed") { !Files.exists(theirs.first()) }
			assertTrue(service.totals.files <= 500)

			val cache = ThumbnailCache(cacheRoot)

			assertTrue(Files.exists(cache.entry(favourite.file, ThumbnailSize.Large)))
			assertTrue(
				(1..40).all {
					Files.exists(cache.entry(work.resolve("files/film$it.vid"), ThumbnailSize.Large))
				},
			)
			assertFalse(Files.exists(theirs.first()))
		}

	@Test
	fun setToAnotherFolderThumbnailsAreKeptThereAndOneMovedThereIsKnownAsItWas() =
		runBlocking {
			var clock = Instant.parse("2026-06-01T00:00:00Z")
			val service = service(now = { clock })
			val film = file("film.vid")
			val elsewhere = work.resolve("elsewhere/cache")

			// Shown on two days: established, in the first folder.
			val first = service.find(film, 256) as Found.Kept

			clock = clock.plusSeconds(86_400)
			service.find(film, 256)
			assertEquals(1, service.totals.files)

			service.configure(settings(root = elsewhere))
			assertEquals(ThumbnailCache(elsewhere), service.cache)
			eventually("the new folder counted") { service.totals.files == 0 }

			// Left where it was, the old folder's thumbnail is not the new folder's; moved, it is, used as it was.
			val made = fake.started.size

			Relocation.move(ThumbnailCache(cacheRoot), ThumbnailCache(elsewhere))
			told.watched.getValue(elsewhere)(null)
			eventually("the folder it came to watched") {
				told.watched.containsKey(elsewhere.resolve("large"))
			}
			told.watched.getValue(
				elsewhere.resolve("large"),
			)(setOf(first.thumbnail.fileName.toString()))
			eventually("the moved one counted") { service.totals.files == 1 }
			assertEquals(
				Found.Kept(ThumbnailCache(elsewhere).entry(film.file, ThumbnailSize.Large)),
				service.find(film, 256),
			)
			assertEquals(made, fake.started.size)
			service.close()

			val known = Catalogue(work.resolve("state/thumbnails.used")).also { it.read() }.all()

			assertTrue(
				known.values
					.single()
					.used.established,
			)
		}

	@Test
	fun setToOneSizeEveryThumbnailIsMadeAtIt() =
		runBlocking {
			val service = service(settings().copy(size = ThumbnailSize.XLarge))
			val film = file("film.vid")

			assertEquals(
				ThumbnailCache(cacheRoot).entry(film.file, ThumbnailSize.XLarge),
				(service.find(film, 100) as Found.Kept).thumbnail,
			)
			assertEquals(1, fake.started.size)
			service.find(film, 900)
			assertEquals(1, fake.started.size)
			assertEquals(Resolution.Fixed(ThumbnailSize.XLarge), service.settings.resolution)
			assertEquals(ThumbnailSize.Large, Resolution.Automatic.sizeFor(200))
			assertEquals(ThumbnailSize.XxLarge, Resolution.Automatic.sizeFor(5000))
		}

	@Test
	fun whatTheSettingsSayIsReadLeniently() {
		// A folder that is no path from the root is the platform's own; so is none.
		assertEquals(
			Path.of("/d"),
			ThumbnailSettings(folder = "relative/path").root {
				Path.of("/d")
			},
		)
		assertEquals(Path.of("/d"), ThumbnailSettings(folder = "  ").root { Path.of("/d") })
		assertEquals(
			Path.of("/x/y"),
			ThumbnailSettings(folder = "/x/./z/../y").root {
				Path.of("/d")
			},
		)
		// A budget is never less than a cache needs to be one; nor is what is made ahead less than nothing.
		assertEquals(
			Budget(Budget.LEAST_BYTES, Budget.LEAST_FILES),
			ThumbnailSettings(budget = Budget(0, -4)).kept,
		)
		assertEquals(0, ThumbnailSettings(nearby = -3).ahead)
		assertEquals(150, ThumbnailSettings().ahead)

		// Written and read back, they are themselves.
		val set =
			settings().copy(
				size = ThumbnailSize.XLarge,
				off = setOf("ffmpeg"),
				nearby = 40,
			)

		val json = Json { encodeDefaults = true }

		assertEquals(
			set,
			json.decodeFromString(
				ThumbnailSettings.serializer(),
				json.encodeToString(ThumbnailSettings.serializer(), set),
			),
		)
		assertEquals(
			ThumbnailSettings(),
			json.decodeFromString(ThumbnailSettings.serializer(), "{}"),
		)
	}
}
