// The index of the files and folders on the machine: how it is read (the scanners), what it skips, how it is held in
// memory and on disk, and how it is searched. Nothing here watches the disk: the watcher is a module of its own, and
// the daemon module runs the two together.
plugins {
	id("barion.kotlin-jvm")
}

dependencies {
	api(project(":linux"))
	// zstd, for the index's file: the JNI binding, which carries the native library for each platform in its jar.
	implementation(libs.zstd)
}

tasks.test {
	// `-Pindex.benchmark=N` runs the search benchmark over a made-up index of N entries, which is otherwise skipped.
	providers.gradleProperty("index.benchmark").orNull?.let {
		systemProperty("index.benchmark", it)
	}
	// `-Pparser.seed=N -Pparser.texts=N` has the parser's fuzzing read other random texts, and more of them.
	for (property in listOf("parser.seed", "parser.texts"))
		providers.gradleProperty(property).orNull?.let { systemProperty(property, it) }
}
