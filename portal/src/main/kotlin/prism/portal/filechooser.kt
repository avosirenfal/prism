package prism.portal

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import prism.dbus.Answer
import prism.dbus.AnyVariant
import prism.dbus.Connection
import prism.dbus.InterfaceName
import prism.dbus.MemberName
import prism.dbus.Method
import prism.dbus.ObjectPath
import prism.dbus.Reply
import prism.dbus.method
import prism.dbus.orNull

/*
 * The file chooser, served: what the desktop portal asks a backend for when an application wants a file or a folder
 * (docs/portal.md). An application calls the portal's own `org.freedesktop.portal.FileChooser`; the portal
 * (xdg-desktop-portal) calls the backend the user has chosen for it, on `org.freedesktop.impl.portal.FileChooser`, with
 * the same request and a handle; the backend shows its dialog, and answers with what was chosen.
 *
 * Each method takes the same five things: the handle (an object path, at which the backend serves
 * `org.freedesktop.impl.portal.Request.Close` for as long as its dialog is open, so that the portal can take it away),
 * the application's id, the window it asks from, a title, and options by name, each a variant. It answers with a
 * response (0 chosen, 1 cancelled, 2 anything else) and results by name.
 *
 * Here a request is read into what it asks ([Asked]), each option as its type, an option of the wrong type taken as
 * not given, so the dialog is made from values that cannot be wrong; and what the dialog gives ([Picked]) is made into
 * the answer.
 */

/** A file filter as the portal sends it: its name, and its rules. */
@Serializable
data class FileFilter(
	val name: String,
	val rules: List<FilterRule>,
)

/** One rule of a filter: a glob of names (`*.png`), or a MIME type (`image/png`, or every image as `image/` and a star). */
@Serializable
data class FilterRule(
	val kind: UInt,
	val pattern: String,
) {
	val isGlob: Boolean get() = kind == GLOB
	val isMimeType: Boolean get() = kind == MIME_TYPE

	companion object {
		val GLOB = 0u
		val MIME_TYPE = 1u
	}
}

/** One of the extra controls an application asks for: a list to choose from, or, with no [options], a check box. */
@Serializable
data class Choice(
	val id: String,
	val label: String,
	val options: List<ChoiceOption>,
	val initial: String,
)

@Serializable
data class ChoiceOption(
	val id: String,
	val label: String,
)

/** What was chosen of a [Choice]: its id, and the option's id (or `true` or `false`, for a check box). */
@Serializable
data class Chosen(
	val id: String,
	val value: String,
)

/** A call of one of the file chooser's methods: what every one of them takes. */
@Serializable
data class ChooserCall(
	val handle: ObjectPath,
	val appId: String,
	val parentWindow: String,
	val title: String,
	val options: Map<String, AnyVariant>,
)

/** What every one of the file chooser's methods answers. */
@Serializable
data class ChooserAnswer(
	val response: UInt,
	val results: Map<String, AnyVariant>,
)

/** The window an application asks from, as the portal names it. */
sealed interface Parent {
	/** An X11 window, by its id. */
	data class X11(
		val window: Long,
	) : Parent

	/** A Wayland surface, by the handle `xdg-foreign` exported it under. */
	data class Wayland(
		val handle: String,
	) : Parent

	data object None : Parent

	companion object {
		/** [text] as the portal sends it: `x11:` and a window id in hexadecimal, `wayland:` and a handle, or nothing. */
		fun of(text: String): Parent =
			when {
				text.startsWith("x11:") ->
					text.removePrefix("x11:").toLongOrNull(16)?.let(::X11)
						?: None
				text.startsWith(
					"wayland:",
				) && text.length > "wayland:".length -> Wayland(text.removePrefix("wayland:"))
				else -> None
			}
	}
}

/** What a request asks for, its options read. */
sealed interface Asked {
	val title: String

	/** What the accept button says, where the application says; its mnemonic marks taken out. */
	val acceptLabel: String?
	val choices: List<Choice>

	/** One file to open, or several, or a folder (or several). */
	data class Opening(
		override val title: String,
		override val acceptLabel: String?,
		override val choices: List<Choice>,
		val multiple: Boolean,
		val directory: Boolean,
		val filters: List<FileFilter>,
		val currentFilter: FileFilter?,
		val folder: Path?,
	) : Asked

	/** A file to save under: a name in a folder, perhaps over a file that is there. */
	data class Saving(
		override val title: String,
		override val acceptLabel: String?,
		override val choices: List<Choice>,
		val filters: List<FileFilter>,
		val currentFilter: FileFilter?,
		val name: String?,
		val folder: Path?,
		/** The file being saved over, where the application says there is one. */
		val file: Path?,
	) : Asked

	/** A folder to save several files of these names in. */
	data class SavingMany(
		override val title: String,
		override val acceptLabel: String?,
		override val choices: List<Choice>,
		val folder: Path?,
		val names: List<String>,
	) : Asked

