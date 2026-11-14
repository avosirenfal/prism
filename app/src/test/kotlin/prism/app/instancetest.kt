package prism.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import prism.cli.Ran
import prism.client.Asking
import prism.client.Caller
import prism.client.InstancePlace
import prism.client.Reply
import prism.client.Request
import prism.client.Wire
import prism.client.ask

/**
 * One prism for a session's windows: what one prism asks another as it is written and read, who listens where, a
 * prism that asks being answered, and two started at once ending as one that listens and one that asked it.
 */
class InstanceTest {
	@TempDir
	lateinit var folder: Path

	private val place get() = InstancePlace.within(folder.resolve("prism"))

	private val request =
		Request(
			listOf("explore", "--", "docs/ä b", ""),
			Caller(Path("/home/a/work"), mapOf(Caller.ACTIVATION to "token-1")),
		)

	private fun written(write: (DataOutputStream) -> Unit): DataInputStream =
		ByteArrayOutputStream()
			.also { write(DataOutputStream(it)) }
			.toByteArray()
			.let { DataInputStream(ByteArrayInputStream(it)) }

	@Test
	fun whatIsAskedAndAnsweredIsReadAsItWasWritten() {
		assertEquals(Wire.Asked.Read(request), Wire.asked(written { Wire.write(it, request) }))

		for (reply in listOf(
			Reply.Done(Ran("chosen\nlines", "a warning", 3)),
			Reply.Done(Ran("", "", 0)),
			Reply.Unable("no display"),
		))
			assertEquals(reply, Wire.reply(written { Wire.write(it, reply) }))
	}

	@Test
	fun aPrismOfAnotherVersionIsKnownForOneAndWhatIsNoPrismsIsRefused() {
		// The mark and another version, then whatever that version writes: not read as this one's.
		val other =
			written {
				it.writeInt(0x5052534D)
				it.writeInt(Wire.VERSION + 1)
				it.writeUTF("whatever a later version says")
			}

		assertEquals(Wire.Asked.Other(Wire.VERSION + 1), Wire.asked(other))

		val answer =
			written {
				it.writeInt(0x5052534D)
				it.writeInt(Wire.VERSION + 1)
			}

		assertInstanceOf(Reply.Unable::class.java, Wire.reply(answer))

		// Not a prism's at all; and a prism's that says it holds more than any command line does.
		assertThrows<IOException> {
			Wire.asked(written { it.writeBytes("GET / HTTP/1.1\r\n\r\n") })
		}
		assertThrows<IOException> {
			Wire.asked(
				written {
					it.writeInt(0x5052534D)
					it.writeInt(Wire.VERSION)
					it.writeInt(Int.MAX_VALUE)
				},
			)
		}
	}

	@Test
	fun aCallersPathsAreReadFromWhereTheyWereSaid() {
		val caller = Caller(Path("/home/a/work"), emptyMap())

		assertEquals(Path("/home/a/work/docs"), caller.resolved(Path("docs")))
		assertEquals(Path("/home/a/notes"), caller.resolved(Path("../notes")))
		assertEquals(Path("/etc"), caller.resolved(Path("/etc")))
		assertEquals(null, caller.activation)
		// Of an environment, only what goes with a request is carried.
		assertEquals(
			mapOf(Caller.ACTIVATION to "t"),
			Caller
				.here(
					mapOf(Caller.ACTIVATION to "t", "HOME" to "/home/a", "SECRET" to "s"),
				).environment,
		)
	}

