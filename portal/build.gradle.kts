// The desktop portal as barion speaks to it, declared over the dbus module: the appearance settings for now (the
// colour scheme, and its changes), and the file chooser barion will serve.
plugins {
	id("barion.kotlin-jvm")
	id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
	api(project(":dbus"))

	testImplementation(libs.kotlinx.coroutines.test)
}

dependencies {
	testImplementation(testFixtures(project(":dbus")))
}
