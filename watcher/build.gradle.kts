import barion.CompileBpf
import barion.EmitBpfSources
import barion.resolvesJavaRuntimeJars

// The watcher: the probe module's BPF program, compiled here from its declarations into this module's resources,
// loaded, attached, and its ring read on a thread of its own into the changes the kernel made. It knows nothing of
// the index; the daemon module makes the index of what it sees.
plugins {
	id("barion.kotlin-jvm")
}

val probeEmitter =
	configurations.dependencyScope("probeEmitter") {
		description = "The probe module, whose main emits the C of its programs."
	}

val probeEmitterClasspath =
	configurations.resolvable("probeEmitterClasspath") {
		extendsFrom(probeEmitter.get())
		resolvesJavaRuntimeJars(objects)
	}

dependencies {
	api(project(":probe"))
	api(project(":ebpf-loader"))
	api(project(":linux"))
	add(probeEmitter.name, project(":probe"))
}

val emitProbes =
	tasks.register<EmitBpfSources>("emitProbes") {
		description = "Emits the C of the probe module's BPF programs."
		emitterClasspath.from(probeEmitterClasspath)
		mainClass = "barion.probe.EmitKt"
		launcher =
			javaToolchains.launcherFor {
				languageVersion =
					JavaLanguageVersion.of(libs.versions.jdk.get())
			}
		outputDirectory = layout.buildDirectory.dir("generated/probe-c")
	}

val compileProbes =
	tasks.register<CompileBpf>("compileProbes") {
		description =
			"Compiles the probes' C with clang's BPF target, into the module's resources."
		sources = emitProbes.flatMap { it.outputDirectory }
		clang =
			providers
				.gradleProperty("barion.clang")
				.orElse(providers.environmentVariable("BARION_CLANG"))
				.orElse("clang")
		resourcePath = "barion/probe"
		outputDirectory = layout.buildDirectory.dir("generated/probe-objects")
	}

sourceSets.main {
	resources.srcDir(compileProbes)
}
