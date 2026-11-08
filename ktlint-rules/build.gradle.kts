import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// House-style ktlint rules. Packaged as a plain jar (no ktlint classes bundled) so the ktlint CLI can load it with `-R`.
plugins {
	id("barion.kotlin-jvm")
}

kotlin {
	compilerOptions {
		// The jar runs inside the ktlint CLI, which ships its own (older) Kotlin stdlib: stay within that API surface.
		apiVersion = KotlinVersion.KOTLIN_2_2
	}
}

dependencies {
	compileOnly(libs.ktlint.rule.engine.core)
	compileOnly(libs.ktlint.cli.ruleset.core)

	testImplementation(libs.ktlint.rule.engine)
	testImplementation(libs.ktlint.rule.engine.core)
	testImplementation(libs.ktlint.cli.ruleset.core)
	testImplementation(libs.ktlint.ruleset.standard)
	testRuntimeOnly(libs.slf4j.nop)
}

tasks.test {
	testLogging {
		events("failed")
		showStandardStreams = false
	}
}
