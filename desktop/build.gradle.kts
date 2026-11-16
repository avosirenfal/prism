// The desktop prism runs on, behind interfaces a platform implements: what type a file is (MIME), which applications
// open which types and which is the default, opening files and folders with them as processes of their own, the
// terminal, the well-known folders and drives, and what a file's properties are. Linux's is freedesktop.org's
// (shared-mime-info, desktop entries, mimeapps.list); macOS's is Launch Services' and uniform types'; Windows' is
// still to add.
plugins {
	id("prism.kotlin-jvm")
}

dependencies {
	// Which platform this is, and so which desktop; and the XDG Base Directory folders freedesktop's is under.
	api(project(":platform"))
	// statx(2), for when a file was made, which the JVM does not say on Linux.
	implementation(project(":linux"))
	// macOS's own: uniform types, Launch Services, NSWorkspace, the system's trash, the volumes.
	implementation(project(":macos"))
	// `mimeapps.list`, changed as it is at that moment, and state shared between threads.
	implementation(project(":shared"))
	// `zfs list -j`, read for what each dataset holds.
	implementation(libs.kotlinx.serialization.json)
}
