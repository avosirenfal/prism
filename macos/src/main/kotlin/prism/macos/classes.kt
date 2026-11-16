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

data object NSObject : ObjCClass("NSObject") {
	val alloc = static("alloc", Obj)
	val release = message("release", NoValue)
	val isKindOfClass = message("isKindOfClass:", Obj, Bool)
}

data object NSString : ObjCClass("NSString") {
	val stringWithUTF8String = static("stringWithUTF8String:", Ptr, Obj)
	val utf8String = message("UTF8String", Ptr)
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
}

data object NSResponder : ObjCClass("NSResponder", Frameworks.appKit) {
	val nextResponder = message("nextResponder", Obj)
	val rightMouseDown = message("rightMouseDown:", Obj, NoValue)
}

data object NSView : ObjCClass("NSView", Frameworks.appKit) {
	val initWithFrame = message("initWithFrame:", Rect, Obj)
}

data object NSWindow : ObjCClass("NSWindow", Frameworks.appKit) {
	val initWithContentRect =
		message("initWithContentRect:styleMask:backing:defer:", Rect, Int64, Int64, Bool, Obj)
	val setContentView = message("setContentView:", Obj, NoValue)
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
