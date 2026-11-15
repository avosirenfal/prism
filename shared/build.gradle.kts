// What is shared, and how it stays whole: state shared between threads, reached only under its lock (Guarded and its
// kin), and state shared between runs and processes, a value kept in a file that is only ever read, changed and
// written as one step (Stored). A module of its own because every module that keeps a file or shares state between
// threads needs it, and it is about neither Linux nor any one of them: it depends on nothing but coroutines, for the
// state a stored value is handed to its readers as.
plugins {
	id("prism.kotlin-jvm")
}

dependencies {
	api(libs.kotlinx.coroutines.core)
	testImplementation(libs.kotlinx.coroutines.test)
}