	@Test
	fun aPrismThatListensAnswersOneThatAsksAndNobodyAnswersWhereNoneListens() {
		assertEquals(Asking.Nobody, ask(place, request))

		val asked = CopyOnWriteArrayList<Request>()
		val claim =
			Listening.claim(place) {
				asked += it
				Reply.Done(Ran("opened", "", 0))
			}

		assertInstanceOf(Claim.Got::class.java, claim).listening.use {
			assertEquals(Asking.Answered(Reply.Done(Ran("opened", "", 0))), ask(place, request))
			assertEquals(listOf(request), asked.toList())

			// The place is this one's: no other takes it while it is held.
			assertEquals(Claim.Held, Listening.claim(place) { Reply.Unable("never asked") })
		}

		// Closed, its socket is gone, nobody answers, and the place can be taken again.
		assertFalse(Files.exists(place.socket))
		assertEquals(Asking.Nobody, ask(place, request))
		assertInstanceOf(
			Claim.Got::class.java,
			Listening.claim(place) {
				Reply.Unable("")
			},
		).listening.close()
	}

	@Test
	fun whatAnsweringThrowsIsAnsweredAsBeingUnableAndTheNextIsAnsweredAllTheSame() {
		var calls = 0

		val listening =
			assertInstanceOf(
				Claim.Got::class.java,
				Listening.claim(place) {
					if (calls++ == 0)
						error("a mistake in answering")
					else
						Reply.Done(Ran("fine", "", 0))
				},
			).listening

		listening.use {
			val first = assertInstanceOf(Asking.Answered::class.java, ask(place, request)).reply

			assertTrue(
				"a mistake in answering" in assertInstanceOf(Reply.Unable::class.java, first).why,
			)
			assertEquals(Asking.Answered(Reply.Done(Ran("fine", "", 0))), ask(place, request))
		}
	}

	@Test
	fun aSocketLeftByAPrismThatWasKilledIsNobodysAndIsTaken() {
		// A socket file with nobody behind it, as a prism killed outright leaves: no lock is held on the place.
		Files.createDirectories(place.socket.parent)
		Files.createFile(place.socket)

		assertEquals(Asking.Nobody, ask(place, request))
		assertInstanceOf(
			Claim.Got::class.java,
			Listening.claim(place) {
				Reply.Done(Ran("", "", 0))
			},
		).listening.use {
			assertInstanceOf(Asking.Answered::class.java, ask(place, request))
		}
	}

	@Test
	fun oneThatAsksAPrismThatDoesNotAnswerGivesUp() {
		val release = CountDownLatch(1)

		val listening =
			assertInstanceOf(
				Claim.Got::class.java,
				Listening.claim(place) {
					release.await(10, TimeUnit.SECONDS)
					Reply.Unable("too late")
				},
			).listening

		listening.use {
			assertEquals(Asking.Nobody, ask(place, request, patience = 200.milliseconds))
			release.countDown()
		}
	}

	@Test
	fun twoStartedAtOnceEndAsOneThatListensAndOneThatAskedIt() {
		val standings = CopyOnWriteArrayList<Standing>()
		val go = CountDownLatch(1)

		val both =
			(1..2).map {
				thread {
					go.await()
					standings += stand(place, request) { Reply.Done(Ran("from the other", "", 0)) }
				}
			}

		go.countDown()
		both.forEach { it.join(20_000) }

		val alone = standings.filterIsInstance<Standing.Alone>()
		val asked = standings.filterIsInstance<Standing.Asked>()

		try {
			assertEquals(1, alone.size, standings.toString())
			assertTrue(alone.single().listening != null)
			assertEquals(listOf(Standing.Asked(Reply.Done(Ran("from the other", "", 0)))), asked)
		} finally {
			alone.forEach { it.listening?.close() }
		}
	}

	@Test
	fun whereThereIsNoRuntimeFolderThereIsNoPlace() {
		assertEquals(null, InstancePlace.of(emptyMap()))
		assertEquals(null, InstancePlace.of(mapOf("XDG_RUNTIME_DIR" to "relative")))
		assertEquals(
			InstancePlace(
				Path("/run/user/1000/prism/prism.sock"),
				Path("/run/user/1000/prism/prism.lock"),
			),
			InstancePlace.of(mapOf("XDG_RUNTIME_DIR" to "/run/user/1000")),
		)
	}
}
