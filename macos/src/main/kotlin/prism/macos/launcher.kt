package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import prism.ffi.ByValue
import prism.ffi.Library
import prism.ffi.StructType

/*
 * What a launcher is on a Mac, in place of Spotlight: a key that shows it from anywhere, and a window over every
 * other, on every Space and over an application that fills the screen, given the keyboard as it is shown and giving
 * it back to the application that had it as it goes.
 *
 * The key is Carbon's hot key (`RegisterEventHotKey`), which is still how a Mac application has a key of its own
 * system-wide, and needs no permission (an event tap would need Accessibility). It is told of through the
 * application's event target, as its event loop (SDL's, here) dispatches events, so the handler runs on the windows'
 * thread. A key the system or another application has already (Spotlight's ⌘Space) cannot be had, and is said so.
 */

/** Carbon's modifiers, as a hot key is registered with them. */
enum class HotKeyModifier(
	val bit: Int,
) {
	Command(1 shl 8),
	Shift(1 shl 9),
	Option(1 shl 11),
	Control(1 shl 12),
}

/** A key of the keyboard, as a Mac's virtual key code names it (`kVK_Space`, 49), from AWT's code for it. */
object MacKeys {
	/** The Mac's code for the key AWT calls [awt]; null for one a hot key cannot be. */
	fun of(awt: Int): Int? = CODES[awt]

	private val CODES: Map<Int, Int> by lazy {
		val letters =
			"ASDFHGZXCV?BQWERYT".withIndex().filter { it.value != '?' }.associate {
				it.value to
					it.index
			} +
				mapOf(
					'O' to 0x1F,
					'U' to 0x20,
					'I' to 0x22,
					'P' to 0x23,
					'L' to 0x25,
					'J' to 0x26,
					'K' to 0x28,
					'N' to 0x2D,
					'M' to 0x2E,
				)

		val digits =
			mapOf(
				'1' to 0x12,
				'2' to 0x13,
				'3' to 0x14,
				'4' to 0x15,
				'6' to 0x16,
				'5' to 0x17,
				'9' to 0x19,
				'7' to 0x1A,
				'8' to 0x1C,
				'0' to 0x1D,
			)

		val functions =
			listOf(0x7A, 0x78, 0x63, 0x76, 0x60, 0x61, 0x62, 0x64, 0x65, 0x6D, 0x67, 0x6F)

		letters.mapKeys { it.key.code } + digits.mapKeys { it.key.code } +
			functions.withIndex().associate {
				(java.awt.event.KeyEvent.VK_F1 + it.index) to
					it.value
			} +
			mapOf(
				java.awt.event.KeyEvent.VK_SPACE to 0x31,
				java.awt.event.KeyEvent.VK_ENTER to 0x24,
				java.awt.event.KeyEvent.VK_TAB to 0x30,
				java.awt.event.KeyEvent.VK_ESCAPE to 0x35,
				java.awt.event.KeyEvent.VK_BACK_SPACE to 0x33,
				java.awt.event.KeyEvent.VK_DELETE to 0x75,
				java.awt.event.KeyEvent.VK_BACK_QUOTE to 0x32,
				java.awt.event.KeyEvent.VK_MINUS to 0x1B,
				java.awt.event.KeyEvent.VK_EQUALS to 0x18,
				java.awt.event.KeyEvent.VK_OPEN_BRACKET to 0x21,
				java.awt.event.KeyEvent.VK_CLOSE_BRACKET to 0x1E,
				java.awt.event.KeyEvent.VK_BACK_SLASH to 0x2A,
				java.awt.event.KeyEvent.VK_SEMICOLON to 0x29,
				java.awt.event.KeyEvent.VK_QUOTE to 0x27,
				java.awt.event.KeyEvent.VK_COMMA to 0x2B,
				java.awt.event.KeyEvent.VK_PERIOD to 0x2F,
				java.awt.event.KeyEvent.VK_SLASH to 0x2C,
				java.awt.event.KeyEvent.VK_LEFT to 0x7B,
				java.awt.event.KeyEvent.VK_RIGHT to 0x7C,
				java.awt.event.KeyEvent.VK_DOWN to 0x7D,
				java.awt.event.KeyEvent.VK_UP to 0x7E,
				java.awt.event.KeyEvent.VK_HOME to 0x73,
				java.awt.event.KeyEvent.VK_END to 0x77,
				java.awt.event.KeyEvent.VK_PAGE_UP to 0x74,
				java.awt.event.KeyEvent.VK_PAGE_DOWN to 0x79,
			)
	}
}

