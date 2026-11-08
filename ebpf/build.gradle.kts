// The eBPF DSL: kernel types and records, hooks, maps and the statements of a program, typed so that a program
// that would not verify cannot be written where the types can say so; the C it compiles to (CO-RE, no headers);
// and a reader of BTF, the kernel's own description of its types, to check a program's declarations against the
// kernel it will run on. Pure Kotlin: nothing here loads or runs anything (ebpf-loader does).
plugins {
	id("barion.kotlin-jvm")
}

tasks.test {
	// `-Pebpf.emit=DIR` keeps the C the tests emit, to read or to compile by hand.
	providers.gradleProperty("ebpf.emit").orNull?.let {
		systemProperty("ebpf.emit", it)
	}
	// The clang the emitted C is compiled with, where it is not `clang` on the PATH.
	systemProperty(
		"barion.clang",
		providers.gradleProperty("barion.clang").orNull ?: System.getenv("BARION_CLANG")
			?: "clang",
	)
}
