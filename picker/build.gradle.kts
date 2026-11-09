// The file picker: an immutable model of what it shows, a pure reducer from one state to the next, a composable view
// of it, and a session that runs the effects the reducer asks for against a disk (picker.source).
plugins {
	id("barion.compose")
}

tasks.test {
	// `-Ppicker.screenshots=DIR` has the screenshot test write its renders there.
	providers.gradleProperty("picker.screenshots").orNull?.let {
		systemProperty("picker.screenshots", it)
	}
}

dependencies {
	// A session's states and outcome are coroutines' flows and deferreds.
	api(libs.kotlinx.coroutines.core)
	// AWT's event thread as a dispatcher, which headless drawing draws on, as a window does.
	implementation(libs.kotlinx.coroutines.swing)

	// Compose's own test harness: the picker composed headless, and pressed and typed at as a user would.
	@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
	testImplementation(compose.uiTest)
}
