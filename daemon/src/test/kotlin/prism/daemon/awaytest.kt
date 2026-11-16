package prism.daemon

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.index.FileId
import prism.index.IgnoreRules
import prism.index.Index
import prism.index.Indexer
import prism.index.Name
import prism.index.Volume
import prism.index.VolumeId
import prism.index.Where
import prism.linux.Fd
import prism.linux.orThrow
import prism.linux.statx
import prism.platform.Dev
import prism.platform.KernelDev
import prism.platform.Mount

/**
 * File systems that come and go under a live index: one unmounted has its tree kept, whatever takes its entries out
 * of the index; mounted again, in the same place or another, it is put back as it was and read for what changed;
 * one newly mounted is read at once, unless it comes and goes with something outside the machine; and one long gone
 * is let go.
 *
 * Nothing is really mounted here: a file system is a folder with files in it and a mount that says it is one, and
 * unmounting it is emptying the folder and saying nothing is mounted there, which is all the index ever sees of it.
 */
@OptIn(ExperimentalPathApi::class)
class AwayTest {
	@TempDir
	lateinit var root: Path

	@TempDir
	lateinit var state: Path

	private val file: Path get() = state.resolve("index.prism")

	/** The time, which a test moves. */
	private var now = Instant.parse("2026-11-01T10:00:00Z")

	private val clock =
		object : Clock() {
			override fun getZone() = ZoneOffset.UTC

			override fun withZone(zone: java.time.ZoneId?) = this

			override fun instant() = now
		}

	/** What is mounted, which a test changes. */
	private var mounts: List<Mount> = emptyList()

	/** A file system of a made-up device, mounted at [at]: [source] is which it is. */
	private fun mount(source: String, at: String) =
		Mount(
			source.hashCode().toLong(),
			KernelDev(Dev(0, 50 + mounts.size)),
			"/",
			root.resolve(at),
			"ext4",
			source,
		)

	private val portable: (Mount) -> Boolean = { it.source.startsWith("usb") }

	private val settings =
		LiveSettings(
			mounts = { mounts },
			volumes = { Volume(VolumeId(it.source), portable(it)) },
			clock = { now },
		)

	private fun opened(): LiveIndex =
		LiveIndex
			.open(
				file,
				listOf(root),
				// No rules: the test's folders are under /tmp, which the usual rules leave out.
				Indexer(
					IgnoreRules(emptySet(), emptySet()),
					mounts,
					clock = clock,
					portable = portable,
				),
				settings,
			).also {
				it.opened.done()
				it.remounted().done()
			}

	private fun made(vararg files: String) =
		files.forEach {
			root
				.resolve(it)
				.also { path -> path.parent.createDirectories() }
				.writeText(it)
		}

	/** The folder at [at] emptied, as a mount point is once what was mounted there is not. */
	private fun emptied(at: String) =
		root.resolve(at).listDirectoryEntries().forEach { it.deleteRecursively() }

	private fun where(path: String) = Where.of(root.resolve(path))

	/** Every entry held but the root, as paths under it. */
	private fun Index.names(): Set<String> =
		entries()
			.map {
				it.where.toString()
			}.filter { it != "$root" }
			.map { it.removePrefix("$root/") }
			.toSet()

	private fun <T> java.util.concurrent.Future<T>.done(): T = get(30, TimeUnit.SECONDS)

