package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.file.Path
import prism.ffi.Library

/*
 * FSEvents: the kernel's record of what changed in the file system, as a stream of paths, each with what happened to
 * it. A stream watches folders and everything under them, and with `kFSEventStreamCreateFlagFileEvents` says each
 * entry that was made, removed or renamed, rather than only its folder. Its events are given on a dispatch queue of
 * its own (libdispatch's), so no run loop is needed; the callback is an FFM upcall, which is given what the stream
 * gives, copied into Kotlin's values before it returns.
 *
 * Where the stream could not keep up (`MustScanSubDirs`, or events dropped by the kernel or the process), it says
 * which folder to read again, or that events were lost: what was lost cannot be known otherwise.
 */

/** What happened to an entry, as FSEvents' flags say. */
enum class FsFlag(
	val bit: Int,
) {
	MustScanSubDirs(0x1),
	UserDropped(0x2),
	KernelDropped(0x4),
	EventIdsWrapped(0x8),
	HistoryDone(0x10),
	RootChanged(0x20),
	Mount(0x40),
	Unmount(0x80),
	Created(0x100),
	Removed(0x200),
	Renamed(0x800),
	Modified(0x1000),
	IsFile(0x10000),
	IsDir(0x20000),
	IsSymlink(0x40000),
	;

	companion object {
		fun of(flags: Int): Set<FsFlag> = entries.filter { flags and it.bit != 0 }.toSet()
	}
}

/** One event of a stream: the [path] it is of, what happened ([flags]), and its id, which orders it among all others. */
data class FsEvent(
	val path: Path,
	val flags: Set<FsFlag>,
	val id: Long,
)

/** `FSEventStreamCallback`: the stream, its context, how many events, and their paths, flags and ids. */
internal fun interface StreamCallback {
	fun called(
		stream: MemorySegment,
		info: MemorySegment,
		count: Long,
		paths: MemorySegment,
		flags: MemorySegment,
		ids: MemorySegment,
	)
}

/** `dispatch_function_t`: work given a context. */
internal fun interface DispatchWork {
	fun run(context: MemorySegment)
}

internal fun interface FsEventStreamCreate {
	operator fun invoke(
		allocator: MemorySegment,
		callback: MemorySegment,
		context: MemorySegment,
		paths: MemorySegment,
		since: Long,
		latency: Double,
		flags: Int,
	): MemorySegment
}

internal fun interface FsEventStreamSetDispatchQueue {
	operator fun invoke(stream: MemorySegment, queue: MemorySegment)
}

internal fun interface FsEventStreamStart {
	operator fun invoke(stream: MemorySegment): Boolean
}

/** `FSEventStreamStop`, `FSEventStreamInvalidate`, `FSEventStreamRelease`: each of a stream, returning nothing. */
internal fun interface FsEventStreamEnd {
	operator fun invoke(stream: MemorySegment)
}

internal fun interface FsEventsCurrentId {
	operator fun invoke(): Long
}

internal fun interface DispatchQueueCreate {
	operator fun invoke(label: MemorySegment, attributes: MemorySegment): MemorySegment
}

internal fun interface DispatchSyncF {
	operator fun invoke(queue: MemorySegment, context: MemorySegment, work: MemorySegment)
}

internal fun interface DispatchRelease {
	operator fun invoke(queue: MemorySegment)
}

internal fun interface CfArrayCreate {
	operator fun invoke(
		allocator: MemorySegment,
		values: MemorySegment,
		count: Long,
		callbacks: MemorySegment,
	): MemorySegment
}

internal fun interface CfStringCreateWithCString {
	operator fun invoke(
		allocator: MemorySegment,
		text: MemorySegment,
		encoding: Int,
	): MemorySegment
}