/** What registering a hot key came to. */
sealed interface HotKeyRegistered {
	/** It is the application's, until [key] is closed. */
	data class Held(
		val key: HotKey,
	) : HotKeyRegistered

	/** It is not: another has it, or the system would not give it, as [why] says. */
	data class Refused(
		val why: String,
	) : HotKeyRegistered
}

/** An `EventHotKeyID`, which Carbon is passed by value: a signature (an `OSType`), then a number. */
object EventHotKeyId : StructType {
	override val layout: MemoryLayout =
		MemoryLayout.structLayout(JAVA_INT.withName("signature"), JAVA_INT.withName("id"))
}

internal fun interface GetApplicationEventTarget {
	operator fun invoke(): MemorySegment
}

internal fun interface InstallEventHandler {
	operator fun invoke(
		target: MemorySegment,
		handler: MemorySegment,
		count: Long,
		types: MemorySegment,
		data: MemorySegment,
		reference: MemorySegment,
	): Int
}

internal fun interface RemoveEventHandler {
	operator fun invoke(handler: MemorySegment): Int
}

internal fun interface RegisterEventHotKey {
	operator fun invoke(
		key: Int,
		modifiers: Int,
		@ByValue(EventHotKeyId::class) id: MemorySegment,
		target: MemorySegment,
		options: Int,
		reference: MemorySegment,
	): Int
}

internal fun interface UnregisterEventHotKey {
	operator fun invoke(reference: MemorySegment): Int
}

/** `EventHandlerProcPtr`: the call it is part of, the event, and the handler's own data; answered with a status. */
internal fun interface EventHandler {
	fun handle(call: MemorySegment, event: MemorySegment, data: MemorySegment): Int
}