	@Test
	fun aFileSystemUnmountedIsKeptAndPutBackAsItWasWhenItIsMountedAgain() {
		made("home/x.txt", "mnt/data/a.txt", "mnt/data/sub/b.txt")
		mounts = listOf(mount("/dev/data", "mnt/data"))

		opened().use { live ->
			// One of its files turns up later, so the index knows when it first saw it.
			now = now.plusSeconds(60)
			made("mnt/data/later.txt")
			live.rescan(listOf(root.resolve("mnt/data/later.txt"))).done()

			val first = live.index.seenAt(where("mnt/data/later.txt"))

			assertEquals(now, first)

			// Unmounted: its entries leave the index, and are kept.
			emptied("mnt/data")
			mounts = emptyList()
			live.remounted().done()
			assertEquals(setOf("home", "home/x.txt", "mnt", "mnt/data"), live.index.names())
			assertEquals(
				listOf(VolumeId("/dev/data") to 5),
				live.kept.map { it.volume to it.entries },
			)
			assertEquals(where("mnt/data"), live.kept.single().at)

			// Mounted again, with one file gone and one new: back at once, then as the disk is; what was there
			// before is as old as it was, and what is new is new.
			now = now.plusSeconds(3600)
			made("mnt/data/a.txt", "mnt/data/later.txt", "mnt/data/new.txt")
			mounts = listOf(mount("/dev/data", "mnt/data"))
			live.remounted().done()
			assertEquals(
				setOf(
					"home",
					"home/x.txt",
					"mnt",
					"mnt/data",
					"mnt/data/a.txt",
					"mnt/data/later.txt",
					"mnt/data/new.txt",
				),
				live.index.names(),
			)
			assertEquals(first, live.index.seenAt(where("mnt/data/later.txt")))
			assertEquals(now, live.index.seenAt(where("mnt/data/new.txt")))
			assertEquals(emptyList<Any>(), live.kept)
		}
	}

	@Test
	fun itIsKeptWhateverTakesItsEntriesOutAndNotOnlyWhenTheMountsAreLookedAt() {
		made("mnt/data/a.txt", "mnt/other/keep.txt")
		mounts = listOf(mount("/dev/data", "mnt/data"))

		opened().use { live ->
			// Unmounted, and the roots read again before anything looks at the mounts: the scan finds the folder
			// empty, which would have been the end of what the index knew of it.
			emptied("mnt/data")
			mounts = emptyList()
			live.rescanRoots().done()
			assertFalse("mnt/data/a.txt" in live.index.names())
			assertEquals(listOf(VolumeId("/dev/data")), live.kept.map { it.volume })
		}
	}

	@Test
	fun itIsKeptWhereItsMountPointGoesWithItAndTheWatcherSaysSoFirst() {
		made("mnt/data/a.txt", "mnt/data/sub/b.txt")
		mounts = listOf(mount("/dev/data", "mnt/data"))

		opened().use { live ->
			val above = statx(Fd.CWD, root.resolve("mnt").toString()).orThrow()

			// A desktop takes the folder a drive was mounted at away as it unmounts it, and the watcher sees that
			// at once, long before anything looks at the mounts.
			root.resolve("mnt/data").deleteRecursively()
			mounts = emptyList()
			live.changed(listOf(Changed(FileId(KernelDev(above.dev), above.ino), Name.of("data"))))
			assertEquals(setOf("mnt"), live.index.names())
			assertEquals(
				listOf(VolumeId("/dev/data") to 4),
				live.kept.map {
					it.volume to
						it.entries
				},
			)
		}
	}

	@Test
	fun mountedSomewhereElseItIsPutBackThere() {
		made("mnt/data/a.txt", "mnt/data/sub/b.txt", "mnt/elsewhere/placeholder")
		mounts = listOf(mount("/dev/data", "mnt/data"))

		opened().use { live ->
			now = now.plusSeconds(60)
			made("mnt/data/later.txt")
			live.rescan(listOf(root.resolve("mnt/data/later.txt"))).done()

			val first = live.index.seenAt(where("mnt/data/later.txt"))

			emptied("mnt/data")
			mounts = emptyList()
			live.remounted().done()

			// The same file system, at another place.
			now = now.plusSeconds(3600)
			emptied("mnt/elsewhere")
			made("mnt/elsewhere/a.txt", "mnt/elsewhere/sub/b.txt", "mnt/elsewhere/later.txt")
			mounts = listOf(mount("/dev/data", "mnt/elsewhere"))
			live.remounted().done()
			assertTrue(
				live.index.names().containsAll(
					setOf("mnt/elsewhere/a.txt", "mnt/elsewhere/sub/b.txt", "mnt/elsewhere/later.txt"),
				),
			)
			assertFalse("mnt/data/a.txt" in live.index.names())
			assertEquals(first, live.index.seenAt(where("mnt/elsewhere/later.txt")))
			assertEquals(emptyList<Any>(), live.kept)
		}
	}

