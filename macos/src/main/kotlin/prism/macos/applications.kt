package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.nio.file.Path

/*
 * Applications as macOS keeps them: each a bundle (`Safari.app`), whose Info.plist says what it is called, what kind
 * of program it is and what types of document it opens; Launch Services, through NSWorkspace, says which opens a type
 * and which opens it by default, and is told a new default; NSWorkspace draws each one's icon, and lists those running.
 */

/** An application's bundle, as its Info.plist says it. */
data class Bundle(
	val path: Path,
	/** `com.apple.Safari`; null for a bundle that does not say. */
	val identifier: String?,
	/** What Finder calls it, in the user's language: "Safari". */
	val name: String,
	/** What kind of program it says it is (`public.app-category.productivity`), where it says. */
	val category: String?,
	/** The uniform types of the documents it says it opens. */
	val opens: List<String>,
	/** The MIME types of the documents it says it opens, where it names them so. */
	val opensMime: List<String>,
	/** Whether it runs without a window or a place in the Dock (`LSUIElement`, `LSBackgroundOnly`): no one starts it. */
	val background: Boolean,
)

/** An application running, as NSWorkspace lists it. */
data class Running(
	val pid: Int,
	val name: String,
	val bundle: Path?,
)

internal fun interface LsSetDefaultRoleHandler {
	operator fun invoke(
		contentType: MemorySegment,
		roles: Int,
		bundleIdentifier: MemorySegment,
	): Int
}

/** Launch Services and the bundles it knows. */
object Applications {
	private val workspace get() = NSWorkspace.sharedWorkspace()

	/** The bundle at [path], as its Info.plist says it; null where it is no bundle. */
	fun bundle(path: Path): Bundle? =
		ObjC.pooled {
			val bundle =
				NSBundle.bundleWithPath(nsString(path.toString())).orNull() ?: return@pooled null
			val info = NSBundle.infoDictionary(bundle)

			val types =
				info["CFBundleDocumentTypes"]
					.takeIf {
						it.isA(
							NSArray,
						)
					}?.elements()
					.orEmpty()

			fun words(key: String) =
				types.flatMap { type ->
					type[key]
						.takeIf { it.isA(NSArray) }
						?.elements()
						.orEmpty()
						.mapNotNull { it.text() }
				}

			fun flag(key: String) =
				info[key].orNull()?.let { it.isA(NSNumber) && NSNumber.boolValue(it) } == true

			Bundle(
				path = path,
				identifier = NSBundle.bundleIdentifier(bundle).text(),
				name = (displayNameOf(path) ?: path.fileName.toString()).removeSuffix(".app"),
				category = info["LSApplicationCategoryType"].text(),
				opens = words("LSItemContentTypes"),
				opensMime = words("CFBundleTypeMIMETypes"),
				background = flag("LSUIElement") || flag("LSBackgroundOnly"),
			)
		}

	/** The application that opens files of the type [identifier] by default; null where none does. */
	fun defaultFor(identifier: String): Path? =
		ObjC.pooled {
			typeOf(identifier)?.let {
				NSWorkspace.urlForApplicationToOpenContentType(workspace, it).filePath()
			}
		}

	/** Every application that says it opens files of the type [identifier]. */
	fun forType(identifier: String): List<Path> =
		ObjC.pooled {
			typeOf(identifier)
				?.let {
					NSWorkspace
						.urlsForApplicationsToOpenContentType(
							workspace,
							it,
						).elements()
						.mapNotNull(Id::filePath)
				}.orEmpty()
		}

	/** The application that opens the file at [path] by default; null where none does. */
	fun defaultForFile(path: Path): Path? =
		ObjC.pooled {
			NSWorkspace.urlForApplicationToOpenURL(workspace, fileUrl(path)).filePath()
		}

	private val setDefaultHandler by
		Frameworks.coreServices.binding<LsSetDefaultRoleHandler>(
			"LSSetDefaultRoleHandlerForContentType",
		)

	/** `kLSRolesAll`: as viewer, editor and shell alike. */
	private const val ALL_ROLES = -1

	/** The application [bundle] made the default for the type [identifier]; why not, where it could not be. */
	fun setDefault(identifier: String, bundle: String): String? =
		ObjC.pooled {
			val status =
				setDefaultHandler(nsString(identifier).address, ALL_ROLES, nsString(bundle).address)

			if (status == 0)
				null
			else
				"Launch Services refused it (error $status)."
		}

	/** The icon of the file or application at [path], drawn [pixels] across, as a PNG; null where none could be drawn. */
	fun iconPng(path: Path, pixels: Int): ByteArray? =
		ObjC.pooled {
			Arena.ofConfined().use { arena ->
				val image =
					NSWorkspace.iconForFile(workspace, nsString(path.toString())).orNull()
						?: return@pooled null
				val rect = arena.allocate(NS_RECT)

				rect.set(JAVA_DOUBLE, 16, pixels.toDouble())
				rect.set(JAVA_DOUBLE, 24, pixels.toDouble())

				val drawn = NSImage.cgImageForProposedRect(image, rect, Id.NIL, Id.NIL)

				if (drawn.address() == 0L)
					return@pooled null

				val bitmap =
					NSBitmapImageRep.initWithCGImage(
						NSObject.alloc.on(NSBitmapImageRep.cls),
						drawn,
					)

				try {
					val png =
						NSBitmapImageRep.representationUsingType(
							bitmap,
							PNG,
							NSDictionary.dictionary(),
						)

					val length = NSData.length(png)

					NSData
						.bytes(png)
						.reinterpret(length)
						.toArray(JAVA_BYTE)
						.takeIf { length > 0 }
				} finally {
					NSObject.release(bitmap)
				}
			}
		}

