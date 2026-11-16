package prism.index

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.linux.Mounts
import prism.platform.FileKind

/**
 * The scanner over a tree made here: what it finds, what it leaves out, and that its two backends find the same
 * thing, down to each directory's device, so that what the benchmark compares is how they ask and nothing else.
 */
class ScanTest {
	@TempDir
	lateinit var root: Path

	private fun made() {
		root.resolve("docs/notes").createDirectories()
		root.resolve("docs/notes/a.txt").writeText("a")
		root.resolve("docs/b.md").writeText("b")
		root.resolve("project/src").createDirectories()
		root.resolve("project/src/main.kt").writeText("fun main() {}")
		root.resolve("project/.git/objects").createDirectories()
		root.resolve("project/.git/HEAD").writeText("ref")
		root.resolve("project/node_modules/left-pad").createDirectories()
		root.resolve("link").createSymbolicLinkPointingTo(root.resolve("docs"))
	}

	/** The tree as sorted, comparable text: one line an entry, with its kind, its inode, and a folder's device. */
	private fun shape(contents: Contents, at: String = ""): List<String> =
		when (contents) {
			is Contents.Unreadable -> listOf("$at !unreadable")
			is Contents.Read ->
				contents.entries.sortedBy { it.name }.flatMap { node ->
					val path = "$at/${node.name}"

					when (node) {
						is Node.Leaf -> listOf("$path ${node.kind} ${node.ino} ${node.target}")
						is Node.Skipped -> listOf("$path skipped ${node.why}")
						is Node.Folder ->
							listOf("$path dir ${node.ino} ${node.dev}") +
								shape(node.contents, path)
					}
				}
		}

	@Test
	fun bothBackendsReadTheSameTree() {
		made()

		val shapes =
			Backend.ALL.map { backend ->
				shape(
					Scanner(backend, threads = 2).scan(root).contents,
				)
			}

		assertEquals(shapes[0], shapes[1])
		assertTrue(shapes[0].isNotEmpty())
	}

	@Test
	fun whatTheRulesLeaveOutIsHeldByNameAndNotRead() {
		made()

		val scanned = Scanner(Backend.Getdents).scan(root)
		val project =
			(scanned.contents as Contents.Read).entries.single {
				it.name == Name.of("project")
			} as Node.Folder

		val held =
			(project.contents as Contents.Read).entries.associateBy {
				it.name.toString()
			}

		assertEquals(Skip.IgnoredDirectory, (held[".git"] as Node.Skipped).why)
		assertEquals(Skip.IgnoredDirectory, (held["node_modules"] as Node.Skipped).why)
		assertInstanceOf(Node.Folder::class.java, held["src"])
	}

	@Test
	fun theCountsAreTheTreesOwn() {
		made()

		val counts = Scanner(Backend.Getdents).scan(root).counts

		// docs, docs/notes, project, project/src; .git and node_modules are skipped, not read.
		assertEquals(
			Counts(
				directories = 4,
				files = 3,
				symlinks = 1,
				others = 0,
				unreadable = 0,
				skipped = 2,
			),
			counts,
		)
	}

	@Test
	fun aSymlinkIsNeverFollowed() {
		made()

		val top = (Scanner(Backend.Getdents).scan(root).contents as Contents.Read).entries

		assertEquals(
			FileKind.Symlink,
			(
				top.single {
					it.name == Name.of("link")
				} as Node.Leaf
			).kind,
		)
	}

	@Test
	fun aNameThatIsNotUtf8IsReadAsItsBytes() {
		// 0xFF is never UTF-8. The JVM cannot make such a name, so the test makes it through its own bindings.
		val bytes = byteArrayOf('n'.code.toByte(), 0xFF.toByte(), 'm'.code.toByte())

		java.lang.foreign.Arena.ofConfined().use { arena ->
			val path = arena.allocate(root.toString().length + bytes.size + 2L)
			val full = root.toString().encodeToByteArray() + '/'.code.toByte() + bytes

			java.lang.foreign.MemorySegment.copy(
				full,
				0,
				path,
				java.lang.foreign.ValueLayout.JAVA_BYTE,
				0,
				full.size,
			)
			assertEquals(0, mkdir(path))
		}

		val top = (Scanner(Backend.Getdents).scan(root).contents as Contents.Read).entries

		assertEquals(listOf(Name.of(bytes)), top.map { it.name })
	}

	@Test
	fun anUnreadableRootIsSaidSo() {
		val scanned = Scanner(Backend.Getdents).scan(root.resolve("nowhere"))

		assertInstanceOf(Contents.Unreadable::class.java, scanned.contents)
		assertEquals(1, scanned.counts.unreadable)
	}

	@Test
	fun mountinfoIsReadAsTheKernelWritesIt() {
		val mounts =
			Mounts.parse(
				listOf(
					"22 1 0:21 / /proc rw,nosuid,nodev,noexec,relatime shared:5 - proc proc rw",
					"25 1 254:0 / / rw,relatime shared:1 - ext4 /dev/vda rw",
					"30 25 0:27 / /media/My\\040Disk rw - vfat /dev/sdb1 rw",
				),
			)

		assertEquals(listOf("proc", "ext4", "vfat"), mounts.map { it.type })
		assertEquals(Path.of("/media/My Disk"), mounts[2].at)
		assertEquals(setOf(Path.of("/proc")), mountsLeftOut(IgnoreRules.DEFAULT, mounts))
	}
}

/** `mkdir(2)` of a path given as raw bytes, for a name the JVM cannot spell. */
private fun mkdir(path: java.lang.foreign.MemorySegment): Int {
	val linker =
		java.lang.foreign.Linker
			.nativeLinker()

	val handle =
		linker.downcallHandle(
			linker.defaultLookup().find("mkdir").orElseThrow(),
			java.lang.foreign.FunctionDescriptor.of(
				java.lang.foreign.ValueLayout.JAVA_INT,
				java.lang.foreign.ValueLayout.ADDRESS,
				java.lang.foreign.ValueLayout.JAVA_INT,
			),
		)

	return handle.invokeExact(path, 0x1ED) as Int
}
