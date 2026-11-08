package barion

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations

/**
 * Runs a module's probe emitter ([mainClass], a `main(outputDirectory)` of the `probe` module): the C of every BPF
 * object the module declares in the Kotlin DSL, one `NAME.bpf.c` each. The C is a function of the emitter's
 * classpath and the JDK it runs on, so the task is cacheable; the emitter empties the directory before it writes.
 */
@CacheableTask
abstract class EmitBpfSources : DefaultTask() {
	/** The emitter and its runtime dependencies (the DSL). */
	@get:Classpath
	abstract val emitterClasspath: ConfigurableFileCollection

	@get:Input
	abstract val mainClass: Property<String>

	/** The JDK the emitter runs on; it is compiled for the project's toolchain, so it must be that one. */
	@get:Nested
	abstract val launcher: Property<JavaLauncher>

	@get:OutputDirectory
	abstract val outputDirectory: DirectoryProperty

	@get:Inject
	protected abstract val execOperations: ExecOperations

	@TaskAction
	fun emit() {
		val java = launcher.get().executablePath.asFile

		execOperations.javaexec {
			executable = java.absolutePath
			classpath = emitterClasspath
			mainClass.set(this@EmitBpfSources.mainClass)
			args(outputDirectory.get().asFile.absolutePath)
		}
	}
}

/**
 * Compiles every `NAME.bpf.c` under [sources] to `NAME.bpf.o` under [outputDirectory]/[resourcePath], with clang's BPF
 * target, optimised and with BTF (`-g`), which the CO-RE relocations and the map definitions are read from. The
 * emitted C includes nothing, so no kernel or libbpf headers are needed: only a clang with the BPF target.
 */
@CacheableTask
abstract class CompileBpf : DefaultTask() {
	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val sources: DirectoryProperty

	/** The clang to run: `clang` on the PATH unless `-Pbarion.clang=PATH` or `$BARION_CLANG` names another. */
	@get:Input
	abstract val clang: Property<String>

	/** The package directory the objects land in, so they are resources under one known name. */
	@get:Input
	abstract val resourcePath: Property<String>

	@get:OutputDirectory
	abstract val outputDirectory: DirectoryProperty

	@get:Inject
	protected abstract val execOperations: ExecOperations

	@TaskAction
	fun compile() {
		val target = outputDirectory.get().asFile.resolve(resourcePath.get())

		target.deleteRecursively()
		target.mkdirs()

		val sourcesDir = sources.get().asFile
		val units =
			sourcesDir
				.listFiles { file ->
					file.name.endsWith(".bpf.c")
				}.orEmpty()
				.sortedBy { it.name }

		if (units.isEmpty())
			throw GradleException("no .bpf.c under $sourcesDir: the emitter wrote nothing")

		for (unit in units)
			execOperations.exec {
				commandLine(
					clang.get(),
					"-target",
					"bpf",
					"-O2",
					"-g",
					"-Wall",
					"-Werror",
					"-c",
					unit.absolutePath,
					"-o",
					target.resolve(unit.name.removeSuffix(".c") + ".o").absolutePath,
				)
			}
	}
}
