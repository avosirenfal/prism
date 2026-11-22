package prism.watcher

import java.io.IOException
import java.lang.foreign.Arena
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import prism.linux.Epoll
import prism.linux.Fd
import prism.linux.Inotify
import prism.linux.InotifyEvents
import prism.linux.OpenFlags
import prism.linux.Sys
import prism.linux.Wait
import prism.linux.Waker
import prism.linux.close
import prism.linux.openat
import prism.probe.FsWatch

/*
 * Whether the watcher a daemon reads is still the one pinned. The loader that pinned it can be run again (a system
 * that updates prism restarts it): it takes away what it pinned, which detaches the hooks, and pins a new watcher with
 * a ring of its own. The daemon's ring is kept for as long as it has it open, and is read as ever, but nothing writes
 * to it again. Reading it never fails, so the daemon would wait for changes for good, and its count of what was lost,
 * which is in the old watcher's maps too, never grows. So it looks at the pin of the ring it opened: gone, or a file
 * other than the one it opened, and what it reads is no longer the watcher's.
 */

/**
 * The pin of a ring at [path], as it was when the ring was opened: which file it was ([key]), by its inode and when
 * it was made. A BPF file system gives each file it makes an inode none had before (`get_next_ino`), so a ring pinned
 * anew is never taken for the one before; when it was made tells them apart elsewhere too, where an inode let go is
 * given again.
 */
class Pin private constructor(
	private val path: Path,
	private val key: Any,
) {
	/** Why what was opened at [path] is no longer what is pinned there; null while it is. */
	fun gone(): String? =
		when (val now = keyOf(path)) {
			null -> "the watcher pinned at ${path.parent} was unloaded"
			key -> null
			else -> "the watcher pinned at ${path.parent} was replaced by a new one"
		}

	companion object {
		/** The pin at [path] as it is now; none where nothing is there, or it cannot be looked at. */
		fun at(path: Path): Pin? = keyOf(path)?.let { Pin(path, it) }

		/** The ring of the watcher pinned in [folder]. */
		fun ringIn(folder: Path): Path = folder.resolve(FsWatch.maps.changes.cName)

		/** Which file is at [path] (its device and inode); null where none is, or it cannot be looked at. */
		private fun keyOf(path: Path): Any? =
			try {
				Files.readAttributes(path, BasicFileAttributes::class.java).let {
					listOf(it.fileKey(), it.lastModifiedTime())
				}
			} catch (failed: IOException) {
				null
			}
	}
}

/**
 * What says, as it happens, that the watcher pinned at [pins] may have changed, so that whoever reads it looks again
 * rather than every so often: a thread waiting, with no timeout, on three things.
 *
 * - The loader's file system and the folder of this build's watcher, watched with inotify: a watcher pinned or taken
 *   away is files made and removed there, and given to its readers is their owners changed.
 * - The mounts (`/proc/self/mountinfo`, which says when they change): the loader mounts its file system afresh and
 *   unmounts it, and neither is anything inotify tells of. Each time they change, what is mounted at [WatcherPins.mount]
 *   now is watched.
 * - A waker, which [close] uses to stop it.
 *
 * A loader makes a dozen files one after another, so what it is told is let settle for [SETTLE] milliseconds and told
 * once: [changed] is called on the thread of its own, and should hand on what it does.
 */
class PinWatch private constructor(
	private val pins: WatcherPins,
	private val inotify: Inotify,
	private val mounts: Fd,
	private val waker: Waker,
	private val epoll: Epoll,
	private val arena: Arena,
	private val changed: () -> Unit,
) : AutoCloseable {
	@Volatile
	private var running = true

	private val thread: Thread =
		Thread
			.ofPlatform()
			.name("prism-pins")
			.daemon()
			.start(::watch)

	/** What is at the mount and in this build's folder watched, as they are now: what is not there is not watched. */
	private fun armed() {
		val events =
			InotifyEvents.CREATE or InotifyEvents.DELETE or
				InotifyEvents.MOVED_FROM or InotifyEvents.MOVED_TO or
				InotifyEvents.ATTRIB or InotifyEvents.DELETE_SELF or
				InotifyEvents.MOVE_SELF

		inotify.watch(pins.mount.toString(), events)
		inotify.watch(pins.folder.toString(), events)
	}

	private fun watch() {
		try {
			while (running) {
				val ready = (epoll.await(Wait.Forever) as? Sys.Ok)?.value ?: return

				if (!running || WAKER in ready)
					return
				// What a loader does in a moment is told once, after it.
				Thread.sleep(SETTLE)
				inotify.drained()
				armed()
				changed()
			}
		} catch (stopped: InterruptedException) {
			// Closed.
		}
	}

	override fun close() {
		running = false
		waker.wake()
		thread.join()
		epoll.close()
		inotify.close()
		prism.linux.close(mounts)
		waker.close()
		arena.close()
	}

	companion object {
		/** How long what is told is let settle, in milliseconds: a loader pins its watcher in well under it. */
		const val SETTLE = 250L

		private const val INOTIFY = 1L
		private const val MOUNTS = 2L
		private const val WAKER = 3L

		/**
		 * [pins] watched, [changed] called each time what is pinned there, or the mounts, may have changed; or why it
		 * cannot be (no inotify, no mounts to look at).
		 */
		fun start(pins: WatcherPins, changed: () -> Unit): Sys<PinWatch> {
			val arena = Arena.ofShared()
			val opened = mutableListOf<AutoCloseable>(arena)

			fun <T> failed(why: Sys.Failed): Sys<T> {
				opened.asReversed().forEach { it.close() }

				return why
			}

			val inotify =
				when (val made = Inotify.create(arena)) {
					is Sys.Failed -> return failed(made)
					is Sys.Ok -> made.value.also(opened::add)
				}

			val mounts =
				when (
					val made =
						openat(
							Fd.CWD,
							"/proc/self/mountinfo",
							OpenFlags.READ_ONLY or OpenFlags.CLOSE_ON_EXEC,
						)
				) {
					is Sys.Failed -> return failed(made)
					is Sys.Ok -> made.value.also { fd -> opened.add { prism.linux.close(fd) } }
				}

			val waker =
				when (val made = Waker.create()) {
					is Sys.Failed -> return failed(made)
					is Sys.Ok -> made.value.also(opened::add)
				}

			val epoll =
				when (val made = Epoll.create(arena)) {
					is Sys.Failed -> return failed(made)
					is Sys.Ok -> made.value.also(opened::add)
				}

			for (
				watched in listOf(
					epoll.watchInput(inotify.fd, INOTIFY),
					epoll.watchChange(mounts, MOUNTS),
					epoll.watchInput(waker.fd, WAKER),
				)
				)
				if (watched is Sys.Failed)
					return failed(watched)

			return Sys.Ok(
				PinWatch(pins, inotify, mounts, waker, epoll, arena, changed).also { it.armed() },
			)
		}
	}
}
