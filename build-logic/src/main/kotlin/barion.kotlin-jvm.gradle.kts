import barion.libs

// Every module: Kotlin on the one JDK the catalog names (libs.versions.toml: jdk), JUnit for the tests, and the
// tests allowed to call native code through the FFM API (libc, libbpf): without the flag the JDK warns on the first
// downcall, and a later JDK refuses it.
plugins {
	id("org.jetbrains.kotlin.jvm")
}

group = "dev.barion"
version = "0.1.0"

kotlin {
	jvmToolchain(libs.version("jdk").toInt())
}

dependencies {
	testImplementation(platform(libs.library("junit-bom")))
	testImplementation(libs.library("junit-jupiter"))
	testRuntimeOnly(libs.library("junit-platform-launcher"))
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	jvmArgs("--enable-native-access=ALL-UNNAMED")
	testLogging {
		events("skipped", "failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}
