// The file explorer: the browser (the picker's own listing, sidebar, search and menus) as an application of its own,
// opening what it is asked to with the desktop's applications, with properties, "open with", preferences and a window
// to rebind every key in. Run on its own (prism explore), or by the daemon, as a picker is.
plugins {
	id("prism.compose")
}

dependencies {
	api(project(":browser"))
	// The launcher's window is one of the explorer's, opened and closed as the rest are.
	api(project(":launcher"))
	// On a Mac, prism in the Dock while it has a window of the usual kind.
	implementation(project(":macos"))

	@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
	testImplementation(compose.uiTest)
}

// prism's icon, the one the Linux package installs (nix/prism.svg), kept beside the classes for the Dock to show.
tasks.processResources {
	from(rootProject.layout.projectDirectory.file("nix/prism.svg")) { into("prism") }
}

tasks.test {
	// `-Pexplorer.screenshots=DIR` has the screenshot tests write their renders there.
	providers.gradleProperty("explorer.screenshots").orNull?.let {
		systemProperty("explorer.screenshots", it)
	}
}
