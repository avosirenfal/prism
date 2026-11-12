import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// prism itself: the command line (prism.app), the picker's and the explorer's windows, the index and its watcher. Compose Desktop's
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
	implementation(project(":browser"))
	implementation(project(":explorer"))
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

val jfr: Boolean = providers.gradleProperty("jfr").map(String::toBoolean).getOrElse(false)

tasks.withType<JavaExec>().named { it == "run" }.configureEach {
	if (jfr)
		jvmArgumentProviders += CommandLineArgumentProvider {
			listOf(
				"-XX:+UnlockDiagnosticVMOptions",
				"-XX:+DebugNonSafepoints",
				"-XX:StartFlightRecording=filename=profile.jfr,settings=profile,dumponexit=true",
			)
		}

	standardInput = System.`in`
}

compose.desktop {
	application {
		mainClass = "prism.app.MainKt"
		javaHome =
			toolchainJdk
				.get()
				.metadata.installationPath.asFile.absolutePath
		// Native access for SDL, libc and libbpf through FFM; AWT never opens a window (prism's are SDL's), which
		// headless says, and macOS has SDL's windows on the process's first thread.
		jvmArgs += listOf("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
		if (System.getProperty("os.name").startsWith("Mac"))
			jvmArgs += "-XstartOnFirstThread"
		nativeDistributions {
			targetFormats(TargetFormat.AppImage)
			packageName = "prism"
			packageVersion = "0.1.0"
		}
	}
}
