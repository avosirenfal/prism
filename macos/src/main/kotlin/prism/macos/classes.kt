package prism.macos

import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout

/*
 * The classes prism sends messages to, and every method of each that it sends: the whole of what prism asks of
 * Objective-C, in one place. A method is called through its value here and no other way, and a test on a Mac compares
 * each with the runtime's own account of it.
 */

/** An `NSRect`: its origin's x and y, then its width and height, each a `CGFloat`. */
val NS_RECT: MemoryLayout =
	MemoryLayout.structLayout(
		ValueLayout.JAVA_DOUBLE,
		ValueLayout.JAVA_DOUBLE,
		ValueLayout.JAVA_DOUBLE,
		ValueLayout.JAVA_DOUBLE,
	)

private val Rect = Struct(NS_RECT)
internal val Point = Struct(NsPoint.layout)

data object NSObject : ObjCClass("NSObject") {
	val alloc = static("alloc", Obj)
	val init = message("init", Obj)
	val release = message("release", NoValue)
	val autorelease = message("autorelease", Obj)
	val isKindOfClass = message("isKindOfClass:", Obj, Bool)
}

data object NSString : ObjCClass("NSString") {
	val stringWithUTF8String = static("stringWithUTF8String:", Ptr, Obj)
	val utf8String = message("UTF8String", Ptr)

	/** Whether it says yes: "1", "YES", "true", as `boolValue` reads one (a digit 1–9, a Y or a T, first). */
	val boolValue = message("boolValue", Bool)
}

data object NSArray : ObjCClass("NSArray") {
	val count = message("count", Int64)
	val objectAtIndex = message("objectAtIndex:", Int64, Obj)
}

data object NSSet : ObjCClass("NSSet") {
	val allObjects = message("allObjects", Obj)
}

data object NSDictionary : ObjCClass("NSDictionary") {
	val dictionary = static("dictionary", Obj)
	val objectForKey = message("objectForKey:", Obj, Obj)
}

data object NSNumber : ObjCClass("NSNumber") {
	val boolValue = message("boolValue", Bool)
	val longLongValue = message("longLongValue", Int64)
}

data object NSData : ObjCClass("NSData") {
	val dataWithBytes = static("dataWithBytes:length:", Ptr, Int64, Obj)
	val length = message("length", Int64)
	val bytes = message("bytes", Ptr)
}

data object NSError : ObjCClass("NSError") {
	val localizedDescription = message("localizedDescription", Obj)
}

data object NSURL : ObjCClass("NSURL") {
	val fileURLWithPath = static("fileURLWithPath:", Obj, Obj)
	val path = message("path", Obj)
	val getResourceValue = message("getResourceValue:forKey:error:", Ptr, Obj, Ptr, Bool)
}

data object NSBundle : ObjCClass("NSBundle") {
	val bundleWithPath = static("bundleWithPath:", Obj, Obj)
	val infoDictionary = message("infoDictionary", Obj)
	val bundleIdentifier = message("bundleIdentifier", Obj)
	val executablePath = message("executablePath", Obj)
}

data object NSFileManager : ObjCClass("NSFileManager") {
	val defaultManager = static("defaultManager", Obj)
	val displayNameAtPath = message("displayNameAtPath:", Obj, Obj)
	val trashItemAtURL =
		message("trashItemAtURL:resultingItemURL:error:", Obj, Ptr, Ptr, Bool)
}

data object UTType : ObjCClass("UTType", Frameworks.uniformTypes) {
	val typeWithIdentifier = static("typeWithIdentifier:", Obj, Obj)
	val typeWithMIMEType = static("typeWithMIMEType:", Obj, Obj)
	val typeWithFilenameExtension = static("typeWithFilenameExtension:", Obj, Obj)
	val isDynamic = message("isDynamic", Bool)
	val identifier = message("identifier", Obj)
	val preferredMIMEType = message("preferredMIMEType", Obj)
	val localizedDescription = message("localizedDescription", Obj)
	val supertypes = message("supertypes", Obj)
	val tags = message("tags", Obj)
}

data object NSWorkspace : ObjCClass("NSWorkspace", Frameworks.appKit) {
	val sharedWorkspace = static("sharedWorkspace", Obj)
	val urlForApplicationToOpenContentType =
		message("URLForApplicationToOpenContentType:", Obj, Obj)
	val urlsForApplicationsToOpenContentType =
		message("URLsForApplicationsToOpenContentType:", Obj, Obj)
	val urlForApplicationToOpenURL = message("URLForApplicationToOpenURL:", Obj, Obj)
	val urlForApplicationWithBundleIdentifier =
		message("URLForApplicationWithBundleIdentifier:", Obj, Obj)
	val iconForFile = message("iconForFile:", Obj, Obj)
	val runningApplications = message("runningApplications", Obj)
}

data object NSImage : ObjCClass("NSImage", Frameworks.appKit) {
	val initWithData = message("initWithData:", Obj, Obj)
	val cgImageForProposedRect =
		message("CGImageForProposedRect:context:hints:", Ptr, Obj, Obj, Ptr)
}

