package prism.portal

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import prism.dbus.AnyVariant
import prism.dbus.Connection
import prism.dbus.Method
import prism.dbus.ObjectPath
import prism.dbus.PrivateBus
import prism.dbus.Reply
import prism.dbus.method
import prism.dbus.orThrow

/**
 * The file chooser and the file manager, served on a bus of the tests' own and called as the portal calls them: by
 * this library, and by GLib's `gdbus`, whose way of writing a request (its variants, its byte strings) is the portal's
 * own, since the portal is GLib's too.
 */
class FileChooserTest {
	/** [test], with prism's backend served on one connection to a private bus and a caller on another. */
	private fun served(
		choose: suspend (ChooserRequest) -> Picked,
		test: suspend (bus: PrivateBus, caller: Connection) -> Unit,
	) = runBlocking {
		PrivateBus.start().use { bus ->
			val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

			bus.connect().use { backend ->
				assertEquals(Owning.Owned, backend.own(FileChooser.BACKEND))

				backend.serveFileChooser(scope, choose).use {
					bus.connect().use { caller -> withTimeout(20.seconds) { test(bus, caller) } }
				}
			}
			scope.cancel()
		}
	}

	private val openFile =
		method<ChooserCall, ChooserAnswer>(
			FileChooser.BACKEND,
			FileChooser.PATH,
			FileChooser.INTERFACE,
			"OpenFile",
		)
	private val saveFile =
		method<ChooserCall, ChooserAnswer>(
			FileChooser.BACKEND,
			FileChooser.PATH,
			FileChooser.INTERFACE,
			"SaveFile",
		)
	private val saveFiles =
		method<ChooserCall, ChooserAnswer>(
			FileChooser.BACKEND,
			FileChooser.PATH,
			FileChooser.INTERFACE,
			"SaveFiles",
		)

	/** A path as the portal sends one: its bytes, and a NUL. */
	private fun bytes(path: String): AnyVariant = AnyVariant.of(path.encodeToByteArray() + 0)

	@Test
	fun aRequestIsReadIntoWhatItAsksAndTheChoiceAnswered() {
		val asked = CompletableDeferred<ChooserRequest>()

		served({ request ->
			asked.complete(request)
			Picked.Chose(
				listOf(Path.of("/home/avery/a b.png"), Path.of("/home/avery/ü.txt")),
				FileFilter("Images", listOf(FilterRule(0u, "*.png"))),
				listOf(Chosen("encoding", "utf8")),
			)
		}) { _, caller ->
			val answer =
				caller
					.call(
						openFile,
						ChooserCall(
							ObjectPath("/org/freedesktop/portal/desktop/request/1_2/t"),
							"org.example.App",
							"x11:1a2b",
							"Open a picture",
							mapOf(
								"multiple" to AnyVariant.of(true),
								"accept_label" to AnyVariant.of("_Open it"),
								"filters" to
									AnyVariant.of(
										listOf(
											FileFilter(
												"Images",
												listOf(FilterRule(0u, "*.png"), FilterRule(1u, "image/jpeg")),
											),
											FileFilter("All", listOf(FilterRule(0u, "*"))),
										),
									),
								"current_filter" to
									AnyVariant.of(FileFilter("Images", listOf(FilterRule(0u, "*.png")))),
								"current_folder" to bytes("/home/avery/Pictures"),
								"choices" to
									AnyVariant.of(
										listOf(
											Choice("encoding", "Encoding", listOf(ChoiceOption("utf8", "UTF-8")), "utf8"),
										),
									),
								// Of the wrong type: taken as not given, never a failure.
								"directory" to AnyVariant.of("yes"),
							),
						),
					).orThrow()

			val request = asked.await()
			val opening = request.asked as Asked.Opening

			assertEquals(AppId.of("org.example.App"), request.app)
			assertEquals(Parent.X11(0x1a2b), request.parent)
			assertEquals("Open a picture", opening.title)
			assertEquals("Open it", opening.acceptLabel)
			assertTrue(opening.multiple)
			assertEquals(false, opening.directory)
			assertEquals(listOf("Images", "All"), opening.filters.map { it.name })
			assertEquals("Images", opening.currentFilter?.name)
			assertEquals(Path.of("/home/avery/Pictures"), opening.folder)
			assertEquals("encoding", opening.choices.single().id)

			assertEquals(FileChooser.SUCCESS, answer.response)
			assertEquals(
				listOf("file:///home/avery/a%20b.png", "file:///home/avery/%C3%BC.txt"),
				answer.results
					.getValue("uris")
					.decode<List<String>>()
					.orThrow(),
			)
			assertEquals(
				"Images",
				answer.results
					.getValue("current_filter")
					.decode<FileFilter>()
					.orThrow()
					.name,
			)
			assertEquals(
				listOf(Chosen("encoding", "utf8")),
				answer.results
					.getValue("choices")
					.decode<List<Chosen>>()
					.orThrow(),
			)
		}
	}

