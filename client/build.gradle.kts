// The prism a shell starts: what one prism asks another and how (the session's prism, at its socket), `prism search`,
// which asks it for what the index holds, and the program that is all of that and nothing else, compiled ahead of
// time (GraalVM's native-image) so that asking costs no Java's start. Knows nothing of windows or of the index
// itself: what it cannot do by asking, it hands to the prism that can (app), whose commands these are part of.
plugins {
	id("prism.kotlin-jvm")
}

dependencies {
	api(project(":cli"))
	// Where the session's prism listens, on each platform.
	api(project(":platform"))
}

// `./gradlew :client:nativeImage`: build/native/prism, the client as a program of its own. Not part of `build`: it
// takes a GraalVM and a minute, and prism runs without it (the Java one does everything, a moment later). The
// GraalVM is the one `-Pprism.graalvm=DIR` or `$GRAALVM_HOME` names; nothing else in the build needs one, and without
// one this task says so and fails, every other being as it was.
tasks.register<Exec>("nativeImage") {
	val graalvm =
		providers
			.gradleProperty(
				"prism.graalvm",
			).orElse(providers.environmentVariable("GRAALVM_HOME"))

	val classes = files(tasks.jar, configurations.runtimeClasspath)
	val program = layout.buildDirectory.file("native/prism")

	group = "build"
	description =
		"The client compiled ahead of time by GraalVM's native-image, as build/native/prism"
	inputs.files(classes)
	inputs.property("graalvm", graalvm.orElse(""))
	outputs.file(program)

	doFirst {
		check(graalvm.isPresent) {
			"native-image is GraalVM's: name one with -Pprism.graalvm=DIR or GRAALVM_HOME"
		}
		program
			.get()
			.asFile.parentFile
			.mkdirs()
	}
	executable = graalvm.map { "$it/bin/native-image" }.getOrElse("native-image")
	argumentProviders +=
		CommandLineArgumentProvider {
			listOf(
				"--class-path",
				classes.asPath,
				"-o",
				program.get().asFile.path,
				// Ended by a signal, it ends what it started with it (the Java prism it handed its words to).
				"--install-exit-handlers",
				// For whatever processor runs it, not only ones like the one that built it.
				"-march=compatibility",
				"prism.client.MainKt",
			)
		}
}
