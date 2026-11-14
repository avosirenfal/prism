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
	implementation(project(":client"))
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

// prism's own jar and every jar it runs with, side by side in one folder: what a package that brings its own Java
// runs prism from (the flake's, nix/package.nix), where an app image with a Java inside it is not wanted. Each is
// named after its group as well as itself, since two of Compose's have one file name; the natives of platforms other
// than Linux are left out, a Linux package having no use for them.
tasks.register<Sync>("installJars") {
	val elsewhere = Regex("natives-(windows|macos)")

	from(tasks.jar)
	from(configurations.runtimeClasspath) {
		exclude { elsewhere.containsMatchIn(it.name) }
		eachFile {
			// A jar from a repository is kept by Gradle under its group's name: files-2.1/GROUP/NAME/VERSION/HASH/.
			val folders = file.invariantSeparatorsPath.split('/')
			val group = folders.getOrNull(folders.indexOf("files-2.1") + 1)

			if ("files-2.1" in folders && group != null)
				name = "$group-$name"
		}
	}
	into(layout.buildDirectory.dir("install/lib"))
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
