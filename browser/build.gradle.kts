// Browsing files, as the file picker and the explorer both do: an immutable model of what is shown, a pure reducer
// from one state to the next, a composable view of it, and a session that runs the effects the reducer asks for
// against a disk (browser.source), an index, and the desktop.
plugins {
	id("prism.compose")
}

tasks.test {
	// `-Pbrowser.screenshots=DIR` has the screenshot tests write their renders there.
	providers.gradleProperty("browser.screenshots").orNull?.let {
		systemProperty("browser.screenshots", it)
	}
	// `-Pbrowser.display` runs the tests that need real windows on a display (a drag from one to another), which the
	// rest, drawn headless, do not: run under one, as `xvfb-run ./gradlew :browser:test -Pbrowser.display`.
	if (providers.gradleProperty("browser.display").isPresent) {
		jvmArgs("-Djava.awt.headless=false")
		systemProperty("browser.display", "true")
		// The display is this run's, read as an input: the environment a task was first configured with is otherwise
		// kept with the configuration, and would name a display that is gone.
		for (name in listOf("DISPLAY", "XAUTHORITY", "WAYLAND_DISPLAY"))
			providers.environmentVariable(name).orNull?.let { environment(name, it) }
	}
}

dependencies {
	// The index the picker searches (Ctrl+Shift+F), and its search language.
	api(project(":index"))
	// The desktop's file types, applications and places, and the user's preferences (pins, bindings).
	api(project(":desktop"))
	api(project(":preferences"))
	// A session's states and outcome are coroutines' flows and deferreds.
	api(libs.kotlinx.coroutines.core)
	// AWT's event thread as a dispatcher, which headless drawing draws on, as a window does.
	implementation(libs.kotlinx.coroutines.swing)

	// Compose's own test harness: the picker composed headless, and pressed and typed at as a user would.
	@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
	testImplementation(compose.uiTest)
}
