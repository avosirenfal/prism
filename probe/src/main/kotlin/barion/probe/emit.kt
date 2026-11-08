package barion.probe

import barion.ebpf.emittedOrThrow
import barion.ebpf.fileName
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.writeText

/** Every program the probe module declares: what the build emits and compiles. */
val PROGRAMS = listOf(FsWatch.program)

/**
 * The build's emitter (EmitBpfSources): every program's C written into the directory it is given, emptied first so
 * that a program removed from [PROGRAMS] leaves nothing behind. A program with errors stops the build, naming them.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
fun main(args: Array<String>) {
	val into = Path(args.single())

	into.deleteRecursively()
	into.createDirectories()
	for (program in PROGRAMS)
		(into / program.fileName).writeText(program.emittedOrThrow())
}
