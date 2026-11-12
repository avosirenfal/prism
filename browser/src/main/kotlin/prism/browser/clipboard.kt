package prism.browser

import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import prism.window.ClipOffer
import prism.window.SystemClipboard

/*
 * Files on the clipboard, as the desktop's file managers put them there, so a file copied here pastes in any of them and
 * one copied there pastes here: a URI list (`text/uri-list`, which every desktop reads), GNOME's
 * `x-special/gnome-copied-files` (which says whether they were cut, and which most Linux file managers read), and the
 * paths as text, for anything else.
 */

/** Where files are copied and cut to, and pasted from. */
interface FileClipboard {
	fun put(clip: FileClip)

	/** What is there, as files; null where it is not files. */
	fun read(): FileClip?
}

/** A clipboard of this process's alone: the tests', and a picker's with no display. */
class MemoryFileClipboard : FileClipboard {
	private val held = AtomicReference<FileClip?>(null)

	override fun put(clip: FileClip) = held.set(clip)

	override fun read(): FileClip? = held.get()
}

/** The desktop's clipboard, through the window's (prism.window), by MIME type. */
object SystemFileClipboard : FileClipboard {
	private const val GNOME = "x-special/gnome-copied-files"
	private const val URI_LIST = "text/uri-list"

	override fun put(clip: FileClip) {
		val paths = clip.paths.joinToString("\n").encodeToByteArray()

		SystemClipboard.put(
			ClipOffer(
				ClipOffer.text(clip.paths.joinToString("\n")).types +
					mapOf(
						GNOME to gnomeText(clip).encodeToByteArray(),
						URI_LIST to uriList(clip.paths).encodeToByteArray(),
						"text/plain" to paths,
					),
			),
		)
	}

	override fun read(): FileClip? {
		val types = SystemClipboard.types()

		return when {
			GNOME in types -> SystemClipboard.read(GNOME)?.decodeToString()?.let(::gnomeOf)
			URI_LIST in types ->
				SystemClipboard
					.read(URI_LIST)
					?.decodeToString()
					?.let(::pathsOfUris)
					?.takeIf { it.isNotEmpty() }
					?.let { FileClip(it, cut = false) }
			else -> null
		}
	}

	/** [paths] as a URI list, as every desktop names files: one a line, each line ended by CR and LF. */
	fun uriList(paths: List<Path>): String =
		paths.joinToString("") {
			it.toUri().toString() +
				"\r\n"
		}

	/** GNOME's form: `copy` or `cut`, then a URI a line. */
	fun gnomeOf(text: String): FileClip? {
		val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

		val paths =
			lines.drop(1).mapNotNull {
				try {
					Path.of(URI(it))
				} catch (wrong: IllegalArgumentException) {
					null
				} catch (wrong: java.net.URISyntaxException) {
					null
				}
			}

		return paths
			.takeIf {
				it.isNotEmpty()
			}?.let { FileClip(it, cut = lines.first() == "cut") }
	}

	/** GNOME's form of [clip]. */
	fun gnomeText(clip: FileClip): String =
		(
			listOf(if (clip.cut) "cut" else "copy") +
				clip.paths.map {
					it.toUri().toString()
				}
		).joinToString("\n")
}
