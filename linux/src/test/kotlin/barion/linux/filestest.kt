package barion.linux

import java.lang.foreign.Arena
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The system calls against a real directory: what getdents64 and statx report, and the errnos of what fails. */
class FilesTest {
	@TempDir
	lateinit var dir: Path

	private fun entries(path: Path): Map<String, FileKind?> =
		Arena.ofConfined().use { arena ->
			val buffer = DirentBuffer(arena, size = 1024)

			withOpened(Fd.CWD, path.toString(), OpenFlags.DIRECTORY_FOR_READING) { fd ->
				generateSequence { buffer.fill(fd).orThrow().takeIf { it > 0 } }
					.flatMap { length ->
						buildList {
							buffer.forEach(length) { _, kind, name, _ -> add(buffer.nameString(name) to kind) }
						}
					}.toMap()
					.let { Sys.Ok(it) }
			}.orThrow()
		}

	@Test
	fun getdentsSaysEveryEntryAndItsKindButTheDots() {
		dir.resolve("folder").createDirectory()
		dir.resolve("file.txt").writeText("hello")
		dir.resolve("link").createSymbolicLinkPointingTo(dir.resolve("file.txt"))

		// Enough entries that the small buffer is filled several times over.
		(0 until 100).forEach { dir.resolve("n$it").writeText("") }

		val read = entries(dir)

		assertEquals(103, read.size)
		assertEquals(FileKind.Directory, read["folder"])
		assertEquals(FileKind.Regular, read["file.txt"])
		assertEquals(FileKind.Symlink, read["link"])
	}

	@Test
	fun statxAgreesWithTheJvm() {
		val file = dir.resolve("file.txt")

		file.writeText("hello")

		val stat = statx(Fd.CWD, file.toString()).orThrow()
		val key = Files.readAttributes(file, "unix:ino,dev,size")

		assertEquals(FileKind.Regular, stat.kind)
		assertEquals(5L, stat.size)
		assertEquals(key["ino"], stat.ino)
	}

	@Test
	fun failuresAreTheirErrno() {
		val missing =
			assertInstanceOf(
				Sys.Failed::class.java,
				openat(Fd.CWD, dir.resolve("nope").toString(), OpenFlags.DIRECTORY_FOR_READING),
			)

		dir.resolve("file").writeText("")

		val notDirectory =
			assertInstanceOf(
				Sys.Failed::class.java,
				openat(Fd.CWD, dir.resolve("file").toString(), OpenFlags.DIRECTORY_FOR_READING),
			)

		assertEquals(Errno.ENOENT, missing.errno)
		assertEquals(Errno.ENOTDIR, notDirectory.errno)
		assertEquals("openat: ENOENT", missing.toString())
	}

	@Test
	fun theKernelsDeviceEncodingRoundTrips() {
		assertEquals(Dev(254, 3), Dev.ofKernel(Dev(254, 3).kernel))
	}

	@Test
	fun aWaitOnNothingReadyTimesOut() {
		Arena.ofConfined().use { arena ->
			Epoll.create(arena).orThrow().use { epoll ->
				assertEquals(emptyList<Long>(), epoll.await(Wait.AtMost(10)).orThrow())
			}
		}
	}
}
