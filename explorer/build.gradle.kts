// The file explorer: the browser (the picker's own listing, sidebar, search and menus) as an application of its own,
// opening what it is asked to with the desktop's applications, with properties, "open with", preferences and a window
// to rebind every key in. Run on its own (prism explore), or by the daemon, as a picker is.
plugins {
	id("prism.compose")
}

dependencies {
	api(project(":browser"))

	@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
	testImplementation(compose.uiTest)
}
