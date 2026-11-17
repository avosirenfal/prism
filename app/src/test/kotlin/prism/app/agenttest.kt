package prism.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The daemon's launchd agent, as its property list is written: what runs it, where it says what it says, and whose it is. */
class AgentTest {
	@Test
	fun theAgentRunsTheDaemonInTheForegroundAtLoginAndAgainWhereItFails() {
		val plist =
			agentPlist(
				listOf("/Applications/prism.app/Contents/MacOS/prism"),
				Path.of("/Users/a/Library/Application Support/prism/daemon.log"),
			)

		assertTrue("<string>dev.prism.daemon</string>" in plist, plist)
		assertTrue(
			"\t\t<string>/Applications/prism.app/Contents/MacOS/prism</string>\n" +
				"\t\t<string>daemon</string>\n\t\t<string>--foreground</string>" in plist,
			plist,
		)
		assertTrue("<key>RunAtLoad</key>\n\t<true/>" in plist, plist)
		assertTrue("<key>SuccessfulExit</key>\n\t\t<false/>" in plist, plist)
		assertTrue("<string>Aqua</string>" in plist, plist)
		assertTrue(
			"<string>/Users/a/Library/Application Support/prism/daemon.log</string>" in plist,
			plist,
		)
		assertTrue(WRITTEN_BY_AGENT in plist)
		assertTrue(
			"<string>a &amp; b &lt;c&gt;</string>" in
				agentPlist(listOf("a & b <c>"), Path.of("/l")),
		)
	}

	private fun build(at: Long) = Build(FileTime.fromMillis(at), 100)

	@Test
	fun aDaemonStaysWhileItsApplicationIsTheOneItWasStartedFrom() {
		assertEquals(Fate.Stay, fateOf(build(1), emptyList()))
		assertEquals(Fate.Stay, fateOf(build(1), List(20) { build(1) }))
	}

	@Test
	fun aDaemonEndsToBeStartedAgainOnceAnotherApplicationHasSettledInItsPlace() {
		// Being copied: gone, then there and still changing.
		assertEquals(Fate.Stay, fateOf(build(1), listOf(build(1), null, build(2))))
		assertEquals(Fate.Stay, fateOf(build(1), listOf(null, build(2), build(3))))
		assertEquals(Fate.Renew, fateOf(build(1), listOf(null, build(2), build(3), build(3))))
	}

	@Test
	fun aDaemonEndsForGoodOnceItsApplicationHasBeenGoneAMinute() {
		assertEquals(Fate.Stay, fateOf(build(1), List(11) { null }))
		assertEquals(Fate.End, fateOf(build(1), List(12) { null }))
		assertEquals(Fate.Stay, fateOf(build(1), List(11) { null } + build(1)))
	}

	@Test
	fun theCommandIsLinkedOnceAndNothingButALinkIsReplaced(
		@TempDir folder: Path,
	) {
		val target = Files.createFile(folder.resolve("client"))
		val link = folder.resolve("bin/prism")

		assertEquals(Linked.Made, linkCli(target, link))
		assertEquals(target, Files.readSymbolicLink(link))
		assertEquals(Linked.AsItWas, linkCli(target, link))

		// The application moved: the link follows it.
		val moved = Files.createFile(folder.resolve("moved"))

		assertEquals(Linked.Made, linkCli(moved, link))
		assertEquals(moved, Files.readSymbolicLink(link))

		Files.delete(link)
		Files.createFile(link)
		assertEquals(Linked.NotALink, linkCli(target, link))
	}
}