	/** `NSBitmapImageFileTypePNG`. */
	private const val PNG = 4L

	/** The applications running now that the user started (with a place in the Dock). */
	fun running(): List<Running> =
		ObjC.pooled {
			NSWorkspace
				.runningApplications(workspace)
				.elements()
				.filter { NSRunningApplication.activationPolicy(it) == REGULAR }
				.map {
					Running(
						pid = NSRunningApplication.processIdentifier(it),
						name = NSRunningApplication.localizedName(it).text().orEmpty(),
						bundle = NSRunningApplication.bundleURL(it).filePath(),
					)
				}
		}

	/** `NSApplicationActivationPolicyRegular`. */
	private const val REGULAR = 0L

	/** The application running as [pid] brought forward, every window of it; whether it was. */
	fun activate(pid: Int): Boolean =
		ObjC.pooled {
			NSRunningApplication
				.withProcessIdentifier(pid)
				.orNull()
				?.let { NSRunningApplication.activateWithOptions(it, ALL_WINDOWS) } == true
		}

	/** `NSApplicationActivateAllWindows`. */
	private const val ALL_WINDOWS = 1L

	private fun typeOf(identifier: String): Id? =
		UTType.typeWithIdentifier(nsString(identifier)).orNull()
}

/** What Finder calls the file or folder at [path], in the user's language ("Macintosh HD", "Schreibtisch"). */
fun displayNameOf(path: Path): String? =
	ObjC.pooled {
		NSFileManager
			.displayNameAtPath(
				NSFileManager.defaultManager(),
				nsString(path.toString()),
			).text()
	}

/*
 * The trash, as the system keeps it: NSFileManager moves an item to the trash of its volume (`~/.Trash`, or a volume's
 * own `.Trashes`), under a name free there, and says where it went.
 */

/** What moving an item to the trash came to. */
sealed interface Binned {
	/** It is in the trash, at [at]. */
	data class In(
		val at: Path,
	) : Binned

	/** It is where it was, because [why]. */
	data class Refused(
		val why: String,
	) : Binned
}

/** The file or folder at [path] moved to its volume's trash. */
fun trashItem(path: Path): Binned =
	ObjC.pooled {
		Arena.ofConfined().use { arena ->
			val went = arena.allocate(ADDRESS)
			val error = arena.allocate(ADDRESS)

			if (NSFileManager.trashItemAtURL(
					NSFileManager.defaultManager(),
					fileUrl(path),
					went,
					error,
				)
			)
				Id(went.get(ADDRESS, 0L)).filePath()?.let(Binned::In)
					?: Binned.Refused("the system did not say where it went")
			else
				Binned.Refused(errorOf(error) ?: "the system refused")
		}
	}

/*
 * The appearance the user has chosen: dark where the global domain's `AppleInterfaceStyle` is `Dark`, which is what it
 * is while an automatic appearance is dark too. Read through CFPreferences, synchronised first, so a change is seen.
 */

/** Whether the user's appearance is dark, now. */
fun prefersDark(): Boolean =
	ObjC.pooled {
		CoreFoundation.preference(
			"AppleInterfaceStyle",
			Frameworks.coreFoundation.constant("kCFPreferencesAnyApplication"),
		) { style -> style.orNull()?.takeIf { it.isA(NSString) }?.text() == "Dark" }
	}

/*
 * Spotlight's key, as the system keeps it: symbolic hot key 64 ("Show Spotlight search") among
 * `com.apple.symbolichotkeys`' `AppleSymbolicHotKeys`, whether it is on and which key it is (its parameters: the
 * character, the key's code, and the modifiers as NSEvent's flags). Where it says nothing, it is on, and ⌘Space.
 */

/** NSEvent's modifier flags, as the symbolic hot keys write them, by Carbon's. */
private val EVENT_FLAGS =
	mapOf(
		HotKeyModifier.Shift to (1L shl 17),
		HotKeyModifier.Control to (1L shl 18),
		HotKeyModifier.Option to (1L shl 19),
		HotKeyModifier.Command to (1L shl 20),
	)

/** Whether Spotlight has [key] with [modifiers] for itself: its "Show Spotlight search" is on, and is that key. */
fun spotlightHolds(key: Int, modifiers: Set<HotKeyModifier>): Boolean =
	ObjC.pooled {
		CoreFoundation.preference(
			"AppleSymbolicHotKeys",
			nsString("com.apple.symbolichotkeys"),
		) { all ->
			val spotlight =
				all
					.orNull()
					?.takeIf { it.isA(NSDictionary) }
					?.get("64")
					?.orNull()
			val on = spotlight?.get("enabled")?.orNull()?.let(NSNumber.boolValue::invoke) ?: true
			val parameters =
				spotlight
					?.get("value")
					?.orNull()
					?.get("parameters")
					?.orNull()
					?.elements()
					?.map(NSNumber.longLongValue::invoke)
			val held = parameters?.getOrNull(1) ?: SPACE
			val flags = parameters?.getOrNull(2) ?: EVENT_FLAGS.getValue(HotKeyModifier.Command)

			on && held == key.toLong() && flags == modifiers.sumOf { EVENT_FLAGS.getValue(it) }
		}
	}

/** `kVK_Space`. */
private const val SPACE = 0x31L

/** The page of System Settings where Spotlight's key is turned off: the keyboard's, whose Keyboard Shortcuts… has it. */
const val KEYBOARD_SETTINGS =
	"x-apple.systempreferences:com.apple.Keyboard-Settings.extension"