	private fun <T> prism.dbus.Decoded<T>.orThrow(): T =
		(this as prism.dbus.Decoded.Read).value

	@Test
	fun savingIsAskedWithANameAFolderAndTheFileSavedOverAndManyWithTheirNames() {
		val asked = CopyOnWriteArrayList<Asked>()

		served({ request ->
			asked += request.asked
			Picked.Cancelled
		}) { _, caller ->
			val one =
				caller
					.call(
						saveFile,
						ChooserCall(
							ObjectPath("/org/freedesktop/portal/desktop/request/1_2/s"),
							"",
							"",
							"Save",
							mapOf(
								"current_name" to AnyVariant.of("report.pdf"),
								"current_folder" to bytes("/tmp"),
								"current_file" to bytes("/tmp/report.pdf"),
							),
						),
					).orThrow()

			val many =
				caller
					.call(
						saveFiles,
						ChooserCall(
							ObjectPath("/org/freedesktop/portal/desktop/request/1_2/m"),
							"",
							"wayland:abc",
							"Save these",
							mapOf(
								"files" to
									AnyVariant.of(
										listOf(
											"a.txt".encodeToByteArray() + 0,
											"b.txt".encodeToByteArray() + 0,
										),
									),
							),
						),
					).orThrow()

			assertEquals(FileChooser.CANCELLED, one.response)
			assertEquals(FileChooser.CANCELLED, many.response)
			assertEquals(
				Asked.Saving(
					"Save",
					null,
					emptyList(),
					emptyList(),
					null,
					"report.pdf",
					Path.of("/tmp"),
					Path.of("/tmp/report.pdf"),
				),
				asked[0],
			)
			assertEquals(listOf("a.txt", "b.txt"), (asked[1] as Asked.SavingMany).names)
		}
	}

	@Test
	fun thePortalTakingTheDialogAwayCancelsIt() {
		val closed = CompletableDeferred<Unit>()
		val handle = ObjectPath("/org/freedesktop/portal/desktop/request/1_2/c")
		val close =
			method<Unit, Unit>(FileChooser.BACKEND, handle.value, FileChooser.REQUEST, "Close")

		served({ _ ->
			try {
				awaitCancellation()
			} finally {
				closed.complete(Unit)
			}
		}) { _, caller ->
			val answer =
				CoroutineScope(Dispatchers.Default).async {
					caller.call(openFile, ChooserCall(handle, "", "", "", emptyMap()))
				}

			// The Request object is there for as long as the dialog is.
			var reply: Reply<Unit>

			do {
				delay(20)
				reply = caller.call(close, Unit)
			} while (reply !is Reply.Returned)

			closed.await()
			assertEquals(FileChooser.CANCELLED, answer.await().orThrow().response)
			// And gone after.
			assertTrue(caller.call(close, Unit) is Reply.Failed)
		}
	}

