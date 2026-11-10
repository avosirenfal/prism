package prism.browser

import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/*
 * Files on the clipboard, as the desktop's file managers put them there, so a file copied here pastes in any of them and
 * one copied there pastes here: a list of files (which the JVM gives other applications as `text/uri-list`), GNOME's
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

/** The desktop's clipboard, through AWT. */
object SystemFileClipboard : FileClipboard {
	private val gnome = DataFlavor("x-special/gnome-copied-files;class=java.io.InputStream")

	private fun system(): Clipboard? =
		try {
			Toolkit.getDefaultToolkit().systemClipboard
		} catch (headless: HeadlessException) {
			null
		}

	override fun put(clip: FileClip) {
		val clipboard = system() ?: return

		try {
			clipboard.setContents(Files(clip), Files(clip))
		} catch (busy: IllegalStateException) {
			Unit
		}
	}

	override fun read(): FileClip? {
		val contents =
			system()?.let { clipboard ->
				try {
					clipboard.getContents(null)
				} catch (busy: IllegalStateException) {
					null
				}
			} ?: return null

		return try {
			when {
				contents.isDataFlavorSupported(
					gnome,
				) -> gnomeOf(String((contents.getTransferData(gnome) as InputStream).readAllBytes()))
				contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
					(contents.getTransferData(DataFlavor.javaFileListFlavor) as List<*>)
						.filterIsInstance<File>()
						.map { it.toPath() }
						.takeIf { it.isNotEmpty() }
						?.let { FileClip(it, cut = false) }
				else -> null
			}
		} catch (failed: IOException) {
			null
		} catch (wrong: UnsupportedFlavorException) {
			null
		}
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

	/** [clip], in every form the clipboard offers it. */
	private class Files(
		private val clip: FileClip,
	) : Transferable,
		ClipboardOwner {
		override fun getTransferDataFlavors(): Array<DataFlavor> =
			arrayOf(gnome, DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor)

		override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
			flavor in transferDataFlavors

		override fun getTransferData(flavor: DataFlavor): Any =
			when (flavor) {
				gnome -> gnomeText(clip).byteInputStream()
				DataFlavor.javaFileListFlavor -> clip.paths.map { it.toFile() }
				DataFlavor.stringFlavor -> clip.paths.joinToString("\n")
				else -> throw UnsupportedFlavorException(flavor)
			}

		override fun lostOwnership(clipboard: Clipboard, contents: Transferable) = Unit
	}
}
