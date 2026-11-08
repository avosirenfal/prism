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

// The clang the tests compile BPF programs with: `clang` on the PATH unless `-Pbarion.clang=PATH` or `$BARION_CLANG`
// names another (a Nix shell's unwrapped one, which does not add the hardening flags the BPF target refuses).
val clang =
	providers
		.gradleProperty(
			"barion.clang",
		).orElse(providers.environmentVariable("BARION_CLANG"))
		.orElse("clang")

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	jvmArgs("--enable-native-access=ALL-UNNAMED")
	systemProperty("barion.clang", clang.get())
	// The JVM names files in the locale's encoding, so under a locale that is not UTF-8 (a bare container's POSIX one)
	// it cannot make a Path of a name like "Téléchargements". A desktop session's locale is UTF-8; the tests' is too.
	environment("LC_ALL", "C.UTF-8")
	testLogging {
		events("skipped", "failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}
