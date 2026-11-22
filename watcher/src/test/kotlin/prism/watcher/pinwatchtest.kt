package prism.watcher

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.linux.Sys

class PinWatchTest {
	@TempDir
	lateinit var mount: Path

	@Test
	fun whatALoaderDoesAtThePinsIsToldAsItHappens() {
		val pins = WatcherPins(mount)
		val told = LinkedBlockingQueue<Unit>()

		fun toldOnce() {
			assertNotNull(told.poll(5, TimeUnit.SECONDS), "told")
			// Each thing done told once, however many files it took: nothing more is told meanwhile.
			assertNull(told.poll(PinWatch.SETTLE * 2, TimeUnit.MILLISECONDS))
		}

		val watch = (PinWatch.start(pins) { told.put(Unit) } as Sys.Ok).value

		watch.use {
			// Nothing told until something happens.
			assertNull(told.poll(PinWatch.SETTLE * 2, TimeUnit.MILLISECONDS))

			// A watcher pinned: its folder made, and then its files in it, which the folder had to be watched for.
			Files.createDirectory(pins.folder)
			toldOnce()
			Files.createFile(Pin.ringIn(pins.folder))
			Files.createFile(pins.folder.resolve("lost"))
			toldOnce()

			// Taken away.
			Files.delete(Pin.ringIn(pins.folder))
			toldOnce()
		}
	}
}
