package barion

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations

/**
 * The ktlint CLI with the house rule set (the `ktlint-rules` module) over the sources that match [patterns],
 * relative to [root]. [KtlintCheck] reports violations, [KtlintFormat] rewrites the sources.
 */
abstract class KtlintTask : DefaultTask() {
	/** The ktlint CLI with its dependencies. */
	@get:Classpath
	abstract val ktlintClasspath: ConfigurableFileCollection

	/** The rule-set jars ktlint loads with `-R`. */
	@get:Classpath
	abstract val ruleset: ConfigurableFileCollection

	/** The JDK ktlint runs on; the house rules jar targets the project's toolchain, so it must be that one. */
	@get:Nested
	abstract val launcher: Property<JavaLauncher>

	/** ktlint's own glob patterns; a leading `!` excludes. */
	@get:Input
	abstract val patterns: ListProperty<String>

	/** The directory the patterns are relative to, and ktlint's working directory. */
	@get:Internal
	abstract val root: DirectoryProperty

	@get:Inject
	protected abstract val execOperations: ExecOperations

	/** The arguments that tell checking from formatting. */
	protected abstract fun modeArguments(): List<String>

	/** What to make of ktlint's exit value; non-zero means it found violations. */
	protected abstract fun onExit(exitValue: Int)

	@TaskAction
	fun runKtlint() {
		val java = launcher.get().executablePath.asFile

		val result =
			execOperations.javaexec {
				executable = java.absolutePath
				classpath = ktlintClasspath
				mainClass.set("com.pinterest.ktlint.Main")
				workingDir = root.get().asFile
				args(ruleset.files.map { "-R${it.absolutePath}" })
				args(modeArguments())
				args("--relative")
				args(patterns.get())
				isIgnoreExitValue = true
			}

		onExit(result.exitValue)
	}
}

/**
 * Lints. Up to date while the sources, the `.editorconfig` files, the rules and ktlint itself are unchanged,
 * and cacheable, so `check` lints only what changed since the last clean run.
 */
@CacheableTask
abstract class KtlintCheck : KtlintTask() {
	/** Every file the patterns can match, plus the `.editorconfig` files that configure the rules. */
	@get:InputFiles
	@get:IgnoreEmptyDirectories
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val sources: ConfigurableFileCollection

	/** A checkstyle report of the run, the task's output next to the console's plain one. */
	@get:OutputFile
	abstract val report: RegularFileProperty

	/** Whether violations fail the task (and so `check`), or are only reported. */
	@get:Input
	abstract val failOnViolations: Property<Boolean>

	override fun modeArguments() =
		listOf(
			"--reporter=plain",
			"--reporter=checkstyle,output=${report.get().asFile.absolutePath}",
		)

	override fun onExit(exitValue: Int) {
		if (failOnViolations.get() && exitValue != 0)
			throw GradleException(
				"ktlint found violations (exit $exitValue); see the output above",
			)
	}
}

/** Rewrites the sources in place. It changes its own inputs, so it declares no outputs and always runs. */
abstract class KtlintFormat : KtlintTask() {
	override fun modeArguments() = listOf("-F")

	/** What ktlint could not autocorrect is reported, never a failure. */
	override fun onExit(exitValue: Int) = Unit
}
