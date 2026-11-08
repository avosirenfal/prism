import barion.libs

// A module with composables: the Compose compiler over its sources, Compose Desktop for the host and Material 3,
// and tests that compose and draw headless (skiko's software renderer, AWT without a display).
plugins {
	id("barion.kotlin-jvm")
	id("org.jetbrains.compose")
	id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
	implementation(compose.desktop.currentOs)
	implementation(libs.library("compose-material3"))
}

tasks.withType<Test>().configureEach {
	jvmArgs(
		"-Dskiko.renderApi=SOFTWARE",
		"-Djava.awt.headless=true",
	)
}
