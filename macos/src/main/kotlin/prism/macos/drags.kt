package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import prism.ffi.ByValue
import prism.ffi.Library
import prism.ffi.StructType

/*
 * Files dragged out of a window to another application, as AppKit carries a drag: a dragging session begun on the
 * window's view, with an item for each file (its URL, which is what Finder, a browser's upload box and every other
 * application that takes files reads), each shown as the file's own icon under the pointer. The session is AppKit's
 * from there: it follows the pointer, asks the application under it what it would do, and says how it ended.
 *
 * A session asks its source what may be done with what is dragged, and tells it the end; the source is an object of a
 * class made here for it. A move is the target's to make, as on every platform: prism is told only how the drag ended.
 */

/** An `NSPoint`, which AppKit passes by value: x, then y. */
object NsPoint : StructType {
	override val layout: MemoryLayout = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE)
}

/** `NSDragOperation (*)(id, SEL, NSDraggingSession *, NSDraggingContext)`. */
internal fun interface SourceOperations {
	fun allowed(
		self: MemorySegment,
		selector: MemorySegment,
		session: MemorySegment,
		context: Long,
	): Long
}

/** `void (*)(id, SEL, NSDraggingSession *, NSPoint, NSDragOperation)`. */
internal fun interface SessionEnded {
	fun ended(
		self: MemorySegment,
		selector: MemorySegment,
		session: MemorySegment,
		@ByValue(NsPoint::class) at: MemorySegment,
		operation: Long,
	)
}

/** Drags of files out of a window. */
object FileDrags {
	/** `NSDragOperationCopy`, `Link`, `Generic` and `Move`: what a target may make of the files. */
	private const val OPERATIONS = 1L or 2L or 4L or 16L

	/** `NSEventTypeLeftMouseDragged`: the event a drag is begun from. */
	private const val LEFT_DRAGGED = 6L

	/** How large a file's icon is drawn under the pointer, in points. */
	private const val ICON = 48.0

	/** Who is told how the drag now going ended: one at a time, as there is one pointer. */
	private val ending = AtomicReference<((Boolean) -> Unit)?>(null)

	/** The source every session is given: an object of a class made for it, kept for the life of the process. */
	private val source: Id by lazy {
		val operations =
			Library.upcall<SourceOperations>(
				SourceOperations {
					_,
					_,
					_,
					_,
					->
					OPERATIONS
				},
				Arena.global(),
			)

		val ended =
			Library.upcall<SessionEnded>(
				SessionEnded { _, _, _, _, operation ->
					// Nothing may be thrown back into AppKit's dispatch: it would end the process.
					try {
						ending.getAndSet(null)?.invoke(operation != 0L)
					} catch (thrown: Throwable) {
						Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(
							Thread.currentThread(),
							thrown,
						)
					}
				},
				Arena.global(),
			)

		NSObject.init(
			NSObject.alloc.on(
				ObjCRuntime.subclass(
					"PrismDragSource",
					NSObject,
					listOf(
						Implemented(
							"draggingSession:sourceOperationMaskForDraggingContext:",
							"Q32@0:8@16q24",
							operations,
						),
						Implemented(
							"draggingSession:endedAtPoint:operation:",
							"v48@0:8@16{CGPoint=dd}24Q40",
							ended,
						),
					),
				),
			),
		)
	}

	/**
	 * A drag of [files] begun from [window] (an `NSWindow *`), the pointer's button held at [x] and [fromBottom]
	 * points from the window's lower left corner; [onEnd] told, on the windows' thread, whether something took them.
	 * False where it cannot be begun: the pointer's button is held no longer.
	 *
	 * A session is begun from an event of the pointer's, and the event is made here, for where the pointer is. The
	 * one AppKit last gave out (`currentEvent`) is not it: by the time a drag is known to be one, SDL has read on,
	 * and the last event is as often the one that woke its loop as a movement of the pointer's.
	 */
	fun begin(
		window: Long,
		files: List<Path>,
		x: Double,
		fromBottom: Double,
		onEnd: (Boolean) -> Unit,
	): Boolean =
		ObjC.pooled {
			val nsWindow = Id(MemorySegment.ofAddress(window))
			val view = NSWindow.contentView(nsWindow).orNull()

			if (files.isEmpty() || view == null || NSEvent.pressedMouseButtons() and 1L == 0L)
				return@pooled false

			Arena.ofConfined().use { arena ->
				val at =
					arena.allocate(NsPoint.layout).also {
						it.set(JAVA_DOUBLE, 0, x)
						it.set(JAVA_DOUBLE, 8, fromBottom)
					}

				val event =
					NSEvent.mouseEvent(
						Int64 of LEFT_DRAGGED,
						Point of at,
						Int64 of 0L,
						Float64 of NSProcessInfo.systemUptime(NSProcessInfo.processInfo()),
						Int64 of NSWindow.windowNumber(nsWindow),
						Obj of Id.NIL,
						Int64 of 0L,
						Int64 of 1L,
						Float32 of 1f,
					)

				val items = NSMutableArray.array()
				val workspace = NSWorkspace.sharedWorkspace()

				files.forEachIndexed { nth, file ->
					val item =
						NSDraggingItem.initWithPasteboardWriter(
							NSObject.alloc.on(NSDraggingItem.cls),
							fileUrl(file),
						)

					// Each file's icon, the pointer in the middle of the first, the rest a little down and across.
					val frame =
						arena.allocate(NS_RECT).also {
							it.set(JAVA_DOUBLE, 0, x - ICON / 2 + nth * STEP)
							it.set(JAVA_DOUBLE, 8, fromBottom - ICON / 2 - nth * STEP)
							it.set(JAVA_DOUBLE, 16, ICON)
							it.set(JAVA_DOUBLE, 24, ICON)
						}

					NSDraggingItem.setDraggingFrame(
						item,
						frame,
						NSWorkspace.iconForFile(workspace, nsString(file.toString())),
					)
					NSMutableArray.addObject(items, item)
					NSObject.release(item)
				}

				ending.set(onEnd)

				val session = NSView.beginDraggingSession(view, items, event, source)

				if (session.isNil)
					ending.set(null)
				!session.isNil
			}
		}

	/** How far each file's icon after the first is from the one before, in points; only the first few are apart. */
	private const val STEP = 4.0
}