	companion object {
		/** What a call of [member] (`OpenFile`, `SaveFile`, `SaveFiles`) asks; null for a member that is none of them. */
		fun of(member: String, call: ChooserCall): Asked? {
			val options = Options(call.options)
			val choices = options.list<Choice>("choices")
			val label = options.text("accept_label")?.let(::withoutMnemonics)

			return when (member) {
				FileChooser.OPEN_FILE ->
					Opening(
						call.title,
						label,
						choices,
						multiple = options.flag("multiple") ?: false,
						directory = options.flag("directory") ?: false,
						filters = options.list("filters"),
						currentFilter = options.value<FileFilter>("current_filter"),
						folder = options.path("current_folder"),
					)
				FileChooser.SAVE_FILE ->
					Saving(
						call.title,
						label,
						choices,
						filters = options.list("filters"),
						currentFilter = options.value<FileFilter>("current_filter"),
						name = options.text("current_name")?.takeIf { it.isNotBlank() },
						folder = options.path("current_folder"),
						file = options.path("current_file"),
					)
				FileChooser.SAVE_FILES ->
					SavingMany(
						call.title,
						label,
						choices,
						folder = options.path("current_folder"),
						names =
							options
								.value<List<ByteArray>>("files")
								.orEmpty()
								.map {
									bytesToText(it)
								}.filter { it.isNotEmpty() },
					)
				else -> null
			}
		}

		/**
		 * [label] as a person reads it: GTK's mnemonic marks (an underscore before the letter Alt chooses it by) taken
		 * out, and a doubled underscore, which is one written, made one.
		 */
		fun withoutMnemonics(label: String): String =
			label.replace("__", "\u0000").replace("_", "").replace("\u0000", "_")

		/** A path as the portal sends one: its bytes, ended by a NUL. */
		internal fun bytesToText(bytes: ByteArray): String =
			bytes.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
	}
}

/** Options by name, each read as its type; one that is not there, or not of that type, is null. */
private class Options(
	private val options: Map<String, AnyVariant>,
) {
	inline fun <reified T> value(name: String): T? = options[name]?.decode<T>()?.orNull()

	inline fun <reified T> list(name: String): List<T> = value<List<T>>(name).orEmpty()

	fun flag(name: String): Boolean? = value<Boolean>(name)

	fun text(name: String): String? = value<String>(name)

	fun path(name: String): Path? =
		value<ByteArray>(name)?.let(Asked::bytesToText)?.takeIf { it.isNotEmpty() }?.let {
			try {
				Path.of(it)
			} catch (bad: InvalidPathException) {
				null
			}
		}
}

/** What the dialog gave. */
sealed interface Picked {
	/** [paths] chosen, the filter [filter] in use (where there were filters), and what each [Choice] was left at. */
	data class Chose(
		val paths: List<Path>,
		val filter: FileFilter? = null,
		val choices: List<Chosen> = emptyList(),
	) : Picked

	data object Cancelled : Picked

	/** Nothing could be chosen: why, for the log. */
	data class Failed(
		val why: String,
	) : Picked
}

/** [picked], as the portal's answer: the response, and the results. */
fun answerOf(picked: Picked): ChooserAnswer =
	when (picked) {
		is Picked.Chose ->
			ChooserAnswer(
				FileChooser.SUCCESS,
				buildMap {
					put("uris", AnyVariant.of(picked.paths.map(::uriOf)))
					picked.filter?.let { put("current_filter", AnyVariant.of(it)) }
					if (picked.choices.isNotEmpty())
						put("choices", AnyVariant.of(picked.choices))
				},
			)
		Picked.Cancelled -> ChooserAnswer(FileChooser.CANCELLED, emptyMap())
		is Picked.Failed -> ChooserAnswer(FileChooser.OTHER, emptyMap())
	}

/** [path] as a `file://` URI, every byte that needs it escaped, and no `/` after a folder's name. */
fun uriOf(path: Path): String =
	path.toAbsolutePath().normalize().toUri().toString().let {
		if (it.endsWith("/") && it != "file:///")
			it.dropLast(1)
		else
			it
	}

/** The file a `file://` URI names; null for one that is not a file's. */
fun pathOf(uri: String): Path? =
	try {
		URI(uri).takeIf { it.scheme == "file" }?.let(Path::of)
	} catch (bad: URISyntaxException) {
		null
	} catch (bad: IllegalArgumentException) {
		null
	} catch (bad: FileSystemNotFoundException) {
		null
	}

/** What each [Choice] is left at where the dialog does not show them: its initial value. */
fun initialChoices(choices: List<Choice>): List<Chosen> =
	choices.map { choice ->
		Chosen(
			choice.id,
			choice.initial.ifEmpty { choice.options.firstOrNull()?.id ?: "false" },
		)
	}

/**
 * The application a request is made for, as the portal names it: its desktop entry's name without `.desktop`, or
 * what the portal made of the unit it runs in. Never blank: of an application it cannot tell, the portal says
 * nothing, and that is no application ([of]).
 */
