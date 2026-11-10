// The modules configure themselves through the conventions in build-logic (prism.kotlin-jvm and friends); the
// root only lints. Nothing here reaches into a module, which is what lets Gradle configure and run them in
// parallel and cache the configuration.
plugins {
	id("prism.ktlint")
}
