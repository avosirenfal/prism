// The desktop prism runs on, behind interfaces a platform implements: what type a file is (MIME), which applications
// open which types and which is the default, opening files and folders with them as processes of their own, the
// terminal, the well-known folders and drives, and what a file's properties are. Linux's is freedesktop.org's
// (shared-mime-info, desktop entries, mimeapps.list); macOS's and Windows' are theirs to add.
plugins {
	id("prism.kotlin-jvm")
}

dependencies {
	// statx(2), for when a file was made, which the JVM does not say on Linux.
	implementation(project(":linux"))
}
