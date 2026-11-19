// The launcher: a field, and under it what it finds as it is typed into, the applications installed first and then
// the index's files, each started or opened with Enter. An immutable state and a pure reducer, as the browser's are,
// drawn with the browser's own theme and controls. Shown by the daemon (prism launcher), as an explorer's window is.
plugins {
	id("prism.compose")
}

dependencies {
	api(project(":browser"))
	// The calculator's language, which a module of the launcher's works out what is typed in.
	implementation(project(":calc"))
	// A Mac's launcher window over every other, given the keyboard and giving it back.
	implementation(project(":macos"))

	@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
	testImplementation(compose.uiTest)
}

tasks.test {
	// `-Plauncher.screenshots=DIR` has the screenshot tests write their renders there.
	providers.gradleProperty("launcher.screenshots").orNull?.let {
		systemProperty("launcher.screenshots", it)
	}
}
