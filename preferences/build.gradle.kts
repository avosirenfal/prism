// What the user has set, the same for every window: the sidebar's pinned places, key and mouse bindings, the terminal.
// Kept in one file, read and written as JSON, and watched, so a change made in one window (or by hand) reaches every
// other. Nothing of a toolkit or a platform: the daemon holds it, and every window it opens reads it.
plugins {
	id("prism.kotlin-jvm")
	id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
	api(libs.kotlinx.serialization.json)
	api(libs.kotlinx.coroutines.core)
	// The terminal chosen is the desktop's description of one (prism.desktop.TerminalChoice), written here.
	api(project(":desktop"))
}
