// What the user has set, the same for every window: the sidebar's pinned places, key and mouse bindings, the terminal.
// Kept in one file, read once and written whole as JSON at each change, which the store hands to every window, so a
// change made in one reaches every other. Nothing of a toolkit or a platform: the daemon holds it, and every window
// it opens is given it.
plugins {
	id("prism.kotlin-jvm")
	id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
	api(libs.kotlinx.serialization.json)
	api(libs.kotlinx.coroutines.core)
	// The terminal chosen is the desktop's description of one (prism.desktop.TerminalChoice), written here.
	api(project(":desktop"))
	// The preferences are a value kept in a file, handed to every window as it changes (prism.shared.Stored).
	api(project(":shared"))
}
