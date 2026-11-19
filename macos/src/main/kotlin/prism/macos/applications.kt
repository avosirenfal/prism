package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.nio.file.Path
import prism.ffi.ByValue
import prism.ffi.Library
import prism.ffi.StructType

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
	/** How Launch Services runs it: in the Dock, as an accessory, or with no window at all. */
	val policy: ActivationPolicy,
	/** The program that is it (`Contents/MacOS/Safari`), where it has one. */
	val executable: Path? = null,
)

/**
 * How Launch Services runs an application, as its Info.plist says: AppKit's activation policies
 * (`NSApplicationActivationPolicy`), which are what the two keys that say it come to. A bundle that says neither is
 * [Regular].
 */
enum class ActivationPolicy {
	/** An ordinary application, in the Dock, with a menu bar and windows. */
	Regular,

	/**
	 * In no Dock and with no menu bar of its own, but with windows when it wants them (`LSUIElement`): an application
	 * of the menu bar's (Rectangle), or prism's daemon; and an application's helpers, which have none.
	 */
	Accessory,

	/** With no window at all, never brought forward (`LSBackgroundOnly`): a faceless helper. */
	Prohibited,
	;

	companion object {
		/**
		 * The policy of a bundle that says it is an accessory ([uiElement]) and that it runs in the background alone
		 * ([backgroundOnly]): where it says both, the stricter, since what has no window cannot show one.
		 */
		fun of(uiElement: Boolean, backgroundOnly: Boolean): ActivationPolicy =
			when {
				backgroundOnly -> Prohibited
				uiElement -> Accessory
				else -> Regular
			}
	}
}

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

			// A key that says yes is written as a Boolean (`<true/>`), and in older bundles and Chromium's
			// helpers as a string (`<string>1</string>`): each is read as Foundation reads it.
			fun flag(key: String) =
				info[key].orNull()?.let {
					when {
						it.isA(NSNumber) -> NSNumber.boolValue(it)
						it.isA(NSString) -> NSString.boolValue(it)
						else -> false
					}
				} == true

			Bundle(
				path = path,
				identifier = NSBundle.bundleIdentifier(bundle).text(),
				name = (displayNameOf(path) ?: path.fileName.toString()).removeSuffix(".app"),
				category = info["LSApplicationCategoryType"].text(),
				opens = words("LSItemContentTypes"),
				opensMime = words("CFBundleTypeMIMETypes"),
				policy = ActivationPolicy.of(flag("LSUIElement"), flag("LSBackgroundOnly")),
				executable = NSBundle.executablePath(bundle).text()?.let(Path::of),
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

	/**
	 * The application whose bundle says it is [identifier] (`com.mitchellh.ghostty`), wherever it is, as Launch
	 * Services knows it: as `open -b` finds it. Null where Launch Services knows of none.
	 */
	fun withIdentifier(identifier: String): Path? =
		ObjC.pooled {
			NSWorkspace
				.urlForApplicationWithBundleIdentifier(
					workspace,
					nsString(identifier),
				).filePath()
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

				pngOf(drawn)
			}
		}

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

/** `NSBitmapImageFileTypePNG`. */
private const val PNG = 4L

/** A `CGImage` as a PNG's bytes; null where it comes to none. Called inside a pool. */
internal fun pngOf(image: MemorySegment): ByteArray? {
	val bitmap =
		NSBitmapImageRep.initWithCGImage(
			NSObject.alloc.on(NSBitmapImageRep.cls),
			image,
		)

	try {
		val png =
			NSBitmapImageRep.representationUsingType(
				bitmap,
				PNG,
				NSDictionary.dictionary(),
			)

		val length = NSData.length(png)

		return NSData
			.bytes(png)
			.reinterpret(length)
			.toArray(JAVA_BYTE)
			.takeIf { length > 0 }
	} finally {
		NSObject.release(bitmap)
	}
}

/*
 * Quick Look's picture of a file: what Finder shows for a document, a video, a picture of a kind only the system reads
 * (HEIC, RAW). Asked of Quick Look's own C function, which answers as it returns, with an image or with none.
 */

/** A `CGSize`, which Quick Look is passed by value: width, then height. */
object CgSize : StructType {
	override val layout: MemoryLayout = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE)
}

internal fun interface QlThumbnailImageCreate {
	operator fun invoke(
		allocator: MemorySegment,
		url: MemorySegment,
		@ByValue(CgSize::class) size: MemorySegment,
		options: MemorySegment,
	): MemorySegment
}

/** Quick Look's thumbnails. */
object QuickLook {
	private val create by Library
		.at("/System/Library/Frameworks/QuickLook.framework/QuickLook")
		.binding<QlThumbnailImageCreate>("QLThumbnailImageCreate")

	/** The picture Quick Look makes of the file at [path], [pixels] across at most, as a PNG; null where it makes none. */
	fun png(path: Path, pixels: Int): ByteArray? =
		ObjC.pooled {
			Arena.ofConfined().use { arena ->
				val size =
					arena.allocate(CgSize.layout).also {
						it.set(JAVA_DOUBLE, 0, pixels.toDouble())
						it.set(JAVA_DOUBLE, 8, pixels.toDouble())
					}

				// An NSURL is a CFURL, as Foundation's objects are CoreFoundation's.
				val image =
					create(MemorySegment.NULL, fileUrl(path).address, size, MemorySegment.NULL)

				if (image.address() == 0L)
					return@pooled null

				try {
					pngOf(image)
				} finally {
					CoreFoundation.release(image)
				}
			}
		}
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
