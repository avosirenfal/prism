// Compose in windows of SDL3's: native on Wayland, X11, Windows and macOS, drawn by Skia on the GPU through OpenGL
// where there is one, and on the CPU into the window's own pixels where there is not. Nothing here is prism's; it is
// meant to be lifted out whole.
plugins {
	id("prism.compose")
}

/** The platforms SDL3's and LWJGL's natives are shipped for: every one prism runs on. */
val natives =
	listOf("linux", "linux-arm64", "windows", "windows-arm64", "macos", "macos-arm64")

dependencies {
	api(platform(libs.lwjgl.bom))
	api(libs.lwjgl.core)
	api(libs.lwjgl.sdl)
	implementation(libs.lwjgl.opengl)
	api(libs.kotlinx.coroutines.core)

	for (platform in natives) {
		runtimeOnly(variantOf(libs.lwjgl.core) { classifier("natives-$platform") })
		runtimeOnly(variantOf(libs.lwjgl.sdl) { classifier("natives-$platform") })
		runtimeOnly(variantOf(libs.lwjgl.opengl) { classifier("natives-$platform") })
	}
}

// A window to look at by hand, on whatever display the shell has: `./gradlew :window:demo`.
tasks.register<JavaExec>("demo") {
	classpath = sourceSets.test.get().runtimeClasspath
	mainClass = providers.gradleProperty("demo.main").orNull ?: "prism.window.DemoKt"
	jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
	providers.gradleProperty("demo.millis").orNull?.let { systemProperty("demo.millis", it) }
	for (name in listOf(
		"DISPLAY",
		"WAYLAND_DISPLAY",
		"XDG_RUNTIME_DIR",
		"SDL_VIDEO_DRIVER",
		"PRISM_DRAWING",
	))
		providers.environmentVariable(name).orNull?.let { environment(name, it) }
}

tasks.test {
	// `-Pwindow.display` runs the tests that open real windows, on the display the shell has (X11's or Wayland's).
	if (providers.gradleProperty("window.display").isPresent) {
		systemProperty("window.display", "true")
		jvmArgs("--enable-native-access=ALL-UNNAMED")
		for (name in listOf(
			"DISPLAY",
			"XAUTHORITY",
			"WAYLAND_DISPLAY",
			"XDG_RUNTIME_DIR",
			"PRISM_DRAWING",
		))
			providers.environmentVariable(name).orNull?.let { environment(name, it) }
	}
}