/** A key system-wide that is the application's, [pressed] called on the windows' thread each time it is pressed. */
class HotKey private constructor(
	private val reference: MemorySegment,
	private val handler: MemorySegment,
	private val arena: Arena,
) : AutoCloseable {
	override fun close() {
		unregister(reference)
		removeHandler(handler)
		arena.close()
	}

	companion object {
		private val applicationTarget by Frameworks.carbon.binding<GetApplicationEventTarget>(
			"GetApplicationEventTarget",
		)
		private val installHandler by Frameworks.carbon.binding<InstallEventHandler>(
			"InstallEventHandler",
		)
		private val removeHandler by Frameworks.carbon.binding<RemoveEventHandler>(
			"RemoveEventHandler",
		)
		private val register by Frameworks.carbon.binding<RegisterEventHotKey>(
			"RegisterEventHotKey",
		)
		private val unregister by Frameworks.carbon.binding<UnregisterEventHotKey>(
			"UnregisterEventHotKey",
		)

		/** `kEventClassKeyboard` ('keyb') and `kEventHotKeyPressed`. */
		private const val KEYBOARD = 0x6B657962
		private const val HOT_KEY_PRESSED = 5

		/** prism's signature, 'prsm'. */
		private const val SIGNATURE = 0x7072736D

		/** `eventHotKeyExistsErr`: another has the key. */
		private const val TAKEN = -9878

		/**
		 * [key] (a Mac's virtual key code) with [modifiers] made the application's, [pressed] called each time it is
		 * pressed. Called on the windows' thread, whose event loop tells of it.
		 */
		fun register(
			key: Int,
			modifiers: Set<HotKeyModifier>,
			pressed: () -> Unit,
		): HotKeyRegistered {
			val arena = Arena.ofShared()
			val target = applicationTarget()

			val told =
				Library.upcall<EventHandler>(
					EventHandler { _, _, _ ->
						// Nothing may be thrown back into Carbon's dispatch: it would end the process.
						try {
							pressed()
						} catch (thrown: Throwable) {
							Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(
								Thread.currentThread(),
								thrown,
							)
						}
						0
					},
					arena,
				)

			// An EventTypeSpec: its class, then its kind.
			val spec =
				arena.allocate(8, 4).also {
					it.set(JAVA_INT, 0, KEYBOARD)
					it.set(JAVA_INT, 4, HOT_KEY_PRESSED)
				}
			val handler = arena.allocate(ADDRESS)
			val reference = arena.allocate(ADDRESS)
			val installed = installHandler(target, told, 1L, spec, MemorySegment.NULL, handler)

			if (installed != 0) {
				arena.close()

				return HotKeyRegistered.Refused(
					"the system would not tell prism of its keys (error $installed)",
				)
			}

			val id =
				arena.allocate(EventHotKeyId.layout).also {
					it.set(JAVA_INT, 0, SIGNATURE)
					it.set(JAVA_INT, 4, 1)
				}

			val registered =
				register(
					key,
					modifiers.fold(0) { bits, it -> bits or it.bit },
					id,
					target,
					0,
					reference,
				)

			if (registered != 0) {
				removeHandler(handler.get(ADDRESS, 0L))
				arena.close()

				return HotKeyRegistered.Refused(
					if (registered == TAKEN)
						"another application has that key already"
					else
						"the system would not give that key (error $registered)",
				)
			}

			return HotKeyRegistered.Held(
				HotKey(reference.get(ADDRESS, 0L), handler.get(ADDRESS, 0L), arena),
			)
		}
	}
}

/*
 * The launcher's window, as Spotlight's is: a non-activating panel, which is given the keyboard without its
 * application being brought forward, so the application in front stays in front (its menu bar, its windows' focus)
 * while the launcher is typed into, and nothing has to be given back as it goes. It is at the level of a menu that
 * has popped up, on every Space and beside an application that fills the screen, and not among the windows ⌘` goes
 * through.
 *
 * A window is a panel, and non-activating, from the moment it is made and by how it is made: the window server is
 * told so then, and not when a style is changed after. So the panel is made here, as AppKit makes one
 * (`initWithContentRect:styleMask:backing:defer:` with the non-activating style), and handed to SDL to be its window
 * (`SDL_PROP_WINDOW_CREATE_COCOA_WINDOW_POINTER`), which SDL has for windows an application makes itself: SDL draws
 * in it and hears its events, and leaves closing it to whoever made it.
 *
 * Two classes are made for it, each the runtime's own subclass with nothing but methods: a panel that says it takes
 * the keyboard (one with no title bar says it does not, unasked) and is never the main window; and a view that takes
 * the click that also brings the panel forward, and hands the right button's press on (a view of AppKit's keeps it,
 * for a menu), as SDL's own view does for the windows SDL makes.
 */

/** `BOOL (*)(id, SEL)`: a method of no arguments that answers yes or no. */
internal fun interface AnswersBool {
	fun answer(self: MemorySegment, selector: MemorySegment): Boolean
}

/** `BOOL (*)(id, SEL, id)`: a method of one object that answers yes or no. */
internal fun interface AnswersBoolOf {
	fun answer(
		self: MemorySegment,
		selector: MemorySegment,
		argument: MemorySegment,
	): Boolean
}

/** `void (*)(id, SEL, id)`: a method of one object that answers nothing. */
internal fun interface Takes {
	fun take(self: MemorySegment, selector: MemorySegment, argument: MemorySegment)
}

