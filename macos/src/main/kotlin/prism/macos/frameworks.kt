package prism.macos

import java.lang.foreign.MemorySegment
import prism.ffi.Library

/*
 * The libraries macOS's own functions are in, each loaded the first time something of it is asked for, so that this
 * module loads on any platform and reaches macOS only where it runs there. Every function is bound to an interface
 * that is its signature (the ffi module), declared beside what uses it.
 */
object Frameworks {
	/** libSystem: libc, libdispatch and the rest, as the process has them already. */
	val system: Library = Library.system
	val objc: Library = Library.at("/usr/lib/libobjc.A.dylib")
	val foundation: Library = framework("Foundation")
	val appKit: Library = framework("AppKit")
	val coreFoundation: Library = framework("CoreFoundation")
	val coreServices: Library = framework("CoreServices")
	val uniformTypes: Library = framework("UniformTypeIdentifiers")
	val carbon: Library = framework("Carbon")

	private fun framework(name: String): Library =
		Library.at("/System/Library/Frameworks/$name.framework/$name")
}

/*
 * CoreFoundation's functions that more than one file here calls.
 */

internal fun interface CfRelease {
	operator fun invoke(value: MemorySegment)
}

internal fun interface CfPreferencesSynchronize {
	operator fun invoke(application: MemorySegment): Boolean
}

internal fun interface CfPreferencesCopyValue {
	operator fun invoke(key: MemorySegment, application: MemorySegment): MemorySegment
}

internal object CoreFoundation {
	val release by Frameworks.coreFoundation.binding<CfRelease>("CFRelease")
	val synchronize by Frameworks.coreFoundation.binding<CfPreferencesSynchronize>(
		"CFPreferencesAppSynchronize",
	)
	val copyValue by Frameworks.coreFoundation.binding<CfPreferencesCopyValue>(
		"CFPreferencesCopyAppValue",
	)

	/** The value of [key] in the preferences of [application] (a domain's name), read anew, given to [read], and released. */
	fun <T> preference(key: String, application: Id, read: (Id) -> T): T {
		synchronize(application.address)

		val value = Id(copyValue(nsString(key).address, application.address))

		try {
			return read(value)
		} finally {
			if (!value.isNil)
				release(value.address)
		}
	}
}
