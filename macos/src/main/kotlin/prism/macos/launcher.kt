package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

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

/** A key system-wide that is the application's, [pressed] called on the windows' thread each time it is pressed. */
class HotKey private constructor(
	private val reference: MemorySegment,
	private val handler: MemorySegment,
	private val arena: Arena,
) : AutoCloseable {
	override fun close() {
		unregister.handle.invokeExact(reference) as Int
		removeHandler.handle.invokeExact(handler) as Int
		arena.close()
	}

	/** What the handler is: told of the press, and saying it was handled (`noErr`). */
	private class Pressed(
		private val pressed: () -> Unit,
	) {
		@Suppress("unused", "UNUSED_PARAMETER") // Called by Carbon, through the upcall.
		fun handle(call: MemorySegment, event: MemorySegment, data: MemorySegment): Int {
			// Nothing may be thrown back into Carbon's dispatch: it would end the process.
			try {
				pressed()
			} catch (thrown: Throwable) {
				Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(
					Thread.currentThread(),
					thrown,
				)
			}

			return 0
		}
	}

	companion object {
		private val carbon = Library.framework("Carbon")

		/** An `EventHotKeyID`, passed by value: a signature (an `OSType`), then a number. */
		private val HOT_KEY_ID =
			MemoryLayout.structLayout(
				JAVA_INT.withName("signature"),
				JAVA_INT.withName("id"),
			)

		private val applicationTarget =
			carbon.function(
				"GetApplicationEventTarget",
				FunctionDescriptor.of(ADDRESS),
			)
		private val installHandler =
			carbon.function(
				"InstallEventHandler",
				FunctionDescriptor.of(
					JAVA_INT,
					ADDRESS,
					ADDRESS,
					JAVA_LONG,
					ADDRESS,
					ADDRESS,
					ADDRESS,
				),
			)
		private val removeHandler =
			carbon.function(
				"RemoveEventHandler",
				FunctionDescriptor.of(JAVA_INT, ADDRESS),
			)
		private val register =
			carbon.function(
				"RegisterEventHotKey",
				FunctionDescriptor.of(
					JAVA_INT,
					JAVA_INT,
					JAVA_INT,
					HOT_KEY_ID,
					ADDRESS,
					JAVA_INT,
					ADDRESS,
				),
			)
		private val unregister =
			carbon.function(
				"UnregisterEventHotKey",
				FunctionDescriptor.of(JAVA_INT, ADDRESS),
			)

		/** `kEventClassKeyboard` ('keyb') and `kEventHotKeyPressed`. */
		private const val KEYBOARD = 0x6B657962
		private const val HOT_KEY_PRESSED = 5

		/** prism's signature, 'prsm'. */
		private const val SIGNATURE = 0x7072736D

		/** `eventHotKeyExistsErr`: another has the key. */
		private const val TAKEN = -9878

		private val HANDLER = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)

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
			val target = applicationTarget.handle.invokeExact() as MemorySegment

			val upcall =
				Library.linker.upcallStub(
					MethodHandles
						.lookup()
						.findVirtual(
							Pressed::class.java,
							"handle",
							MethodType.methodType(
								Int::class.javaPrimitiveType,
								MemorySegment::class.java,
								MemorySegment::class.java,
								MemorySegment::class.java,
							),
						).bindTo(Pressed(pressed)),
					HANDLER,
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

			val installed =
				installHandler.handle.invokeExact(
					target,
					upcall,
					1L,
					spec,
					MemorySegment.NULL,
					handler,
				) as Int

			if (installed != 0) {
				arena.close()

				return HotKeyRegistered.Refused(
					"the system would not tell prism of its keys (error $installed)",
				)
			}

			val id =
				arena.allocate(HOT_KEY_ID).also {
					it.set(JAVA_INT, 0, SIGNATURE)
					it.set(JAVA_INT, 4, 1)
				}

			val registered =
				register.handle.invokeExact(
					key,
					modifiers.fold(0) { bits, it -> bits or it.bit },
					id,
					target,
					0,
					reference,
				) as Int

			if (registered != 0) {
				removeHandler.handle.invokeExact(handler.get(ADDRESS, 0L)) as Int
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
 * The launcher's window over every other, as Spotlight's is: a non-activating panel, which is given the keyboard
 * without its application being brought forward, so the application in front stays in front (its menu bar, its
 * windows' focus) while the launcher is typed into, and nothing has to be given back as it goes. It is at the level of a
 * menu that has popped up, on every Space and beside an application that fills the screen, and not among the windows
 * ⌘` goes through.
 *
 * A panel is made a panel as it is made, and SDL makes windows: so SDL's window is made one after, as Tauri's
 * windows are made panels. A class is made at run time whose superclass is NSPanel and which has every method of
 * SDL's window class (which adds no fields to NSWindow's, nor NSPanel to them, so the object is the same size either
 * way), and the window's class is set to it; with the non-activating style, it is a panel. Its own methods say it
 * becomes the key window (it is typed into) and never the main one (it is no document's window).
 */

/** A launcher's window on a Mac. */
object Overlay {
	/** `NSPopUpMenuWindowLevel`. */
	private const val LEVEL = 101L

	/** `canJoinAllSpaces`, `ignoresCycle` and `fullScreenAuxiliary`. */
	private const val BEHAVIOUR = (1L shl 0) or (1L shl 6) or (1L shl 8)

	/** `NSWindowStyleMaskNonactivatingPanel`. */
	private const val NON_ACTIVATING = 1L shl 7

	/** The window [window] (an `NSWindow *`, as SDL gives it) made a non-activating panel over every other, and given the keyboard. */
	fun shown(window: Long) {
		ObjC.pooled {
			val own = Id(MemorySegment.ofAddress(window))

			Panels.make(own)
			own.perform("setStyleMask:", own.integer("styleMask") or NON_ACTIVATING)
			own.perform("setFloatingPanel:", true)
			own.perform("setBecomesKeyOnlyIfNeeded:", false)
			own.perform("setHidesOnDeactivate:", false)
			own.perform("setLevel:", LEVEL)
			own.perform("setCollectionBehavior:", BEHAVIOUR)
			own.perform("makeKeyAndOrderFront:", Id.NIL)
		}
	}
}

/** Windows made panels: the class each window class is made into, made once. */
private object Panels {
	private val made = java.util.concurrent.ConcurrentHashMap<Long, MemorySegment>()

	/** [window]'s class made a panel class of its own methods, where it is not one already. */
	fun make(window: Id) {
		val current = ObjCRuntime.classOf(window)

		if (current.address() in made.values.map { it.address() })
			return

		val panel = made.getOrPut(current.address()) { panelOf(current) }

		ObjCRuntime.setClass(window, panel)
	}

	private fun panelOf(windowClass: MemorySegment): MemorySegment {
		val name = "PrismPanel_${ObjCRuntime.nameOf(windowClass)}"

		ObjCRuntime.lookUp(name)?.let { return it }

		val panel = ObjCRuntime.allocate(ObjC.cls("NSPanel", Frameworks.appKit).address, name)

		// Its own first: a method added later of the same name is not added.
		ObjCRuntime.add(panel, "canBecomeKeyWindow", answering(true))
		ObjCRuntime.add(panel, "canBecomeMainWindow", answering(false))
		ObjCRuntime.copyMethods(windowClass, panel)
		ObjCRuntime.register(panel)

		return panel
	}

	/** A method of no arguments that answers [answer]: `BOOL (*)(id, SEL)`. */
	private fun answering(answer: Boolean): MemorySegment =
		Library.linker.upcallStub(
			java.lang.invoke.MethodHandles
				.dropArguments(
					java.lang.invoke.MethodHandles
						.constant(Boolean::class.javaPrimitiveType, answer),
					0,
					MemorySegment::class.java,
					MemorySegment::class.java,
				),
			FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_BOOLEAN, ADDRESS, ADDRESS),
			Arena.global(),
		)
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

	/** prism in the Dock, with [icon] (an image's bytes: SVG, PNG) as its icon, where [shown]; out of it where not. */
	fun shown(shown: Boolean, icon: ByteArray?) {
		ObjC.pooled {
			val application =
				ObjC
					.cls(
						"NSApplication",
						Frameworks.appKit,
					).send("sharedApplication")

			application.bool("setActivationPolicy:", if (shown) REGULAR else ACCESSORY)
			if (shown && icon != null)
				java.lang.foreign.Arena.ofConfined().use { arena ->
					val data =
						ObjC
							.cls(
								"NSData",
							).send(
								"dataWithBytes:length:",
								arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, *icon),
								icon.size.toLong(),
							)

					val image =
						ObjC
							.cls(
								"NSImage",
								Frameworks.appKit,
							).send("alloc")
							.send("initWithData:", data)

					if (!image.isNil) {
						application.perform("setApplicationIconImage:", image)
						image.perform("release")
					}
				}
		}
	}
}
