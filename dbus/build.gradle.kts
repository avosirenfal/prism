// D-Bus: a connection over the bus's Unix socket (the handshake and the bus's Hello made as it opens), messages as
// immutable data, and bodies encoded and decoded by kotlinx.serialization, their signatures worked out from the
// Kotlin types so none is written by hand. It declares no messages of its own beyond the bus's plumbing: what to
// call and what to listen for is the user's to declare.
plugins {
	id("barion.kotlin-jvm")
	id("org.jetbrains.kotlin.plugin.serialization")
	`java-test-fixtures`
}

dependencies {
	api(libs.kotlinx.serialization.core)
	api(libs.kotlinx.coroutines.core)

	testImplementation(libs.kotlinx.coroutines.test)
	// The test fixtures start a private bus for this module's tests and the portal's.
	testFixturesImplementation(platform(libs.junit.bom))
	testFixturesImplementation(libs.junit.jupiter)
}
