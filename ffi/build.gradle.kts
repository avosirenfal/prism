// Native functions as typed Kotlin functions, through the FFM API: a C function is declared once, as an interface whose
// one method is its signature, and both what the linker is told of it and what a call may pass and expect come from
// that declaration, so the two cannot disagree. What Linux's and macOS's bindings are written with. Depends on nothing.
plugins {
	id("prism.kotlin-jvm")
}
