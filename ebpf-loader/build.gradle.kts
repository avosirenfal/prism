// BPF objects compiled from the DSL, loaded and attached through libbpf (by the FFM API), and their ring buffers
// read in place through memory maps of their own, as records of the DSL's types.
plugins {
	id("prism.kotlin-jvm")
}

dependencies {
	api(project(":ebpf"))
	api(project(":linux"))

	testImplementation(testFixtures(project(":ebpf")))
}

// The tests' classes and what they run with, side by side in one folder: for the check a test cannot make as one
// process, that one user reads what another left pinned (src/test/.../pinnedcheck.kt, docs/testing.md), which is run
// by `java` itself, twice, as two users.
tasks.register<Sync>("pinnedCheckClasspath") {
	val classpath = sourceSets.test.get().runtimeClasspath

	from(classpath.filter { it.isDirectory }) { into("classes") }
	from(classpath.filter { it.isFile }) { into("lib") }
	into(layout.buildDirectory.dir("pinned-check"))
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
