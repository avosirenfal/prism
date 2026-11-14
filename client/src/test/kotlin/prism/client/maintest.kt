package prism.client

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.cli.Ran

/**
 * The client, as the program a shell starts: what it does itself, what it asks of the prism listening, and what it
 * leaves for the Java prism, which is everything nobody answered.
 */
class ClientTest {
	@TempDir
	lateinit var folder: Path

	private val caller = Caller(Path.of("/somewhere"), mapOf(Caller.ACTIVATION to "token"))

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

	@Test
	fun itsWordsAreAskedOfThePrismListeningAsTheyWereTyped() =
		listening({ Reply.Done(Ran("windows: 1 open", "", 0)) }) { place, asked ->
			val words = listOf("explore", "--isolated", "/tmp", "-x")

			assertEquals(Ran("windows: 1 open", "", 0), answered(words, place, caller))
			assertEquals(listOf(Request(words, caller)), asked.toList())
			// No words at all are asked too: that is a window on the home folder.
			assertEquals(Ran("windows: 1 open", "", 0), answered(emptyList(), place, caller))
		}

	@Test
	fun whatNobodyAnswersAndHelpAreTheJavaPrisms() {
		// The prism listening cannot: the Java prism's.
		listening({
			Reply.Unable("explore was asked to run in a separate process")
		}) { place, asked ->
			assertNull(answered(listOf("explore", "--isolated"), place, caller))
			assertEquals(1, asked.size)
			// Help is not asked for: no prism gives another's.
			assertNull(answered(listOf("explore", "--help"), place, caller))
			assertNull(answered(listOf("-h"), place, caller))
			assertEquals(1, asked.size)
			// After two dashes it is a word like any other.
			assertNull(answered(listOf("explore", "--", "--help"), place, caller))
			assertEquals(2, asked.size)
		}
		// Nobody listens, or there is nowhere to listen.
		assertNull(
			answered(listOf("status"), InstancePlace.within(folder.resolve("none")), caller),
		)
		assertNull(answered(listOf("status"), null, caller))
	}

	@Test
	fun searchIsDoneHereAndWithNobodyToAskIsHandedOver() {
		// Its help is its own, under prism's name.
		assertTrue(
			answered(listOf("search", "--help"), null, caller)!!
				.out
				.startsWith("usage: prism search QUERY... [OPTIONS]"),
		)
		// A mistake in its words is said here; a search itself, with no prism to ask, is the Java prism's.
		assertEquals(
			"prism search: missing query",
			answered(listOf("search"), null, caller)!!.err,
		)
	}

	@Test
	fun theJavaPrismIsTheOneNamedOrTheOneInstalledBesideThisProgram() {
		fun program(path: String): Path =
			folder.resolve(path).also {
				it.parent.createDirectories()
				it.writeText("#!/bin/sh\n")
				Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x"))
			}

		val client = program("out/bin/prism")

		assertNull(javaPrism(emptyMap(), client))

		val beside = program("out/$JAVA_PRISM_INSTALLED")

		assertEquals(beside, javaPrism(emptyMap(), client))

		val named = program("elsewhere/prism-java")

		assertEquals(named, javaPrism(mapOf(JAVA_PRISM to named.toString()), client))
		assertEquals(named, javaPrism(mapOf(JAVA_PRISM to named.toString()), null))
		// One that is not there, or cannot be run, is none.
		assertNull(javaPrism(mapOf(JAVA_PRISM to "$folder/nothing"), client))
		assertNull(javaPrism(emptyMap(), null))
	}
}
