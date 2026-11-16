// macOS through the FFM API, as the linux module is Linux: libSystem's calls (the volumes mounted, a volume's own id),
// and the frameworks' C functions. Every handle is made the first time it is called, so the module loads on any
// platform and reaches macOS only where it runs there; its tests that ask the system run on a Mac alone (CI's).
plugins {
	id("prism.kotlin-jvm")
}

dependencies {
	// What its calls answer with: a mount, a device.
	api(project(":platform"))
	// Native functions as typed Kotlin functions.
	api(project(":ffi"))
}
