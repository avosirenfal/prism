package prism.watcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import prism.platform.Dev
import prism.platform.FileId
import prism.platform.FileKind
import prism.platform.KernelDev
import prism.probe.Change

/** What a record of the ring is made into: each kind of change with what it has to say, and nothing else's. */
class ObservedTest {
	private val dev = KernelDev(Dev(8, 1))
	private val entry = Present(7, FileKind.Regular)

	@Test
	fun everyChangeTheProbeSendsIsSomethingDoneThatSaysTheSameChange() {
		for (change in Change.entries)
			assertEquals(change, Done.of(change, dev, to = 20, entry).change, "$change")
	}

	@Test
	fun onlyAMoveHasSomewhereItWentAndOneThatWentNowhereIsNoChange() {
		assertEquals(
			Done.Moved(FileId(dev, 20), swapped = false, entry),
			Done.of(Change.Moved, dev, to = 20, entry),
		)
		assertEquals(
			Done.Moved(FileId(dev, 20), swapped = true, entry),
			Done.of(Change.Exchanged, dev, to = 20, entry),
		)
		// A move the record has no destination of is still a change to the folder it left.
		assertEquals(Done.Left(swapped = false), Done.of(Change.Moved, dev, to = 0, entry))
		assertEquals(Done.Left(swapped = true), Done.of(Change.Exchanged, dev, to = 0, entry))
		// Whatever a record of anything else has where a move's destination would be, it is not read.
		assertEquals(
			Done.Made(Done.Making.File, entry),
			Done.of(Change.FileMade, dev, to = 20, entry),
		)
		assertEquals(
			Done.Removed(Done.Removing.Directory),
			Done.of(Change.DirectoryRemoved, dev, to = 0, Present(0, null)),
		)
	}

	@Test
	fun aNameIsItsBytesWhateverIsDoneToTheArrayItWasMadeFrom() {
		val bytes = "a.txt".encodeToByteArray()
		val name = EntryName(bytes)

		bytes[0] = 'b'.code.toByte()
		name.bytes()[0] = 'c'.code.toByte()

		assertEquals(EntryName("a.txt".encodeToByteArray()), name)
		assertEquals("a.txt", name.toString())
		assertNotEquals(EntryName(bytes), name)
	}
}
