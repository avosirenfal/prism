package barion.ebpf

import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What the DSL emits, and that a real clang accepts it. The C includes nothing, so this needs no kernel headers;
 * it needs a clang with the BPF target, and self-skips without one, as the engine's GPU tests self-skip without a
 * device. With `-Pebpf.emit=DIR` the C is kept there to read.
 */
class EmitTest {
	@TempDir
	lateinit var work: Path

	private val clang: String = System.getProperty("barion.clang", "clang")

	private fun compiled(program: Program): Path {
		val source = work / program.fileName
		val objectFile = work / "${program.name}.bpf.o"

		source.writeText(program.emittedOrThrow())
		System.getProperty("ebpf.emit")?.takeIf { it.isNotBlank() }?.let { into ->
			Path.of(into).createDirectories()
			source.copyTo(Path.of(into) / source.fileName.toString(), overwrite = true)
		}
		assumeTrue(targetsBpf(), "no clang with the BPF target")

		val clanged =
			ProcessBuilder(
				clang,
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

		val said = clanged.inputStream.readBytes().decodeToString()

		assertEquals(
			0,
			clanged.waitFor(),
			"clang refused the emitted C:\n$said\n\n${source.readBytes().decodeToString()}",
		)
		assertTrue(objectFile.exists() && objectFile.fileSize() > 0, "clang wrote no object")

		return objectFile
	}

	private fun targetsBpf(): Boolean =
		try {
			val listed = ProcessBuilder(clang, "-print-targets").redirectErrorStream(true).start()

			listed.waitFor() == 0 && "bpf" in listed.inputStream.readBytes().decodeToString()
		} catch (missing: java.io.IOException) {
			false
		}

	@Test
	fun clangCompilesWhatIsEmitted() {
		val objectFile = compiled(sampleProgram())
		val bytes = objectFile.readBytes().decodeToString(throwOnInvalidSequence = false)

		// The section names the loader attaches by are in the object's own section table.
		for (section in listOf(
			"fexit/vfs_mkdir",
			"fexit/vfs_rmdir",
			"fentry/d_instantiate",
			".maps",
			".BTF",
		))
			assertTrue(section in bytes, "the object has no $section section")
	}

	@Test
	fun theLayoutIsWhatCComputes() {
		// The emitted C asserts barion's offsets against __builtin_offsetof, so a compile that passes is the proof.
		compiled(sampleProgram())

		val layout = Changed.layout

		assertEquals(104, layout.sizeBytes)
		assertEquals(8, layout.alignment)
		assertEquals(0, layout.offsetOf(Changed.change))
		assertEquals(4, layout.offsetOf(Changed.mode))
		assertEquals(8, layout.offsetOf(Changed.dev))
		assertEquals(40, layout.offsetOf(Changed.name))
		assertEquals(
			listOf(Layout.Member.Padding(6, 2), Layout.Member.Padding(12, 4)),
			layout.members.filterIsInstance<Layout.Member.Padding>(),
		)
	}

	@Test
	fun aHookReadsItsContextAsTheKernelPassesIt() {
		val c = sampleProgram().emittedOrThrow()

		// vfs_mkdir's second parameter, and its returned value after the four it takes.
		assertTrue("struct inode *dir = (struct inode *)(ctx[1]);" in c, c)
		assertTrue("struct dentry *returned = (struct dentry *)(ctx[4]);" in c, c)
		// An exit hook of a function that gives nothing back has nothing to read; a fentry hook reads from zero.
		assertTrue("struct inode *inode = (struct inode *)(ctx[1]);" in c, c)
	}

	@Test
	fun theKernelsStructuresAreDeclaredAsTheProgramReadsThem() {
		val c = sampleProgram().emittedOrThrow()

		// Only the fields the program reads, every access relocated by CO-RE.
		val fields = listOf("__u16 i_mode;", "__u64 i_ino;", "struct super_block *i_sb;")
		val inode = "struct inode {\n\t" + fields.joinToString("\n\t") + "\n} KERNEL;"

		assertTrue(inode in c, c)
		// An embedded structure is declared as the structure the kernel holds there, not as a pointer to one.
		assertTrue("struct qstr d_name;" in c, c)
		assertTrue("returned->d_name.name" in c, c)
		// A structure named but never read from is declared and no more.
		assertTrue("struct mnt_idmap;" in c, c)
		assertFalse("struct mnt_idmap {" in c, c)
	}

	@Test
	fun aReservationIsFilledAndSubmittedTogether() {
		val c = sampleProgram().emittedOrThrow()

		assertTrue("bpf_ringbuf_reserve(&changes, sizeof(struct changed), 0)" in c, c)
		assertTrue("bpf_ringbuf_submit(" in c, c)

		// A name is read with the length of the field it is read into, taken from the field's own declaration.
		val read = "bpf_probe_read_kernel_str(changed_3->name, sizeof(changed_3->name), "

		assertTrue(read + "returned->d_name.name);" in c, c)
	}

	@Test
	fun aPerCpuCounterCostsNoAtomicAndASharedOneDoes() {
		val c = sampleProgram().emittedOrThrow()

		assertTrue("(*count_2) += 1ULL;" in c, c)
		assertTrue("__sync_fetch_and_add(&ignored_" in c, c)
	}

	@Test
	fun anEnumTravelsWithTheProgram() {
		val c = sampleProgram().emittedOrThrow()

		assertTrue("#define CHANGE_DIRECTORY_MADE 0" in c, c)
		assertTrue("#define MISSED_RING_FULL 0" in c, c)
		// The C uses the name, so the number cannot drift from the Kotlin enum it came from.
		assertTrue("changed_3->change = CHANGE_DIRECTORY_MADE;" in c, c)
	}

	@Test
	fun onlyTheHelpersCalledAreDeclared() {
		val c = sampleProgram().emittedOrThrow()

		assertTrue("static __u64 (*bpf_ktime_get_ns)(void) = (void *) 5;" in c, c)
		assertFalse("bpf_get_current_comm" in c, c)
	}
}
