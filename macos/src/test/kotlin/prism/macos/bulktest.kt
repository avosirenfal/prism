package prism.macos

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import prism.platform.FileKind

/** A folder read in bulk: the records as the kernel lays them out, and, on a Mac, a real folder. */
class BulkTest {
	/** A record of [name], [type] and [id] written at [at], with the attributes [has] says; where the next begins. */
	private fun record(
		buffer: MemorySegment,
		at: Long,
		name: String,
		type: Int,
		id: Long,
		has: Int = 0x80000000.toInt() or 0x1 or 0x8 or 0x02000000,
	): Long {
		val bytes = name.encodeToByteArray()
		var field = at + 24

		buffer.set(JAVA_INT_UNALIGNED, at + 4, has)
		if (has and 0x1 != 0) {
			// The name's bytes are after the attributes: the type and the number, where the record has them.
			val after =
				8 + (if (has and 0x8 != 0) 4 else 0) + (if (has and 0x02000000 != 0) 8 else 0)

			buffer.set(JAVA_INT_UNALIGNED, field, after)
			buffer.set(JAVA_INT_UNALIGNED, field + 4, bytes.size + 1)
			MemorySegment.copy(bytes, 0, buffer, JAVA_BYTE, field + after, bytes.size)
			field += 8
		}
		if (has and 0x8 != 0) {
			buffer.set(JAVA_INT_UNALIGNED, field, type)
			field += 4
		}
		if (has and 0x02000000 != 0) {
			buffer.set(JAVA_LONG_UNALIGNED, field, id)
			field += 8
		}

		val length = (field - at + bytes.size + 1 + 3) / 4 * 4

		buffer.set(JAVA_INT_UNALIGNED, at, length.toInt())

		return at + length
	}

	@Test
	fun recordsAreReadAsTheKernelLaysThemOut() {
		Arena.ofConfined().use { arena ->
			val buffer = arena.allocate(1024, 8)
			var at = record(buffer, 0, "a.txt", 1, 11)

			at = record(buffer, at, "Folder é", 2, 12)
			at = record(buffer, at, "link", 5, 13)
			// One whose file system said nothing of what it is, nor its number.
			record(buffer, at, "bare", 0, 0, 0x80000000.toInt() or 0x1)

			val read = ArrayList<BulkEntry>()

			BulkFolder.records(buffer, 4, read::add)

			assertEquals(
				listOf("a.txt", "Folder é", "link", "bare"),
				read.map { it.name.decodeToString() },
			)
			assertEquals(
				listOf(FileKind.Regular, FileKind.Directory, FileKind.Symlink, null),
				read.map { it.kind },
			)
			assertEquals(listOf(11L, 12L, 13L, 0L), read.map { it.id })
		}
	}

	@Test
	@EnabledOnOs(OS.MAC)
	fun aFolderIsReadWithWhatEachEntryIs(
		@TempDir folder: Path,
	) {
		Files.createFile(folder.resolve("file"))
		Files.createDirectory(folder.resolve("inner"))
		Files.createSymbolicLink(folder.resolve("link"), folder.resolve("file"))
		repeat(3000) { Files.createFile(folder.resolve("many-$it")) }

		val read = BulkFolder.entries(folder.toString().encodeToByteArray())!!
		val byName = read.associateBy { it.name.decodeToString() }

		assertEquals(3003, read.size)
		assertEquals(FileKind.Regular, byName.getValue("file").kind)
		assertEquals(FileKind.Directory, byName.getValue("inner").kind)
		assertEquals(FileKind.Symlink, byName.getValue("link").kind)
		assertEquals(
			Files.getAttribute(folder.resolve("file"), "unix:ino"),
			byName.getValue("file").id,
		)
		assertNull(BulkFolder.entries(folder.resolve("nowhere").toString().encodeToByteArray()))
	}
}
