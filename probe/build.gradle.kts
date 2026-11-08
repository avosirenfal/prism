// The kernel side of the index's watcher, in the eBPF DSL: the hooks on the VFS that see entries made, removed and
// moved, and the record each sends. Its emitter (barion.probe.EmitKt) writes the C, which the index compiles.
plugins {
	id("barion.kotlin-jvm")
}

dependencies {
	api(project(":ebpf"))
}

dependencies {
	testImplementation(testFixtures(project(":ebpf")))
}
