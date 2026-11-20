// Small pictures of files: the thumbnails freedesktop.org's standard keeps, read and written; what makes one (a
// picture decoded, a preview a file carries, a program run: ffmpeg's, or one the user names); which are kept, by how
// much and how lately each is used; and the queue that makes what a window shows first. It draws nothing itself: what
// decodes a picture (Skia, in the browser) is given to it.
plugins {
	id("prism.kotlin-jvm")
	id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
	// Which platform this is, and prism's own folders on it.
	api(project(":platform"))
	// What is kept in files, and state shared between threads; how much a thing is used, and how lately.
	api(project(":shared"))
	// The settings, as the preferences write them.
	api(libs.kotlinx.serialization.json)
	// What is made is waited for, and no longer made once nobody waits.
	api(libs.kotlinx.coroutines.core)
	testImplementation(libs.kotlinx.coroutines.test)
}
