package prism.window

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What a Mac's drag carries: the files an offer names, read back from the list of their addresses it offers. */
class CocoaDragsTest {
	@Test
	fun theFilesOfAnOfferAreReadFromItsAddresses() {
		val files = listOf(Path.of("/Users/a/My Report.pdf"), Path.of("/Users/a/été.txt"))

		assertEquals(files, CocoaDrags.filesOf(Outgoing(local = files, files = files).offer))
		assertEquals(
			emptyList<Path>(),
			CocoaDrags.filesOf(Outgoing(local = "x", files = emptyList(), text = "x").offer),
		)
	}
}
