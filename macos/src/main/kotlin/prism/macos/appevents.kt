package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import prism.ffi.Library

/*
 * What a Mac asks of an application that is running, beyond its windows' events.
 *
 * **Opened again.** An application opened while it is running (its icon clicked in Finder, Launchpad or the Dock, or
 * `open -a`) is not started a second time: the one running is sent an Apple event, "reopen", and does what it sees
 * fit, which for most is a new window where it has none. The event is heard through the Apple Event Manager's own C
 * function, on the windows' thread, as the application's event loop dispatches it.
 *
 * **The Dock's menu.** A press on an application's icon in the Dock shows a menu the application may add to, asked of
 * its delegate (`applicationDockMenu:`). SDL's delegate does not answer that, so it is given the method, which is all
 * that is asked of it; the menu's one item, "New Window", is sent to an object of a class made here for it.
 */

/** `AEEventHandlerUPP`: the event, the reply to fill, and the handler's own data; answered with a status. */
internal fun interface AppleEventHandler {
	fun handle(event: MemorySegment, reply: MemorySegment, data: MemorySegment): Short
}

internal fun interface AeInstallEventHandler {
	operator fun invoke(
		eventClass: Int,
		eventId: Int,
		handler: MemorySegment,
		data: MemorySegment,
		system: Boolean,
	): Short
}

/** `id (*)(id, SEL, id)`: a method of one object that answers an object. */
internal fun interface AnswersObject {
	fun answer(
		self: MemorySegment,
		selector: MemorySegment,
		argument: MemorySegment,
	): MemorySegment
}

/** What a Mac asks of the application, each heard for as long as the process runs. */
object AppEvents {
	private val install by Frameworks.carbon.binding<AeInstallEventHandler>(
		"AEInstallEventHandler",
	)

	/** `kCoreEventClass` ('aevt') and `kAEReopenApplication` ('rapp'). */
	private const val CORE = 0x61657674
	private const val REOPEN = 0x72617070

	/** What is thrown where a Mac calls back is said, and goes no further: thrown on, it would end the process. */
	private fun guarded(work: () -> Unit) {
		try {
			work()
		} catch (thrown: Throwable) {
			Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(
				Thread.currentThread(),
				thrown,
			)
		}
	}

	/**
	 * [reopened] called, on the windows' thread, each time the application is opened while it runs. Asked for on that
	 * thread, once the application is set up (after SDL is started), since the last to ask is the one told. Whether
	 * the system took the handler.
	 */
	fun onReopen(reopened: () -> Unit): Boolean {
		val handler =
			Library.upcall<AppleEventHandler>(
				AppleEventHandler { _, _, _ ->
					guarded(reopened)
					0
				},
				Arena.global(),
			)

		return install(CORE, REOPEN, handler, MemorySegment.NULL, false) == 0.toShort()
	}

	/**
	 * The Dock's menu for the application given one item, [title], which calls [chosen] on the windows' thread.
	 * Whether it could be: not where the application has no delegate, or its delegate has a Dock menu of its own.
	 */
	fun dockItem(title: String, chosen: () -> Unit): Boolean =
		ObjC.pooled {
			val delegate =
				NSApplication.delegate(NSApplication.sharedApplication()).orNull()
					?: return@pooled false

			val target =
				NSObject.init(
					NSObject.alloc.on(
						ObjCRuntime.subclass(
							"PrismDockTarget",
							NSObject,
							listOf(
								Implemented(
									CHOSEN,
									"v24@0:8@16",
									Library.upcall<Takes>(Takes { _, _, _ -> guarded(chosen) }, Arena.global()),
								),
							),
						),
					),
				)

			// Made once and kept for the life of the process: the delegate hands the same menu out each time.
			val menu = NSMenu.initWithTitle(NSObject.alloc.on(NSMenu.cls), nsString(""))

			NSMenuItem.setTarget(
				NSMenu.addItemWithTitle(menu, nsString(title), CHOSEN, nsString("")),
				target,
			)
			ObjCRuntime.add(
				ObjCRuntime.classOf(delegate),
				Implemented(
					"applicationDockMenu:",
					"@24@0:8@16",
					Library.upcall<AnswersObject>(
						AnswersObject {
							_,
							_,
							_,
							->
							menu.address
						},
						Arena.global(),
					),
				),
			)
		}

	private const val CHOSEN = "prismDockItemChosen:"
}
