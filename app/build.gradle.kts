import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// prism itself: the command line (prism.app), the picker's window, the index and its watcher. Compose Desktop's
// application packaging, not Gradle's `application` plugin: Compose ships two different jars of one file name
// (androidx's runtime-saveable-desktop and JetBrains' shim of it), which Gradle's distribution cannot hold side by
// side and Compose's renames apart. `./gradlew :app:run --args="..."` runs it; `createDistributable` makes the
// app image (with its own JRE) under build/compose/binaries.
plugins {
	id("prism.compose")
	// The app declares D-Bus messages of its own (its tests' now, the portal backend's to come).
	id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
	implementation(project(":cli"))
	implementation(project(":picker"))
	implementation(project(":index"))
	implementation(project(":daemon"))
	implementation(project(":portal"))

	testImplementation(testFixtures(project(":dbus")))
}

/** The JDK the modules are compiled for: Compose runs and packages the app on it, whatever JDK runs Gradle. */
val toolchainJdk =
	javaToolchains.launcherFor {
		languageVersion =
			JavaLanguageVersion.of(libs.versions.jdk.get())
	}

compose.desktop {
	application {
		mainClass = "prism.app.MainKt"
		javaHome =
			toolchainJdk
				.get()
				.metadata.installationPath.asFile.absolutePath
		jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
		nativeDistributions {
			targetFormats(TargetFormat.AppImage)
			packageName = "prism"
			packageVersion = "0.1.0"
		}
	}
}

// AWT assumes the window manager reparents its windows into a frame. Wayland compositors and tiling window
// managers do not, so AWT never learns the window's real size; this tells it so.
val nonReparenting = "_JAVA_AWT_WM_NONREPARENTING"

tasks.withType<JavaExec>().configureEach {
	if (!providers.environmentVariable(nonReparenting).isPresent)
		environment(nonReparenting, "1")
}
