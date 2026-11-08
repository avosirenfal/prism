// The file picker: an immutable model of what it shows and a pure composable view of it. Reads nothing itself but
// through picker.source, the one place a model is made from a real directory.
plugins {
	id("barion.compose")
}

tasks.test {
	// `-Ppicker.screenshots=DIR` has the screenshot test write its renders there.
	providers.gradleProperty("picker.screenshots").orNull?.let {
		systemProperty("picker.screenshots", it)
	}
}