internal fun interface PthreadMainNp {
	operator fun invoke(): Int
}

/**
 * AppKit's own thread, the process's first, on which alone a window may be made. Work is done there: at once, where
 * this is that thread (as prism's windows' thread is, started on the first thread); and otherwise handed to its
 * queue and waited for, which needs that thread to be running its loop (as it is under `java` started the usual way,
 * whose first thread does nothing else).
 */
object MainThread {
	private val isMain by Frameworks.system.binding<PthreadMainNp>("pthread_main_np")
	private val syncOn by Frameworks.system.binding<DispatchSyncF>("dispatch_sync_f")

	/** [work] done on the main thread, and what it made; what it threw is thrown here. */
	fun <T> sync(work: () -> T): T {
		if (isMain() != 0)
			return work()

		val done = java.util.concurrent.CompletableFuture<Result<T>>()

		Arena.ofConfined().use { arena ->
			// Nothing may be thrown back into the queue: what the work throws is carried here.
			val function =
				Library.upcall<DispatchWork>(
					DispatchWork {
						done.complete(runCatching(work))
					},
					arena,
				)

			syncOn(Frameworks.system.address("_dispatch_main_q"), MemorySegment.NULL, function)
		}

		return done.join().getOrThrow()
	}
}

/**
 * A window of prism's own on a Mac's Spaces. A full-screen application is a Space of its own, which has only its own
 * windows, and the windows marked as ones to be shown with it; a window that is not is opened on another Space, out of
 * sight. prism's windows are marked so, and are moved to the Space in front as they are brought forward, so that a
 * window opened over a full-screen application (the preferences, from an explorer over it) is shown there.
 */
object Spaces {
	/** `moveToActiveSpace` and `fullScreenAuxiliary`. */
	private const val WITH_WHAT_IS_IN_FRONT = (1L shl 1) or (1L shl 8)

	/** [window] (an `NSWindow *`) shown on the Space in front, a full-screen application's included. */
	fun followFront(window: Long) {
		MainThread.sync {
			ObjC.pooled {
				NSWindow.setCollectionBehavior(
					Id(MemorySegment.ofAddress(window)),
					WITH_WHAT_IS_IN_FRONT,
				)
			}
		}
	}
}

