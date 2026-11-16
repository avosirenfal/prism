package prism.app

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
