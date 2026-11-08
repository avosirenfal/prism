package barion.ebpf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The BTF reader and the conformance check, against the kernel this test runs on. The declarations in sample.kt are
 * the real `vfs_mkdir`, `vfs_rmdir` and `d_instantiate` and the real fields of the structures they pass, so a kernel
 * that publishes its types must agree with every one of them; a declaration that is deliberately wrong must be
 * named. Self-skips on a kernel that publishes no BTF.
 */
class BtfTest {
	private val btf: Btf? = Btf.ofRunningKernel()

	/** The value, or the test failed saying what was missing (JUnit's own assertNotNull gives nothing back). */
	private fun <T : Any> T?.orFail(what: String): T = this ?: fail(what)

	private fun kernel(): Btf {
		assumeTrue(btf != null, "this kernel publishes no BTF at ${Btf.RUNNING_KERNEL}")

		return btf!!
	}

	@Test
	fun theKernelsOwnTypesAreRead() {
		val btf = kernel()

		assertTrue(btf.typeCount > 10_000, "only ${btf.typeCount} types")

		val inode = btf.struct("inode").orFail("no struct inode")
		val mode = btf.member(inode, "i_mode").orFail("inode has no i_mode")

		// umode_t, through the typedef: two bytes, unsigned, at the front of the structure.
		assertEquals(0, mode.byteOffset)
		assertEquals(2, btf.resolved(mode.type)?.sizeBytes)
		assertEquals(false, btf.resolved(mode.type)?.signed)
	}

	@Test
	fun aMemberOfAnAnonymousUnionIsFoundAsCWouldReachIt() {
		val btf = kernel()
		val dentry = btf.struct("dentry").orFail("no struct dentry")

		// This kernel holds d_name in an anonymous union inside dentry, which C reaches as dentry->d_name.
		val name = btf.member(dentry, "d_name").orFail("dentry has no d_name")

		assertEquals("qstr", btf.resolved(name.type)?.name)
		assertTrue(btf.resolved(name.type)?.isStructOrUnion == true)
	}

	@Test
	fun aFunctionsSignatureIsRead() {
		val btf = kernel()
		val mkdir = btf.function("vfs_mkdir").orFail("no vfs_mkdir")

		assertEquals(4, mkdir.members.size)
		assertEquals(
			"struct dentry *",
			"struct ${btf.resolved(btf.resolved(mkdir.refers)!!.refers)?.name} *",
		)

		val instantiate = btf.function("d_instantiate").orFail("no d_instantiate")

		// void: the prototype refers to type 0.
		assertEquals(0, instantiate.refers)
	}

	@Test
	fun theSampleProgramAgreesWithThisKernel() {
		val mismatches = kernelMismatches(sampleProgram(), kernel())

		assertEquals(emptyList<String>(), mismatches.map { it.message })
	}

	@Test
	fun aFieldTheKernelDoesNotHaveIsNamed() {
		val mismatches = kernelMismatches(wrongProgram(), kernel()).map { it.message }

		assertTrue(mismatches.any { "struct inode has no i_nonesuch" in it }, "$mismatches")
		assertTrue(
			mismatches.any {
				"inode.i_mode is declared __u64, but the kernel's is 2 bytes wide" in
					it
			},
			"$mismatches",
		)
		assertTrue(
			mismatches.any {
				"dentry.d_parent is declared struct inode *, but the kernel's points at dentry" in
					it
			},
			"$mismatches",
		)
		assertTrue(
			mismatches.any {
				"the kernel has no function vfs_nonesuch" in it
			},
			"$mismatches",
		)
	}

	@Test
	fun aSignatureThatMovedIsNamed() {
		val mismatches = kernelMismatches(movedProgram(), kernel()).map { it.message }

		assertTrue(
			mismatches.any {
				"vfs_rmdir is declared with 2 parameters, the kernel's takes 3" in it
			},
			"$mismatches",
		)
		assertTrue(
			mismatches.any {
				"d_instantiate is declared to give __s32, but the kernel's gives nothing back" in
					it
			},
			"$mismatches",
		)
	}
}
