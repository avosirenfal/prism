package prism.window

import java.net.URI
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import prism.macos.FileDrags

/**
 * Drags by a Mac's own protocol: files carried by an AppKit dragging session (macos/drags.kt), out to other
 * applications and to this process's own windows alike, which SDL tells of them as of any drop. What a drag carries
 * that is not files (text alone) is not AppKit's here, and goes from window to window of this process's by where the
 * pointer is. The pointer's cursor is AppKit's to set during a session, by what the application under it says.
 */
internal object CocoaDrags : NativeDrags {
	override fun start(
		window: NativeWindow,
		offer: ClipOffer,
		picture: DragPicture?,
		onEnd: (DragEnd) -> Unit,
	): Boolean {
		val cocoa = window.handle as? NativeHandle.Cocoa ?: return false
		val files = filesOf(offer)
		val (x, fromBottom) = window.pointerInPoints()

		return FileDrags.begin(cocoa.window, files, x, fromBottom) { taken ->
			// Told on the windows' thread, in the middle of AppKit's own work: done once that is.
			Ui.dispatch(EmptyCoroutineContext) {
				onEnd(if (taken) DragEnd.Taken else DragEnd.Cancelled)
			}
		}
	}

	override fun overOwn(effect: DropEffect?) = Unit

	override fun offOwn() = Unit

	override fun close() = Unit

	/** The files [offer] offers, from its list of their addresses; none where it offers none. */
	internal fun filesOf(offer: ClipOffer): List<Path> =
		offer.types["text/uri-list"]
			?.decodeToString()
			?.lines()
			?.filter { it.isNotBlank() && !it.startsWith("#") }
			?.mapNotNull { runCatching { Path.of(URI(it.trim())) }.getOrNull() }
			.orEmpty()
}