/** A stream of FSEvents over [folders], given to [seen] a batch at a time, until it is closed. */
class EventStream private constructor(
	private val stream: MemorySegment,
	private val queue: MemorySegment,
	private val arena: Arena,
) : AutoCloseable {
	/**
	 * The stream stopped, and let go of once any callback it was in is done (an empty function run on its queue, which
	 * runs one thing at a time, is waited for), so the callback is never freed while it runs.
	 */
	override fun close() {
		stop(stream)
		invalidate(stream)
		syncOn(queue, MemorySegment.NULL, nothing)
		release(stream)
		dispatchRelease(queue)
		arena.close()
	}

	companion object {
		private val coreServices = Frameworks.coreServices
		private val coreFoundation = Frameworks.coreFoundation

		private val create by coreServices.binding<FsEventStreamCreate>("FSEventStreamCreate")
		private val setQueue by coreServices.binding<FsEventStreamSetDispatchQueue>(
			"FSEventStreamSetDispatchQueue",
		)
		private val begin by coreServices.binding<FsEventStreamStart>("FSEventStreamStart")
		private val stop by coreServices.binding<FsEventStreamEnd>("FSEventStreamStop")
		private val invalidate by coreServices.binding<FsEventStreamEnd>(
			"FSEventStreamInvalidate",
		)
		private val release by coreServices.binding<FsEventStreamEnd>("FSEventStreamRelease")
		private val currentId by coreServices.binding<FsEventsCurrentId>(
			"FSEventsGetCurrentEventId",
		)
		private val queueCreate by Frameworks.system.binding<DispatchQueueCreate>(
			"dispatch_queue_create",
		)
		private val syncOn by Frameworks.system.binding<DispatchSyncF>("dispatch_sync_f")
		private val dispatchRelease by Frameworks.system.binding<DispatchRelease>(
			"dispatch_release",
		)
		private val arrayCreate by coreFoundation.binding<CfArrayCreate>("CFArrayCreate")
		private val stringCreate by coreFoundation.binding<CfStringCreateWithCString>(
			"CFStringCreateWithCString",
		)

		/** A function that does nothing, as `dispatch_sync_f` is given one. */
		private val nothing: MemorySegment by lazy {
			Library.upcall<DispatchWork>(DispatchWork { }, Arena.global())
		}

		/** `kCFStringEncodingUTF8`. */
		private const val UTF8 = 0x08000100

		/** `kFSEventStreamEventIdSinceNow`: what happens from now on, and nothing before. */
		const val SINCE_NOW = -1L

		/** `kFSEventStreamCreateFlagNoDefer` (the first of a burst at once), `WatchRoot` and `FileEvents`. */
		private const val FLAGS = 0x02 or 0x04 or 0x10

		/** The id of the last event the system has recorded: where a stream started later can begin. */
		fun currentEventId(): Long = currentId()

		/** What a stream's callback is given, copied out as it is given, to [seen]; what that throws, to [failed]. */
		private fun callback(seen: (List<FsEvent>) -> Unit, failed: (Throwable) -> Unit) =
			StreamCallback { _, _, count, paths, flags, ids ->
				// Nothing may be thrown back into the stream's thread: it would end the process.
				try {
					val named = paths.reinterpret(count * ADDRESS.byteSize())
					val flagged = flags.reinterpret(count * Int.SIZE_BYTES)
					val ordered = ids.reinterpret(count * Long.SIZE_BYTES)

					seen(
						(0 until count).map { at ->
							FsEvent(
								Path.of(named.getAtIndex(ADDRESS, at).reinterpret(Long.MAX_VALUE).getString(0)),
								FsFlag.of(flagged.getAtIndex(JAVA_INT, at)),
								ordered.getAtIndex(JAVA_LONG, at),
							)
						},
					)
				} catch (thrown: Throwable) {
					failed(thrown)
				}
			}

		/**
		 * A stream over [folders] started, its events since [since] given to [seen] a batch at a time, gathered over
		 * [latency] seconds (what happens in a burst is one batch); what the callback throws given to [failed]. Null
		 * where the system would not start one.
		 */
		fun start(
			folders: List<Path>,
			latency: Double,
			since: Long = SINCE_NOW,
			failed: (Throwable) -> Unit,
			seen: (List<FsEvent>) -> Unit,
		): EventStream? {
			// The stream's for as long as it lives: its callback, and what it was made with.
			val arena = Arena.ofShared()
			val called = Library.upcall<StreamCallback>(callback(seen, failed), arena)
			val strings =
				folders.map {
					stringCreate(MemorySegment.NULL, arena.allocateFrom(it.toString()), UTF8)
				}
			val values = arena.allocate(ADDRESS, strings.size.toLong())

			strings.forEachIndexed { at, string ->
				values.setAtIndex(
					ADDRESS,
					at.toLong(),
					string,
				)
			}

			val paths =
				arrayCreate(
					MemorySegment.NULL,
					values,
					strings.size.toLong(),
					coreFoundation.address("kCFTypeArrayCallBacks"),
				)

			// The array holds the strings now; and the stream, once made, the array.
			strings.forEach(CoreFoundation.release::invoke)

			val stream =
				create(MemorySegment.NULL, called, MemorySegment.NULL, paths, since, latency, FLAGS)

			CoreFoundation.release(paths)

			if (stream.address() == 0L) {
				arena.close()

				return null
			}

			val queue = queueCreate(arena.allocateFrom("dev.prism.fsevents"), MemorySegment.NULL)

			setQueue(stream, queue)

			if (!begin(stream)) {
				invalidate(stream)
				release(stream)
				dispatchRelease(queue)
				arena.close()

				return null
			}

			return EventStream(stream, queue, arena)
		}
	}
}
