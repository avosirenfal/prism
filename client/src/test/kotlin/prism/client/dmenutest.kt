package prism.client

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.cli.Invocation
import prism.cli.Outcome
import prism.cli.Ran
import prism.cli.execute
import prism.cli.invocation

/**
 * `prism dmenu`, as the program that reads the lines and asks: what its options are read as, what it asks the prism
 * listening (its words said again, and what was on standard input), and who it leaves it to where nobody answers.
 */
class DmenuCommandTest {
	@TempDir
	lateinit var folder: Path

	private val caller = Caller(Path.of("/somewhere"), mapOf(Caller.ACTIVATION to "token"))
	private val lines = "25%\n50%\n75%\n100%\n"

	/** What `prism dmenu` came to, what it said, and what was left to whoever does it where nobody answers. */
	private class Run(
		val ran: Ran,
		val said: List<String>,
		val left: List<Request>,
	)

	private fun dmenu(place: InstancePlace?, vararg words: String): Run {
		val said = ArrayList<String>()
		val left = ArrayList<Request>()

		val command =
			DmenuCommand(
				otherwise = {
					left += it
					Outcome("shown here")
				},
				place = { place },
				input = { lines },
				caller = { caller },
				err = { said += it },
			)

		return Run(execute(command, words.toList()), said, left)
	}

	/** A prism listening in [folder], answering each request with [answer], for as long as [test] runs. */
	private fun <T> listening(
		answer: (Request) -> Reply,
		test: (InstancePlace, List<Request>) -> T,
	): T {
		val place = InstancePlace.within(folder.resolve("run"))
		val asked = CopyOnWriteArrayList<Request>()

		place.socket.parent.createDirectories()

		return ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
			server.bind(UnixDomainSocketAddress.of(place.socket))

			val serving =
				Thread.ofPlatform().daemon().start {
					runCatching {
						while (true)
							server.accept().use { asker ->
								val read =
									Wire.asked(
										DataInputStream(BufferedInputStream(Channels.newInputStream(asker))),
									) as Wire.Asked.Read

								asked += read.request
								Wire.write(
									DataOutputStream(BufferedOutputStream(Channels.newOutputStream(asker))),
									answer(read.request),
								)
							}
					}
				}

			try {
				test(place, asked)
			} finally {
				server.close()
				serving.join(1000)
			}
		}
	}

	private fun optionsOf(vararg words: String): DmenuOptions {
		val command = DmenuCommand(otherwise = { error("nothing was to be shown") })

		return command.options((invocation(command, words.toList()) as Invocation.Run).args)
	}

	@Test
	fun itsOptionsAreVicinaesByTheSameNamesShortAndLong() {
		assertEquals(DmenuOptions(), optionsOf())
		assertEquals(
			DmenuOptions(placeholder = "Window size..."),
			optionsOf("--placeholder", "Window size..."),
		)
		assertEquals(
			DmenuOptions(
				placeholder = "Pick",
				query = "fi",
				section = "Files ({count})",
				title = "Open",
				format = DmenuFormat.Index,
				width = 500,
				height = 300,
				footer = false,
				redacted = true,
			),
			optionsOf(
				"-p",
				"Pick",
				"-q",
				"fi",
				"-s",
				"Files ({count})",
				"-n",
				"Open",
				"-f",
				"index",
				"-W",
				"500",
				"-H",
				"300",
				"--no-footer",
				"--password",
			),
		)
		// A title asked for and then not: there is none. What Vicinae says of its preview is taken, and does nothing.
		assertEquals(
			DmenuOptions(),
			optionsOf(
				"--section-title",
				"Files",
				"--no-section",
				"--no-quick-look",
				"--no-metadata",
			),
		)
	}

	@Test
	fun thePrismListeningIsAskedWithTheWordsSaidAgainAndWhatWasOnStandardInput() =
		listening({ Reply.Done(Ran("75%", "", 0)) }) { place, asked ->
			val run =
				dmenu(place, "-p", "Window size...", "--format", "index", "--no-footer", "-W", "500")

			assertEquals(Ran("75%", "", 0), run.ran)
			assertEquals(emptyList<Request>(), run.left)
			assertEquals(
				listOf(
					Request(
						listOf(
							"dmenu",
							"--placeholder=Window size...",
							"--format=index",
							"--width=500",
							"--no-footer",
						),
						caller.copy(input = lines),
					),
				),
				asked.toList(),
			)
			// What it asks is read by the prism asked as it was meant here.
			assertEquals(
				optionsOf("-p", "Window size...", "--format", "index", "--no-footer", "-W", "500"),
				optionsOf(
					*asked
						.single()
						.words
						.drop(1)
						.toTypedArray(),
				),
			)
		}

	@Test
	fun aValueThatStartsWithADashIsSaidAgainAsAValue() =
		listening({ Reply.Done(Ran("", "", 1)) }) { place, asked ->
			assertEquals(1, dmenu(place, "--placeholder=-x- --", "-q", "-5").ran.status)
			assertEquals(
				optionsOf("--placeholder=-x- --", "--query=-5"),
				optionsOf(
					*asked
						.single()
						.words
						.drop(1)
						.toTypedArray(),
				),
			)
		}

	@Test
	fun whatThePrismAskedSaysAndItsStatusAreThisCommandsOwn() =
		listening({ Reply.Done(Ran("", "prism dmenu: no display", 1)) }) { place, _ ->
			val run = dmenu(place)

			assertEquals(Ran("", "", DmenuCommand.DISMISSED), run.ran)
			assertEquals(listOf("prism dmenu: no display"), run.said)
		}

	@Test
	fun whereNobodyAnswersItIsLeftToWhoeverMadeItWithWhatWasRead() {
		val nobody = dmenu(InstancePlace.within(folder.resolve("none")), "-p", "Pick")

		assertEquals("shown here", nobody.ran.out)
		assertEquals(
			listOf(Request(listOf("dmenu", "--placeholder=Pick"), caller.copy(input = lines))),
			nobody.left,
		)
		assertEquals(1, dmenu(null).left.size)
		// The prism listening cannot (it has no windows): left, too.
		listening({ Reply.Unable("no display to open windows on") }) { place, asked ->
			assertEquals("shown here", dmenu(place).ran.out)
			assertEquals(1, asked.size)
		}
	}

	@Test
	fun aMistakeInItsWordsIsSaidAndNothingIsAsked() {
		val run = dmenu(null, "--format", "json")

		assertEquals(1, run.ran.status)
		assertTrue(run.ran.err.contains("'json' is not one of data, index"), run.ran.err)
		assertEquals(emptyList<Request>(), run.left)
	}
}
