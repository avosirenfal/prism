package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Path

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
		stop.handle.invokeExact(stream)
		invalidate.handle.invokeExact(stream)
		syncOn.handle.invokeExact(queue, MemorySegment.NULL, nothing)
		release.handle.invokeExact(stream)
		dispatchRelease.handle.invokeExact(queue)
		arena.close()
	}

	/** What a stream's callback is given, copied out as it is given. */
	private class Callback(
		private val seen: (List<FsEvent>) -> Unit,
		private val failed: (Throwable) -> Unit,
	) {
		@Suppress("unused") // Called by the stream, through the upcall.
		fun called(
			stream: MemorySegment,
			info: MemorySegment,
			count: Long,
			paths: MemorySegment,
			flags: MemorySegment,
			ids: MemorySegment,
		) {
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
	}

	companion object {
		private val coreServices = Frameworks.coreServices
		private val coreFoundation = Frameworks.coreFoundation

		private val create =
			coreServices.function(
				"FSEventStreamCreate",
				FunctionDescriptor.of(
					ADDRESS,
					ADDRESS,
					ADDRESS,
					ADDRESS,
					ADDRESS,
					JAVA_LONG,
					JAVA_DOUBLE,
					JAVA_INT,
				),
			)
		private val setQueue =
			coreServices.function(
				"FSEventStreamSetDispatchQueue",
				FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
			)
		private val begin =
			coreServices.function(
				"FSEventStreamStart",
				FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS),
			)
		private val stop =
			coreServices.function(
				"FSEventStreamStop",
				FunctionDescriptor.ofVoid(ADDRESS),
			)
		private val invalidate =
			coreServices.function(
				"FSEventStreamInvalidate",
				FunctionDescriptor.ofVoid(ADDRESS),
			)
		private val release =
			coreServices.function(
				"FSEventStreamRelease",
				FunctionDescriptor.ofVoid(ADDRESS),
			)
		private val currentId =
			coreServices.function(
				"FSEventsGetCurrentEventId",
				FunctionDescriptor.of(JAVA_LONG),
			)

		private val queueCreate =
			Library.system.function(
				"dispatch_queue_create",
				FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
			)
		private val syncOn =
			Library.system.function(
				"dispatch_sync_f",
				FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
			)
		private val dispatchRelease =
			Library.system.function(
				"dispatch_release",
				FunctionDescriptor.ofVoid(ADDRESS),
			)

		/** A function that does nothing, as `dispatch_sync_f` is given one: `void (*)(void *)`. */
		private val nothing: MemorySegment by lazy {
			Library.linker.upcallStub(
				MethodHandles
					.lookup()
					.findStatic(
						Nothing::class.java,
						"run",
						MethodType.methodType(Void.TYPE, MemorySegment::class.java),
					),
				FunctionDescriptor.ofVoid(ADDRESS),
				Arena.global(),
			)
		}

		/** What [nothing] calls. */
		private object Nothing {
			@JvmStatic
			@Suppress("unused", "UNUSED_PARAMETER") // Called through the upcall.
			fun run(context: MemorySegment) = Unit
		}

		private val arrayCreate =
			coreFoundation.function(
				"CFArrayCreate",
				FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS),
			)
		private val stringCreate =
			coreFoundation.function(
				"CFStringCreateWithCString",
				FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
			)
		private val cfRelease =
			coreFoundation.function(
				"CFRelease",
				FunctionDescriptor.ofVoid(ADDRESS),
			)

		/** `kCFStringEncodingUTF8`. */
		private const val UTF8 = 0x08000100

		/** `kFSEventStreamEventIdSinceNow`: what happens from now on, and nothing before. */
		const val SINCE_NOW = -1L

		/** `kFSEventStreamCreateFlagNoDefer` (the first of a burst at once), `WatchRoot` and `FileEvents`. */
		private const val FLAGS = 0x02 or 0x04 or 0x10

		/** The callback's C type: `void (*)(ConstFSEventStreamRef, void *, size_t, void *, const UInt32 *, const UInt64 *)`. */
		private val CALLBACK =
			FunctionDescriptor.ofVoid(
				ADDRESS,
				ADDRESS,
				JAVA_LONG,
				ADDRESS,
				ADDRESS,
				ADDRESS,
			)

		/** The id of the last event the system has recorded: where a stream started later can begin. */
		fun currentEventId(): Long = currentId.handle.invokeExact() as Long

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

			val target =
				MethodHandles
					.lookup()
					.findVirtual(
						Callback::class.java,
						"called",
						MethodType.methodType(
							Void.TYPE,
							MemorySegment::class.java,
							MemorySegment::class.java,
							Long::class.javaPrimitiveType,
							MemorySegment::class.java,
							MemorySegment::class.java,
							MemorySegment::class.java,
						),
					).bindTo(Callback(seen, failed))

			val callback = Library.linker.upcallStub(target, CALLBACK, arena)

			val strings =
				folders.map {
					stringCreate.handle.invokeExact(
						MemorySegment.NULL,
						arena.allocateFrom(it.toString()),
						UTF8,
					) as MemorySegment
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
				arrayCreate.handle.invokeExact(
					MemorySegment.NULL,
					values,
					strings.size.toLong(),
					coreFoundation.address("kCFTypeArrayCallBacks"),
				) as MemorySegment

			// The array holds the strings now; and the stream, once made, the array.
			for (string in strings)
				cfRelease.handle.invokeExact(string)

			val stream =
				create.handle.invokeExact(
					MemorySegment.NULL,
					callback,
					MemorySegment.NULL,
					paths,
					since,
					latency,
					FLAGS,
				) as MemorySegment

			cfRelease.handle.invokeExact(paths)

			if (stream.address() == 0L) {
				arena.close()

				return null
			}

			val queue =
				queueCreate.handle.invokeExact(
					arena.allocateFrom("dev.prism.fsevents"),
					MemorySegment.NULL,
				) as MemorySegment

			setQueue.handle.invokeExact(stream, queue)

			if (!(begin.handle.invokeExact(stream) as Boolean)) {
				invalidate.handle.invokeExact(stream)
				release.handle.invokeExact(stream)
				arena.close()

				return null
			}

			return EventStream(stream, queue, arena)
		}
	}
}
