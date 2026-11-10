// The eBPF DSL: kernel types and records, hooks, maps and the statements of a program, typed so that a program
// that would not verify cannot be written where the types can say so; the C it compiles to (CO-RE, no headers);
// and a reader of BTF, the kernel's own description of its types, to check a program's declarations against the
// kernel it will run on. Pure Kotlin: nothing here loads or runs anything (ebpf-loader does). Its test fixtures
// compile a program with clang, for this module's tests and the loader's.
plugins {
	id("prism.kotlin-jvm")
	`java-test-fixtures`
}

dependencies {
	testFixturesImplementation(platform(libs.junit.bom))
	testFixturesImplementation(libs.junit.jupiter)
}

tasks.test {
	// `-Pebpf.emit=DIR` keeps the C the tests emit, to read or to compile by hand.
	providers.gradleProperty("ebpf.emit").orNull?.let {
		systemProperty("ebpf.emit", it)
	}
}
