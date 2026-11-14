package prism.ebpfloader

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.ebpf.Clang
import prism.ebpf.HookKind
import prism.linux.Errno
import prism.linux.Fd
import prism.linux.Sys
import prism.linux.Wait
import prism.linux.close
import prism.linux.mount
import prism.linux.orThrow
import prism.linux.unmount

/**
 * A program from the DSL, through clang and libbpf, into the kernel this test runs on and back: the host writes a
 * table, the program sends what this process does, the host reads it in place as the program's own records.
 *
 * Self-skips where it cannot run: without clang's BPF target, without libbpf, or where this process may not load BPF
 * programs (an unprivileged user, a container that does not allow it), which the kernel says with EPERM.
 */
class LoadTest {
	@TempDir
	lateinit var work: Path

	/** The loaded object, or the test skipped where the system cannot load one at all. */
	private fun loadedOrSkip(loading: Loading): LoadedObject =
		when (loading) {
			is Loading.Loaded -> loading.loaded
			is Loading.Refused ->
				when (val why = loading.why) {
					Refusal.NoLibbpf -> {
						assumeTrue(false, why.message)
						fail(why.message)
					}
					is Refusal.LoadFailed -> {
						assumeTrue(
							why.failure.errno != Errno.EPERM,
							"this process may not load BPF: ${why.message}",
						)
						fail(why.message)
					}
					else -> fail(why.message)
				}
		}

	@Test
	fun theProgramSeesWhatThisProcessDoes() {
		// The first argument of a call is `di` on x86-64 alone, and `close` is call 3 there.
		assumeTrue(
			System.getProperty("os.arch") in setOf("amd64", "x86_64"),
			"x86-64's system call numbering",
		)

		val entered = entered()
		val compiled = Clang.compiled(entered.program, work)
		val maps = entered.maps
		val tgid = ProcessHandle.current().pid()

		loadedOrSkip(load(entered.program, compiled.readBytes())).use { loaded ->
			assertEquals(entered.program.hooks, loaded.attached)

			loaded
				.put(
					maps.watched,
					key = { it[Watched.tgid] = tgid },
					value = { it[Watching.since] = System.nanoTime() },
				).orThrow()

			loaded.reader(maps.entries).orThrow().use { reader ->
				val seen = ArrayList<Pair<Long, Int>>()
				val deadline = System.nanoTime() + 5_000_000_000L

				// A close of a descriptor that is not open: it fails, and the kernel saw it called, with -77. The
				// argument is an int, which the caller's 32-bit move leaves zero-extended in the register, so it is
				// compared as the int it is.
				repeat(3) { close(Fd(-77)) }
				while (seen.none { it == CLOSE to -77 } && System.nanoTime() < deadline)
					reader
						.await(Wait.AtMost(100)) { record ->
							assertEquals(
								tgid,
								record.number(Entered.tgid),
								"a record of a process the host did not watch",
							)
							seen += record.number(Entered.call) to record.number(Entered.first).toInt()
						}.orThrow()

				assertTrue(
					CLOSE to -77 in seen,
					"no close(-77) among the ${seen.size} calls the program sent: $seen",
				)
			}

			assertEquals(mapOf(Lost.RingFull to 0L), loaded.counted(maps.lost).orThrow())
		}
	}