/** A panel made for SDL to have as a window: closed by whoever made it, once SDL has let go of it. */
class Panel internal constructor(
	private val panel: Id,
) : AutoCloseable {
	/** The `NSWindow *`, as SDL is given it. */
	val window: Long get() = panel.address.address()

	override fun close() {
		MainThread.sync {
			ObjC.pooled {
				NSWindow.close(panel)
				NSObject.release(panel)
			}
		}
	}

	companion object {
		/** `NSWindowStyleMaskNonactivatingPanel`, on a window with no title bar (`NSWindowStyleMaskBorderless`, 0). */
		private const val STYLE = 1L shl 7

		/** `NSBackingStoreBuffered`. */
		private const val BUFFERED = 2L

		/** `NSPopUpMenuWindowLevel`. */
		private const val LEVEL = 101L

		/** `canJoinAllSpaces`, `ignoresCycle` and `fullScreenAuxiliary`. */
		private const val BEHAVIOUR = (1L shl 0) or (1L shl 6) or (1L shl 8)

		private val yes =
			Library.upcall<AnswersBool>(
				AnswersBool { _, _ -> true },
				Arena.global(),
			)
		private val no =
			Library.upcall<AnswersBool>(
				AnswersBool {
					_,
					_,
					->
					false
				},
				Arena.global(),
			)
		private val yesOf =
			Library.upcall<AnswersBoolOf>(
				AnswersBoolOf {
					_,
					_,
					_,
					->
					true
				},
				Arena.global(),
			)

		/** The right button's press handed to whatever is next to answer it: SDL's listener, which tells SDL of it. */
		private val handsOn =
			Library.upcall<Takes>(
				Takes { self, _, event ->
					// Nothing may be thrown back into AppKit's dispatch: it would end the process.
					try {
						NSResponder.rightMouseDown(NSResponder.nextResponder(Id(self)), Id(event))
					} catch (thrown: Throwable) {
						Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(
							Thread.currentThread(),
							thrown,
						)
					}
				},
				Arena.global(),
			)

		private val panelClass by lazy {
			ObjCRuntime.subclass(
				"PrismPanel",
				NSPanel,
				listOf(
					Implemented("canBecomeKeyWindow", "B16@0:8", yes),
					Implemented("canBecomeMainWindow", "B16@0:8", no),
				),
			)
		}

		private val viewClass by lazy {
			ObjCRuntime.subclass(
				"PrismPanelView",
				NSView,
				listOf(
					Implemented("acceptsFirstMouse:", "B24@0:8@16", yesOf),
					Implemented("rightMouseDown:", "v24@0:8@16", handsOn),
				),
			)
		}

		/** A non-activating panel [width] by [height] points, in the middle of the screen, not yet shown. */
		fun make(width: Double, height: Double): Panel = MainThread.sync { made(width, height) }

		private fun made(width: Double, height: Double): Panel =
			ObjC.pooled {
				Arena.ofConfined().use { arena ->
					val frame =
						arena.allocate(NS_RECT).also {
							it.set(JAVA_DOUBLE, 16, width)
							it.set(JAVA_DOUBLE, 24, height)
						}

					val panel =
						NSWindow.initWithContentRect(
							NSObject.alloc.on(panelClass),
							frame,
							STYLE,
							BUFFERED,
							false,
						)

					val view = NSView.initWithFrame(NSObject.alloc.on(viewClass), frame)

					check(!panel.isNil && !view.isNil) { "AppKit made no panel" }
					// Kept by this until it is closed, and by nothing AppKit does on closing it.
					NSWindow.setReleasedWhenClosed(panel, false)
					NSWindow.setContentView(panel, view)
					NSObject.release(view)
					NSPanel.setFloatingPanel(panel, true)
					NSPanel.setBecomesKeyOnlyIfNeeded(panel, false)
					NSWindow.setHidesOnDeactivate(panel, false)
					NSWindow.setLevel(panel, LEVEL)
					NSWindow.setCollectionBehavior(panel, BEHAVIOUR)
					NSWindow.center(panel)
					Panel(panel)
				}
			}
	}
}

/*
 * Whether prism is in the Dock and ⌘Tab: an application of the usual kind while a window of the usual kind is open
 * (an explorer's, a picker's, the preferences), with prism's own icon there, and an accessory otherwise, as a daemon
 * and a launcher are: no Dock icon of `java`'s, nothing to switch to.
 */
object Dock {
	/** `NSApplicationActivationPolicyRegular` and `Accessory`. */
	private const val REGULAR = 0L
	private const val ACCESSORY = 1L

	/** prism brought in front of the other applications, with the windows it has. */
	fun forward() {
		ObjC.pooled {
			NSApplication.activateIgnoringOtherApps(NSApplication.sharedApplication(), true)
		}
	}

	/** prism in the Dock, with [icon] (an image's bytes: SVG, PNG) as its icon, where [shown]; out of it where not. */
	fun shown(shown: Boolean, icon: ByteArray?) {
		ObjC.pooled {
			val application = NSApplication.sharedApplication()

			NSApplication.setActivationPolicy(application, if (shown) REGULAR else ACCESSORY)
			if (shown && icon != null)
				Arena.ofConfined().use { arena ->
					val data =
						NSData.dataWithBytes(
							arena.allocateFrom(JAVA_BYTE, *icon),
							icon.size.toLong(),
						)

					val image = NSImage.initWithData(NSObject.alloc.on(NSImage.cls), data)

					if (!image.isNil) {
						NSApplication.setApplicationIconImage(application, image)
						NSObject.release(image)
					}
				}
		}
	}
}
