package prism.probe

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.ebpf.Btf
import prism.ebpf.Clang
import prism.ebpf.kernelCoverage

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
		// Nine places in the kernel, six of them in two shapes.
		assertEquals(15, FsWatch.program.hooks.size)
		assertEquals(
			10,
			FsWatch.program.hooks
				.map { it.section }
				.distinct()
				.size,
		)
		assertEquals(
			9,
			FsWatch.program.hooks
				.map { it.callable.place }
				.distinct()
				.size,
		)
		assertEquals(
			15,
			FsWatch.program.hooks
				.map { it.cName }
				.distinct()
				.size,
		)
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

		val coverage = kernelCoverage(FsWatch.program, btf!!)

		// Nine places, each with a shape declared for it that is this kernel's; opening, which the kernel renamed,
		// is one place under either name.
		assertEquals(9, coverage.size)
		assertEquals(
			emptyList<String>(),
			coverage.filter { it.fits == null }.map { "${it.section}: ${it.misfits.values}" },
		)
	}

	@Test
	fun twoShapesOfOneFunctionAreTwoHooksInOneSection() {
		val mkdir = FsWatch.program.hooks.filter { it.section == "fexit/vfs_mkdir" }

		assertEquals(
			listOf("exit_vfs_mkdir", "exit_vfs_mkdir_since_6_19"),
			mkdir.map {
				it.cName
			},
		)
		assertEquals(
			listOf("fexit/vfs_mkdir", "fexit/vfs_mkdir (since 6.19)"),
			mkdir.map {
				it.toString()
			},
		)
	}
}
