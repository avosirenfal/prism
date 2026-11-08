// The eBPF DSL: kernel types and records, hooks, maps and the statements of a program, typed so that a program
// that would not verify cannot be written where the types can say so; the C it compiles to (CO-RE, no headers);
// and a reader of BTF, the kernel's own description of its types, to check a program's declarations against the
// kernel it will run on. Pure Kotlin: nothing here loads or runs anything (ebpf-loader does).
plugins {
	id("barion.kotlin-jvm")
}
