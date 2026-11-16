package prism.macos

import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/** FSEvents on a Mac: a file made, renamed and removed in a folder watched, each said by its path, and the stream closed. */
@EnabledOnOs(OS.MAC)
class FsEventsTest {
	@Test
	fun whatIsMadeRenamedAndRemovedIsSaid() {
		val folder = Files.createTempDirectory("prism-fsevents").toRealPath()
		val seen = LinkedBlockingQueue<FsEvent>()
		val failures = LinkedBlockingQueue<Throwable>()

		val stream =
			EventStream.start(
				listOf(folder),
				latency = 0.05,
				failed = failures::put,
			) { batch -> batch.forEach(seen::put) }

		assertNotNull(stream)
		stream!!.use {
			val made = Files.writeString(folder.resolve("made.txt"), "x")
			val renamed = Files.move(made, folder.resolve("renamed.txt"))

			Files.delete(renamed)

			fun awaited(what: (FsEvent) -> Boolean): FsEvent? =
				generateSequence { seen.poll(10, TimeUnit.SECONDS) }.firstOrNull(what)

			assertNotNull(
				awaited {
					it.path.fileName.toString() == "made.txt" &&
						FsFlag.Created in it.flags
				},
			)
			assertNotNull(
				awaited {
					it.path.fileName.toString() == "renamed.txt" &&
						FsFlag.Removed in it.flags
				},
			)
		}
		assertTrue(failures.isEmpty(), "$failures")
		assertTrue(EventStream.currentEventId() > 0)
	}
}
