import prism.libs

// A module with composables: the Compose compiler over its sources, Compose Desktop for the host and Material 3,
// and tests that compose and draw headless (skiko's software renderer, AWT without a display).
plugins {
	id("prism.kotlin-jvm")
	id("org.jetbrains.compose")
	id("org.jetbrains.kotlin.plugin.compose")
}

// What replaces it is other artifacts, which the Nix build's list of what Gradle may fetch (nix/deps.json) does
// not hold: changed with that list, not apart from it.
dependencies {
	@Suppress("DEPRECATION")
	val desktop = compose.desktop.currentOs

	implementation(desktop)
	implementation(libs.library("compose-material3"))
}

tasks.withType<Test>().configureEach {
	jvmArgs(
		"-Dskiko.renderApi=SOFTWARE",
		"-Djava.awt.headless=true",
	)
}
