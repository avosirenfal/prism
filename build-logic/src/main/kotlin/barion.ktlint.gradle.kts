import barion.KtlintCheck
import barion.KtlintFormat
import barion.KtlintTask
import barion.libs
import barion.resolvesJavaRuntimeJars

// ktlint with the house rule set (the ktlint-rules module), for the root project. `./gradlew ktlintCheck`
// lints, `./gradlew ktlintFormat` rewrites in place; `check` (and therefore `build`) runs the lint.
plugins {
	base
	`jvm-toolchains`
}

val ktlintCli =
	configurations.dependencyScope("ktlintCli") {
		description = "The ktlint CLI."
	}

val ktlintCliClasspath =
	configurations.resolvable("ktlintCliClasspath") {
		extendsFrom(ktlintCli.get())
		resolvesJavaRuntimeJars(objects)
	}

val houseRules =
	configurations.dependencyScope("ktlintHouseRules") {
		description = "The house rule set, loaded into the ktlint CLI with -R."
	}

val houseRulesClasspath =
	configurations.resolvable("ktlintHouseRulesClasspath") {
		extendsFrom(houseRules.get())
		// The jar alone: ktlint ships the rule API and the Kotlin stdlib the rules run against.
		isTransitive = false
		resolvesJavaRuntimeJars(objects)
	}

dependencies {
	add(ktlintCli.name, libs.library("ktlint-cli"))
	add(houseRules.name, project(":ktlint-rules"))
}

/** ktlint's patterns, relative to the root; the lint task's inputs are the same files. */
val ktlintPatterns = listOf("**/src/**/*.kt", "**/*.kts", "!**/build/**")

val lintedSources =
	fileTree(layout.projectDirectory) {
		for (pattern in ktlintPatterns)
			if (pattern.startsWith("!"))
				exclude(pattern.drop(1))
			else
				include(pattern)
		exclude(".git/**", "**/.gradle/**")
	}

val editorConfigs =
	fileTree(layout.projectDirectory) {
		include("**/.editorconfig")
		exclude("**/build/**", ".git/**", "**/.gradle/**")
	}

val toolchainJdk = JavaLanguageVersion.of(libs.version("jdk"))

tasks.withType<KtlintTask>().configureEach {
	group = "formatting"
	ktlintClasspath.from(ktlintCliClasspath)
	ruleset.from(houseRulesClasspath)
	launcher = javaToolchains.launcherFor { languageVersion = toolchainJdk }
	patterns = ktlintPatterns
	root = layout.projectDirectory
}

val ktlintCheck =
	tasks.register<KtlintCheck>("ktlintCheck") {
		description = "Checks Kotlin sources against .editorconfig and the house rules."
		sources.from(lintedSources, editorConfigs)
		report = layout.buildDirectory.file("reports/ktlint/ktlint-check.xml")
		// The sources still carry violations ktlint cannot autocorrect (long lines, mostly), so the lint reports
		// and the build goes on. Flip this once they are clean, and `check` enforces the style.
		failOnViolations = false
	}

tasks.register<KtlintFormat>("ktlintFormat") {
	description = "Reformats Kotlin sources in place with .editorconfig and the house rules."
}

tasks.check {
	dependsOn(ktlintCheck)
}
