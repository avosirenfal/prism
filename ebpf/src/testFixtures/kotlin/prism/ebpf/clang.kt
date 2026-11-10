package prism.ebpf

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * clang's BPF target, for the tests: a program emitted and compiled as the build compiles it. A test that needs it
 * self-skips where there is no clang with the BPF target, as the engine's GPU tests self-skip without a device.
 */
object Clang {
	/** The clang the build names (`-Pprism.clang`, `$PRISM_CLANG`), or `clang` on the PATH. */
	val command: String =
		System.getProperty("prism.clang")?.takeIf { it.isNotBlank() } ?: "clang"

	val targetsBpf: Boolean by lazy {
		try {
			val listed =
				ProcessBuilder(
					command,
					"-print-targets",
				).redirectErrorStream(true).start()

			val said = listed.inputStream.readBytes().decodeToString()

			listed.waitFor() == 0 && "bpf" in said
		} catch (missing: IOException) {
			false
		}
	}

	/**
	 * [program]'s C written into [dir] and compiled, with the flags the build uses: the object. With `-Pebpf.emit=DIR`
	 * the C is kept there as well. Skips the test without clang, and fails it, with what clang said, where clang
	 * refuses the C.
	 */
	fun compiled(program: Program, dir: Path): Path {
		val source = dir / program.fileName
		val objectFile = dir / "${program.name}.bpf.o"

		source.writeText(program.emittedOrThrow())
		System.getProperty("ebpf.emit")?.takeIf { it.isNotBlank() }?.let { into ->
			Path.of(into).createDirectories()
			source.copyTo(Path.of(into) / source.fileName.toString(), overwrite = true)
		}
		assumeTrue(targetsBpf, "no clang with the BPF target ($command)")

		val clang =
			ProcessBuilder(
				command,
				"-target",
				"bpf",
				"-O2",
				"-g",
				"-Wall",
				"-Werror",
				"-c",
				source.toString(),
				"-o",
				objectFile.toString(),
			).redirectErrorStream(true).start()

		val said = clang.inputStream.readBytes().decodeToString()

		assertEquals(
			0,
			clang.waitFor(),
			"clang refused the emitted C:\n$said\n\n${source.readText()}",
		)
		assertTrue(objectFile.exists() && objectFile.fileSize() > 0, "clang wrote no object")

		return objectFile
	}
}
