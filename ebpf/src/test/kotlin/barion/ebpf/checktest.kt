package barion.ebpf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What a declaration gets wrong that its types cannot refuse. Each is reported as data, all of them at once, and
 * nothing is emitted from a program that has any.
 *
 * The mistakes the types *do* refuse are not here, because they are not expressible: see docs/ebpf.md. A value of
 * one width cannot be added to another, a map's value cannot be reached without the lookup's null check, a
 * reservation cannot be left unsubmitted, a parameter of one function cannot be read in a hook of another, and a
 * `void` function's exit hook has no return value to read.
 */
class CheckTest {
	private fun errors(program: Program): List<String> = program.errors.map { it.message }

	@Test
	fun aRecordSentWithAFieldUnsetIsRefused() {
		val program =
			program("unset") {
				val notes by ringBuffer(Changed, bytes = 1 shl 16)

				fexit(VfsRmdir) {
					send(notes, onFull = {}) {
						it[Changed.change] = Change.DirectoryGone.code()
					}
				}
			}

		assertEquals(
			listOf(
				"changed leaves mode, dev, parent_ino, ino, at_ns, name unset: " +
					"the host reads every field of changed",
			),
			errors(program),
		)
		assertNull(program.emitted())
		assertTrue(
			assertThrows<NotLoadable> {
				program.emittedOrThrow()
			}.message!!.contains("leaves mode"),
		)
	}

	@Test
	fun aKeyLookedUpWithAFieldUnsetIsRefused() {
		val program =
			program("partial") {
				val ignoring by table(DirKey, Ignored, maxEntries = 16)

				fexit(VfsRmdir) {
					val key = filled(DirKey) { it[DirKey.dev] = U32 of 0 }

					remove(ignoring, key)
				}
			}

		assertEquals(
			listOf("dir_key leaves ino unset: dir_key is looked up by every byte it holds"),
			errors(program),
		)
	}

	@Test
	fun aGplOnlyHelperOutsideAGplProgramIsRefused() {
		assertEquals(
			listOf("bpf_probe_read_kernel_str is GPL-only and the program is Proprietary"),
			errors(sampleProgram(License.Proprietary)).distinct(),
		)
	}

	@Test
	fun aRingTooSmallForTwoRecordsIsRefused() {
		val program =
			program("tiny") {
				@Suppress("UNUSED_VARIABLE")
				val notes by ringBuffer(Changed, bytes = 4096)

				fexit(VfsRmdir) { stopIf(VfsRmdir.returns.value() ne (S32 of 0)) }
			}

		assertEquals(emptyList<String>(), errors(program))

		val smaller =
			program("smaller") {
				@Suppress("UNUSED_VARIABLE")
				val notes by ringBuffer(Padded, bytes = 4096)

				fexit(VfsRmdir) { stopIf(VfsRmdir.returns.value() ne (S32 of 0)) }
			}

		assertEquals(
			listOf("notes is 4096 bytes, too small for two padded records of 4096 bytes each"),
			errors(smaller),
		)
	}

	@Test
	fun moreRecordsThanTheStackHoldsIsRefused() {
		val program =
			program("deep") {
				val ignoring by table(DirKey, Ignored, maxEntries = 16)

				fexit(VfsRmdir) {
					// Thirty-three keys of sixteen bytes: past the 512 the verifier allows.
					for (round in 0 until 33) {
						val key =
							filled(DirKey) {
								it[DirKey.dev] = U32 of round
								it[DirKey.ino] = U64 of round
							}

						remove(ignoring, key)
					}
				}
			}

		assertEquals(
			listOf(
				"fexit/vfs_rmdir holds 528 bytes of records on a stack the verifier limits to 512",
			),
			errors(program),
		)
	}

	@Test
	fun twoHooksOfOnePlaceAndTwoMapsOfOneNameAreRefused() {
		val program =
			program("twice") {
				val notes by ringBuffer(Padded, bytes = 1 shl 20)
				val same by table(DirKey, Ignored, maxEntries = 16)

				fexit(VfsRmdir) { stopIf(VfsRmdir.returns.value() ne (S32 of 0)) }
				fexit(VfsRmdir) { stopIf(VfsRmdir.returns.value() ne (S32 of 0)) }
			}

		assertEquals(
			listOf(
				"fexit/vfs_rmdir is hooked twice in one program; one hook says everything it does",
			),
			errors(program),
		)
	}

	@Test
	fun aProgramThatHooksNothingIsRefused() {
		assertEquals(
			listOf(
				"idle hooks nothing, so loading it would do nothing",
			),
			errors(
				program("idle") {
				},
			),
		)
	}

	@Test
	fun everyMistakeIsReportedAtOnce() {
		val program =
			program("several", License.Proprietary) {
				val notes by ringBuffer(Changed, bytes = 1 shl 16)

				fexit(VfsRmdir) {
					send(notes, onFull = {}) {
						it[Changed.name] = kernelText(VfsRmdir.dentry.value()[Dentry.dName][Qstr.name])
					}
				}
			}

		assertEquals(2, program.errors.size, program.why())
		assertTrue("GPL-only" in program.why())
		assertTrue("leaves change" in program.why())
	}
}

/** A record as long as a small ring, to try a ring that could not hold two of them. */
object Padded : Record<Padded>("padded") {
	val value by u64()
	val rest by chars(4088)
}