	@Test
	fun whatIsKeptOutlivesTheDaemonAndAFileSystemGoneWhileItWasNotRunningIsKeptAsItStarts() {
		made("mnt/data/a.txt", "mnt/data/sub/b.txt")
		mounts = listOf(mount("/dev/data", "mnt/data"))
		opened().close()

		// Unmounted while nothing was running: the index's file still has it, and what was noted says whose it is.
		emptied("mnt/data")
		mounts = emptyList()
		opened().use { live ->
			assertEquals(setOf("mnt", "mnt/data"), live.index.names())
			assertEquals(listOf(VolumeId("/dev/data")), live.kept.map { it.volume })
		}

		// And still kept the next time, until it is mounted.
		opened().use { live ->
			assertEquals(listOf(VolumeId("/dev/data")), live.kept.map { it.volume })
			made("mnt/data/a.txt")
			mounts = listOf(mount("/dev/data", "mnt/data"))
			live.remounted().done()
			assertEquals(setOf("mnt", "mnt/data", "mnt/data/a.txt"), live.index.names())
			assertEquals(emptyList<Any>(), live.kept)
		}
	}

	@Test
	fun oneNewlyMountedIsReadAtOnceUnlessItComesAndGoesWithSomethingOutsideTheMachine() {
		made("mnt/keep.txt")
		opened().use { live ->
			// Mounted, with nothing to say what is in them: the index is told only that they are mounted.
			made("mnt/disk/a.txt", "mnt/stick/b.txt")
			mounts = listOf(mount("/dev/disk", "mnt/disk"), mount("usb-stick", "mnt/stick"))
			live.remounted().done()
			assertTrue("mnt/disk/a.txt" in live.index.names())
			assertFalse("mnt/stick/b.txt" in live.index.names())
			assertFalse("mnt/stick" in live.index.names())

			// Asked for outright, the stick is read, and kept when it goes, as any other is.
			live.index(listOf(root.resolve("mnt/stick"))).done()
			live.remounted().done()
			assertTrue("mnt/stick/b.txt" in live.index.names())
			emptied("mnt/stick")
			mounts = listOf(mount("/dev/disk", "mnt/disk"))
			live.remounted().done()
			assertFalse("mnt/stick/b.txt" in live.index.names())
			assertEquals(listOf(VolumeId("usb-stick")), live.kept.map { it.volume })
		}
	}

	@Test
	fun aMountWhoseFileSystemCannotBeAskedForAMomentIsStillTheOneItWas() {
		made("mnt/data/a.txt")
		mounts = listOf(mount("/dev/data", "mnt/data"))

		var answering = true
		val flaky = settings.copy(volumes = { settings.volumes(it).takeIf { answering } })

		LiveIndex
			.open(
				file,
				listOf(root),
				Indexer(
					IgnoreRules(emptySet(), emptySet()),
					mounts,
					clock = clock,
					portable = portable,
				),
				flaky,
			).use { live ->
				live.opened.done()
				live.remounted().done()

				// Nothing says which it is, this time: it is mounted as it was, so it is what it was.
				answering = false
				live.remounted().done()
				live.rescanRoots().done()
				assertTrue("mnt/data/a.txt" in live.index.names())
				assertEquals(emptyList<Any>(), live.kept)
			}
	}

	@Test
	fun oneNotSeenForLongIsLetGo() {
		made("mnt/data/a.txt")
		mounts = listOf(mount("/dev/data", "mnt/data"))

		opened().use { live ->
			emptied("mnt/data")
			mounts = emptyList()
			live.remounted().done()

			now = now.plus(29.days.toJavaDuration())
			assertEquals(emptyList<Any>(), live.expire())
			assertEquals(1, live.kept.size)

			now = now.plus(2.days.toJavaDuration())
			assertEquals(listOf(VolumeId("/dev/data")), live.expire().map { it.volume })
			assertEquals(emptyList<Any>(), live.kept)

			// Mounted after that, it is read as one never seen.
			made("mnt/data/a.txt")
			mounts = listOf(mount("/dev/data", "mnt/data"))
			live.remounted().done()
			assertTrue("mnt/data/a.txt" in live.index.names())
			assertNull(live.kept.firstOrNull())
		}
	}
}