	/**
	 * A program left in the kernel by the process that loaded it, and read by another way in: its hooks and maps
	 * given names in a BPF file system, the object that loaded them closed, and the maps opened again by their names
	 * alone, which is all a process without the privileges to load one has. What the program sends after its loader
	 * is gone is read through them; and with the names taken away, there is nothing to open.
	 */
	@Test
	fun aProgramLeftPinnedGoesOnAfterItsLoaderAndIsReadByItsNames() {
		assumeTrue(
			System.getProperty("os.arch") in setOf("amd64", "x86_64"),
			"x86-64's system call numbering",
		)

		val entered = entered()
		val compiled = Clang.compiled(entered.program, work)
		val maps = entered.maps
		val tgid = ProcessHandle.current().pid()
		val pins = Files.createDirectory(work.resolve("pins"))

		assumeTrue(
			mount("prism-test", pins, "bpf", "mode=0755") is Sys.Ok,
			"this process may not mount a BPF file system",
		)

		try {
			loadedOrSkip(load(entered.program, compiled.readBytes())).use { loaded ->
				loaded.pinned(pins).orThrow()
			}

			// The loader is closed: what is left is the kernel's, by its names.
			assertEquals(
				(
					entered.program.maps.map { it.cName } +
						entered.program.hooks.map { PinnedObject.HOOK_PREFIX + it.cName }
				).toSet(),
				Files
					.list(pins)
					.use { names -> names.map { it.fileName.toString() }.toList() }
					.filterNot { it.endsWith(".debug") }
					.toSet(),
			)

			PinnedObject.open(entered.program, pins).orThrow().use { pinned ->
				assertEquals(entered.program.hooks, pinned.attached)
				pinned
					.put(
						maps.watched,
						key = { it[Watched.tgid] = tgid },
						value = { it[Watching.since] = System.nanoTime() },
					).orThrow()

				pinned.reader(maps.entries).orThrow().use { reader ->
					val seen = ArrayList<Pair<Long, Int>>()
					val deadline = System.nanoTime() + 5_000_000_000L

					repeat(3) { close(Fd(-77)) }
					while (seen.none { it == CLOSE to -77 } && System.nanoTime() < deadline)
						reader
							.await(Wait.AtMost(100)) { record ->
								seen += record.number(Entered.call) to record.number(Entered.first).toInt()
							}.orThrow()

					assertTrue(
						CLOSE to -77 in seen,
						"no close(-77) among what the pinned program sent: $seen",
					)
				}
				assertEquals(mapOf(Lost.RingFull to 0L), pinned.counted(maps.lost).orThrow())
			}

			PinnedObject.unpin(entered.program, pins)
			assertEquals(
				Errno.ENOENT,
				(PinnedObject.open(entered.program, pins) as Sys.Failed).errno,
			)
		} finally {
			// Unmounted whatever became of the names: nothing of a test's is left mounted.
			runCatching { PinnedObject.unpin(entered.program, pins) }
			unmount(pins)
		}
	}

	@Test
	fun theProgramSeesNothingTheHostDidNotAskFor() {
		val entered = entered()
		val compiled = Clang.compiled(entered.program, work)

		loadedOrSkip(load(entered.program, compiled.readBytes())).use { loaded ->
			loaded.reader(entered.maps.entries).orThrow().use { reader ->
				repeat(3) { close(Fd(-77)) }

				var sent = 0

				reader.await(Wait.AtMost(200)) { sent++ }.orThrow()

				// The table is empty, so the program stopped before sending anything.
				assertEquals(0, sent)
			}
		}
	}

	@Test
	fun aHookThisKernelCannotTakeIsLeftOutAndTheRestLoad() {
		val entered = entered(alsoMissing = true)
		val compiled = Clang.compiled(entered.program, work)

		loadedOrSkip(load(entered.program, compiled.readBytes())).use { loaded ->
			val missing = entered.program.hooks.single { it.kind == HookKind.Exit }
			val mismatched =
				assertInstanceOf(Attachment.Mismatched::class.java, loaded.attachments[missing])

			assertEquals(
				listOf("the kernel has no function prism_no_such_function"),
				mismatched.mismatches.map { it.message },
			)
			assertEquals(
				entered.program.hooks.filter {
					it.kind == HookKind.Tracepoint
				},
				loaded.attached,
			)
		}
	}

	@Test
	fun anObjectOfAnotherProgramIsRefused() {
		val compiled = Clang.compiled(entered().program, work)
		val other = entered(alsoMissing = true).program

		val loading = load(other, compiled.readBytes())

		// A loader that cannot run here at all says so first; one that can sees the object is not this program's.
		if (loading is Loading.Refused && loading.why == Refusal.NoLibbpf)
			assumeTrue(false, Refusal.NoLibbpf.message)

		val refused = assertInstanceOf(Loading.Refused::class.java, loading)

		assertEquals(
			"the object holds no exit_prism_no_such_function: it was not compiled from entered",
			refused.why.message,
		)
	}
}
