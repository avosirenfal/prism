package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.atomic.AtomicReference
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
 * its delegate (`applicationDockMenu:`) each time it is shown. SDL's delegate does not answer that, so it is given the
 * method, which is all that is asked of it. What the menu holds is plain data ([DockEntry]), made again each time; an
 * item chosen is sent to an object of a class made here for it, which is told which by the item's tag, its place
 * among the items, and gives back what that item asks for.
 */

/** What the Dock's menu for the application holds: items, each with what it asks for, and lines between them. */
sealed interface DockEntry<out T> {
	data class Item<out T>(
		val title: String,
		val asks: T,
	) : DockEntry<T>

	data object Line : DockEntry<Nothing>
}

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
			said(thrown)
		}
	}

	/** [thrown], said as what is thrown and not caught is. */
	private fun said(thrown: Throwable) {
		Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(
			Thread.currentThread(),
			thrown,
		)
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
	 * The Dock's menu for the application: [entries], made again each time it is shown, so that it is as things are
	 * then (the folders gone to lately); an item chosen is given to [chosen], on the windows' thread, as what it asks
	 * for. Whether it could be: not where the application has no delegate.
	 */
	fun <T> dockMenu(entries: () -> List<DockEntry<T>>, chosen: (T) -> Unit): Boolean {
		// What each item of the menu shown last asks for, by its tag.
		val shown = AtomicReference<List<T>>(emptyList())

		return ObjC.pooled {
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
									Library.upcall<Takes>(
										Takes { _, _, item ->
											guarded {
												shown
													.get()
													.getOrNull(NSMenuItem.tag(Id(item)).toInt())
													?.let(chosen)
											}
										},
										Arena.global(),
									),
								),
							),
						),
					),
				)

			/** [made] as a menu, owned by whoever made it: each item told its place among the items, and the target. */
			fun menuOf(made: List<DockEntry<T>>): Id =
				ObjC.pooled {
					val menu = NSMenu.initWithTitle(NSObject.alloc.on(NSMenu.cls), nsString(""))

					made.fold(0L) { at, entry ->
						when (entry) {
							is DockEntry.Item -> {
								val item =
									NSMenu.addItemWithTitle(
										menu,
										nsString(entry.title),
										CHOSEN,
										nsString(""),
									)

								NSMenuItem.setTarget(item, target)
								NSMenuItem.setTag(item, at)
								at + 1
							}
							DockEntry.Line -> {
								NSMenu.addItem(menu, NSMenuItem.separatorItem())
								at
							}
						}
					}
					menu
				}

			ObjCRuntime.add(
				ObjCRuntime.classOf(delegate),
				Implemented(
					"applicationDockMenu:",
					"@24@0:8@16",
					Library.upcall<AnswersObject>(
						AnswersObject { _, _, _ ->
							try {
								val made = entries()

								shown.set(made.filterIsInstance<DockEntry.Item<T>>().map { it.asks })
								// Given to the pool AppKit asked in, not to one of prism's: it lives till the Dock has it.
								NSObject.autorelease(menuOf(made)).address
							} catch (thrown: Throwable) {
								// No menu of prism's: the Dock's own items alone.
								said(thrown)
								MemorySegment.NULL
							}
						},
						Arena.global(),
					),
				),
			)
		}
	}

	private const val CHOSEN = "prismDockItemChosen:"
}
