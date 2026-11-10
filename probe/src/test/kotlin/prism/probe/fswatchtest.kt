package prism.probe

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.ebpf.Btf
import prism.ebpf.Clang
import prism.ebpf.kernelMismatches

/**
 * The watcher as declared: free of errors, compiled by clang as the build compiles it, and in agreement with the
 * kernel the test runs on, every hook of it. Loading it is the daemon's to try: its hooks are on function exits,
 * which some kernels and containers allow only to a privileged process.
 */
class FsWatchTest {
	@TempDir
	lateinit var work: Path

	@Test
	fun theWatcherHasNoErrors() {
		assertEquals(emptyList<String>(), FsWatch.program.errors.map { it.message })
		assertEquals(9, FsWatch.program.hooks.size)
	}

	@Test
	fun clangCompilesTheWatcher() {
		Clang.compiled(FsWatch.program, work)
	}

	@Test
	fun theRecordHasNoPadding() {
		val layout = FsChange.layout

		assertEquals(304, layout.sizeBytes)
		assertTrue(
			layout.members.none {
				it is prism.ebpf.Layout.Member.Padding
			},
			"${layout.members}",
		)
	}

	@Test
	fun everyHookAgreesWithThisKernel() {
		val btf = Btf.ofRunningKernel()

		assumeTrue(btf != null, "this kernel publishes no BTF")
		assertEquals(
			emptyList<String>(),
			kernelMismatches(FsWatch.program, btf!!).map {
				it.message
			},
		)
	}
}
