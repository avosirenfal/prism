// BPF objects compiled from the DSL, loaded and attached through libbpf (by the FFM API), and their ring buffers
// read in place through memory maps of their own, as records of the DSL's types.
plugins {
	id("barion.kotlin-jvm")
}

dependencies {
	api(project(":ebpf"))
	api(project(":linux"))

	testImplementation(testFixtures(project(":ebpf")))
}