@JvmInline
value class AppId private constructor(
	val id: String,
) {
	override fun toString(): String = id

	companion object {
		/** The application [said] names; none, where the portal said nothing. */
		fun of(said: String): AppId? = said.trim().takeIf { it.isNotEmpty() }?.let(::AppId)
	}
}

/** A request of the file chooser, as a dialog is made for it: [app] is who asked, where the portal could tell. */
data class ChooserRequest(
	val app: AppId?,
	val parent: Parent,
	val asked: Asked,
)

/** The file chooser's interface, as a backend serves it. */
object FileChooser {
	/** The bus name prism's backend is known by, which its `.portal` file gives the portal. */
	const val BACKEND = "org.freedesktop.impl.portal.desktop.prism"
	const val PATH = "/org/freedesktop/portal/desktop"
	const val INTERFACE = "org.freedesktop.impl.portal.FileChooser"
	const val REQUEST = "org.freedesktop.impl.portal.Request"

	const val OPEN_FILE = "OpenFile"
	const val SAVE_FILE = "SaveFile"
	const val SAVE_FILES = "SaveFiles"

	val SUCCESS = 0u
	val CANCELLED = 1u
	val OTHER = 2u

	val openFile: Method<ChooserCall, ChooserAnswer> =
		method(null, PATH, INTERFACE, OPEN_FILE)
	val saveFile: Method<ChooserCall, ChooserAnswer> =
		method(null, PATH, INTERFACE, SAVE_FILE)
	val saveFiles: Method<ChooserCall, ChooserAnswer> =
		method(null, PATH, INTERFACE, SAVE_FILES)

	/** `Close`, at a request's handle: what the portal calls to take its dialog away. */
	fun closeAt(handle: ObjectPath): Method<Unit, Unit> =
		Method(
			null,
			handle,
			InterfaceName(REQUEST),
			MemberName("Close"),
			Unit.serializer(),
			Unit.serializer(),
		)
}

/**
 * The file chooser served on [connection]: each request read into a [ChooserRequest] and given to [choose], in
 * [scope], and its answer what [choose] gives. The portal can take a dialog away while it is open (the request's
 * `Close`): [choose] is then cancelled, and is to close its dialog as it is.
 */
fun Connection.serveFileChooser(
	scope: CoroutineScope,
	choose: suspend (ChooserRequest) -> Picked,
): AutoCloseable {
	fun served(method: Method<ChooserCall, ChooserAnswer>) =
		serve(method) { called ->
			val call = called.arguments
			val asked =
				Asked.of(method.member.value, call)
					?: return@serve Answer.Returned(answerOf(Picked.Failed("no such method")))

			val picking =
				scope.async {
					choose(ChooserRequest(AppId.of(call.appId), Parent.of(call.parentWindow), asked))
				}

			val closing =
				serve(FileChooser.closeAt(call.handle)) {
					picking.cancel()
					Answer.Returned(Unit)
				}

			val picked =
				try {
					picking.await()
				} catch (closed: CancellationException) {
					Picked.Cancelled
				} catch (failed: RuntimeException) {
					Picked.Failed(failed.message ?: failed.toString())
				} finally {
					closing.close()
				}

			Answer.Returned(answerOf(picked))
		}

	val served =
		listOf(
			FileChooser.openFile,
			FileChooser.saveFile,
			FileChooser.saveFiles,
		).map(::served)

	return AutoCloseable { served.forEach(AutoCloseable::close) }
}

// ---- owning a name ----------------------------------------------------------------------------------------------

@Serializable
data class NameRequest(
	val name: String,
	val flags: UInt,
)

/** What asking the bus for a name came to. */
enum class Owning {
	/** The name is this connection's. */
	Owned,

	/** Another connection has it, and keeps it. */
	Taken,

	/** The bus refused, or could not be asked. */
	Failed,
}

private val requestName =
	method<NameRequest, UInt>(
		"org.freedesktop.DBus",
		"/org/freedesktop/DBus",
		"org.freedesktop.DBus",
		"RequestName",
	)

/**
 * The well-known [name] asked of the bus for this connection. Another connection that has it keeps it, unless it let
 * it be replaced and [replace] says to; this one lets its own be replaced, so that another prism (or another backend
 * the user would rather have) can take it.
 */
suspend fun Connection.own(name: String, replace: Boolean = false): Owning {
	val allowReplacement = 1u
	val replaceExisting = 2u
	val doNotQueue = 4u
	val flags = allowReplacement or doNotQueue or (if (replace) replaceExisting else 0u)

	return when (val reply = call(requestName, NameRequest(name, flags))) {
		is Reply.Returned ->
			when (reply.value) {
				// The primary owner now, or already.
				1u, 4u -> Owning.Owned
				else -> Owning.Taken
			}
		is Reply.Failed -> Owning.Failed
	}
}