data object NSBitmapImageRep : ObjCClass("NSBitmapImageRep", Frameworks.appKit) {
	val initWithCGImage = message("initWithCGImage:", Ptr, Obj)
	val representationUsingType =
		message("representationUsingType:properties:", Int64, Obj, Obj)
}

data object NSRunningApplication : ObjCClass("NSRunningApplication", Frameworks.appKit) {
	val withProcessIdentifier =
		static("runningApplicationWithProcessIdentifier:", Int32, Obj)
	val activationPolicy = message("activationPolicy", Int64)
	val processIdentifier = message("processIdentifier", Int32)
	val localizedName = message("localizedName", Obj)
	val bundleURL = message("bundleURL", Obj)
	val activateWithOptions = message("activateWithOptions:", Int64, Bool)
}

data object NSApplication : ObjCClass("NSApplication", Frameworks.appKit) {
	val sharedApplication = static("sharedApplication", Obj)
	val setActivationPolicy = message("setActivationPolicy:", Int64, Bool)
	val setApplicationIconImage = message("setApplicationIconImage:", Obj, NoValue)
	val delegate = message("delegate", Obj)
	val currentEvent = message("currentEvent", Obj)
	val activateIgnoringOtherApps = message("activateIgnoringOtherApps:", Bool, NoValue)
}

data object NSEvent : ObjCClass("NSEvent", Frameworks.appKit) {
	/** The pointer's buttons held now, a bit each: the first is the left. */
	val pressedMouseButtons = static("pressedMouseButtons", Int64)

	/** An event of the pointer's, made here: its type, where, the keys held, when, in which window, and how hard. */
	val mouseEvent =
		staticOf(
			"mouseEventWithType:location:modifierFlags:timestamp:windowNumber:context:" +
				"eventNumber:clickCount:pressure:",
			listOf(Int64, Point, Int64, Float64, Int64, Obj, Int64, Int64, Float32),
			Obj,
		)
}

data object NSProcessInfo : ObjCClass("NSProcessInfo") {
	val processInfo = static("processInfo", Obj)

	/** How long the system has been up, in seconds: what an event's time is counted in. */
	val systemUptime = message("systemUptime", Float64)
}

data object NSMutableArray : ObjCClass("NSMutableArray") {
	val array = static("array", Obj)
	val addObject = message("addObject:", Obj, NoValue)
}

data object NSDraggingItem : ObjCClass("NSDraggingItem", Frameworks.appKit) {
	val initWithPasteboardWriter = message("initWithPasteboardWriter:", Obj, Obj)
	val setDraggingFrame = message("setDraggingFrame:contents:", Rect, Obj, NoValue)
}

data object NSMenu : ObjCClass("NSMenu", Frameworks.appKit) {
	val initWithTitle = message("initWithTitle:", Obj, Obj)
	val addItemWithTitle =
		message("addItemWithTitle:action:keyEquivalent:", Obj, Sel, Obj, Obj)
	val addItem = message("addItem:", Obj, NoValue)
}

data object NSMenuItem : ObjCClass("NSMenuItem", Frameworks.appKit) {
	val separatorItem = static("separatorItem", Obj)
	val setTarget = message("setTarget:", Obj, NoValue)
	val tag = message("tag", Int64)
	val setTag = message("setTag:", Int64, NoValue)
}

data object NSResponder : ObjCClass("NSResponder", Frameworks.appKit) {
	val nextResponder = message("nextResponder", Obj)
	val rightMouseDown = message("rightMouseDown:", Obj, NoValue)
}

data object NSView : ObjCClass("NSView", Frameworks.appKit) {
	val initWithFrame = message("initWithFrame:", Rect, Obj)
	val beginDraggingSession =
		message("beginDraggingSessionWithItems:event:source:", Obj, Obj, Obj, Obj)
}

data object NSWindow : ObjCClass("NSWindow", Frameworks.appKit) {
	val initWithContentRect =
		message("initWithContentRect:styleMask:backing:defer:", Rect, Int64, Int64, Bool, Obj)
	val setContentView = message("setContentView:", Obj, NoValue)
	val contentView = message("contentView", Obj)
	val windowNumber = message("windowNumber", Int64)
	val setLevel = message("setLevel:", Int64, NoValue)
	val setCollectionBehavior = message("setCollectionBehavior:", Int64, NoValue)
	val setHidesOnDeactivate = message("setHidesOnDeactivate:", Bool, NoValue)
	val setReleasedWhenClosed = message("setReleasedWhenClosed:", Bool, NoValue)
	val center = message("center", NoValue)
	val close = message("close", NoValue)
}

data object NSPanel : ObjCClass("NSPanel", Frameworks.appKit) {
	val setFloatingPanel = message("setFloatingPanel:", Bool, NoValue)
	val setBecomesKeyOnlyIfNeeded = message("setBecomesKeyOnlyIfNeeded:", Bool, NoValue)
}
