// The calculator's language: arithmetic over exact decimals by default, and over Kotlin's own number types where a
// value is converted to one (`5.toUInt()`), with what Kotlin's would do to them (an Int's overflow, a Double's
// rounding) shown rather than hidden. Read by the parser combinators (parsing); knows nothing of the launcher that
// shows its answers.
plugins {
	id("prism.kotlin-jvm")
}

dependencies {
	api(project(":parsing"))
}