	@Test
	fun gdbusCallsItAsThePortalDoes() {
		val asked = CompletableDeferred<ChooserRequest>()

		served({ request ->
			asked.complete(request)
			Picked.Chose(listOf(Path.of("/tmp/a.png")))
		}) { bus, _ ->
			val gdbus =
				try {
					ProcessBuilder(
						"gdbus",
						"call",
						"--address",
						bus.address,
						"--dest",
						FileChooser.BACKEND,
						"--object-path",
						FileChooser.PATH,
						"--method",
						"${FileChooser.INTERFACE}.OpenFile",
						// Typed, as the portal's generated proxy types it: gdbus guesses a string where it cannot introspect.
						"objectpath '/org/freedesktop/portal/desktop/request/1_9/g'",
						"org.gnome.TextEditor",
						"x11:3c00007",
						"Open",
						"{'multiple': <true>, 'directory': <false>, " +
							"'filters': <[('Images', [(uint32 0, '*.png'), (uint32 1, 'image/png')])]>, " +
							"'current_folder': <b'/tmp'>, 'accept_label': <'_Open'>}",
					).redirectErrorStream(true).start()
				} catch (missing: java.io.IOException) {
					null
				}

			assumeTrue(gdbus != null, "no gdbus")

			val said = gdbus!!.inputStream.bufferedReader().readText()

			gdbus.waitFor(10, TimeUnit.SECONDS)
			assertTrue(asked.isCompleted, "gdbus said: $said")

			val opening = asked.await().asked as Asked.Opening

			assertEquals(Path.of("/tmp"), opening.folder)
			assertEquals("Open", opening.acceptLabel)
			assertEquals(
				listOf(FilterRule(0u, "*.png"), FilterRule(1u, "image/png")),
				opening.filters.single().rules,
			)
			assertEquals(Parent.X11(0x3c00007), asked.await().parent)
			assertTrue("(uint32 0, {'uris': <['file:///tmp/a.png']>})" in said, said)
		}
	}

	@Test
	fun theFileManagerIsAskedToShowFoldersItemsAndProperties() =
		runBlocking {
			PrivateBus.start().use { bus ->
				val shown = CopyOnWriteArrayList<Showing>()

				bus.connect().use { manager ->
					assertEquals(Owning.Owned, manager.own(FileManager.NAME))
					manager.serveFileManager { shown += it }.use {
						bus.connect().use { caller ->
							assertTrue(
								caller.showInFileManager(listOf(Path.of("/tmp"), Path.of("/etc/hostname"))),
							)
							caller
								.call(
									method<ShowCall, Unit>(
										FileManager.NAME,
										FileManager.PATH,
										FileManager.INTERFACE,
										"ShowItemProperties",
									),
									ShowCall(listOf("file:///etc/hosts", "https://example.com/not-a-file"), ""),
								).orThrow()
						}
					}
				}

				assertEquals(
					listOf(
						Showing.Folders(listOf(Path.of("/tmp"))),
						Showing.Items(listOf(Path.of("/etc/hostname"))),
						Showing.Properties(listOf(Path.of("/etc/hosts"))),
					),
					shown.toList(),
				)
			}
		}

	@Test
	fun aNameAnotherHasIsNotTakenUnlessItLetsItBeAndThisAsksTo() =
		runBlocking {
			PrivateBus.start().use { bus ->
				bus.connect().use { first ->
					bus.connect().use { second ->
						assertEquals(Owning.Owned, first.own(FileManager.NAME))
						assertEquals(Owning.Taken, second.own(FileManager.NAME))
						// prism lets its names be replaced, so another (or a prism started again) can take them.
						assertEquals(Owning.Owned, second.own(FileManager.NAME, replace = true))
					}
				}
			}
		}

	@Test
	fun labelsAndParentsAndUrisAreReadAndWrittenAsThePortalHasThem() {
		assertEquals("Open", Asked.withoutMnemonics("_Open"))
		assertEquals("Save_as", Asked.withoutMnemonics("Save__as"))
		assertEquals(Parent.None, Parent.of(""))
		assertEquals(Parent.None, Parent.of("x11:zz"))
		assertEquals(Parent.Wayland("~h"), Parent.of("wayland:~h"))
		assertEquals("file:///tmp", uriOf(Path.of("/tmp")))
		assertEquals(Path.of("/tmp/a b"), pathOf("file:///tmp/a%20b"))
		assertNull(pathOf("https://example.com/"))
		assertEquals(
			listOf(Chosen("a", "x"), Chosen("b", "false")),
			initialChoices(
				listOf(
					Choice("a", "A", listOf(ChoiceOption("x", "X")), ""),
					Choice("b", "B", emptyList(), ""),
				),
			),
		)
	}
}
