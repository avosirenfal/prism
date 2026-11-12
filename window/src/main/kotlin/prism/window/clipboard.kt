@file:OptIn(ExperimentalComposeUiApi::class)

package prism.window

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import java.nio.ByteBuffer
import org.lwjgl.PointerBuffer
import org.lwjgl.sdl.SDLClipboard.SDL_ClearClipboardData
import org.lwjgl.sdl.SDLClipboard.SDL_GetClipboardData
import org.lwjgl.sdl.SDLClipboard.SDL_GetClipboardMimeTypes
import org.lwjgl.sdl.SDLClipboard.SDL_GetClipboardText
import org.lwjgl.sdl.SDLClipboard.SDL_SetClipboardData
import org.lwjgl.sdl.SDLStdinc.nSDL_free
import org.lwjgl.sdl.SDL_ClipboardCleanupCallback
import org.lwjgl.sdl.SDL_ClipboardDataCallback
import org.lwjgl.system.MemoryUtil

/*
 * The desktop's clipboard, by MIME type, as every platform's is underneath: what is copied is offered in each of the
 * types given, and what is there is read in whichever type the reader wants. SDL's calls are made on the windows'
 * thread, wherever they are asked from.
 */

/** What is put on the clipboard: each MIME type it is offered as, and its bytes in that type. */
data class ClipOffer(
	val types: Map<String, ByteArray>,
) {
	companion object {
		/** Text, as every program reads it. */
		fun text(text: String): ClipOffer =
			text.encodeToByteArray().let { bytes ->
				ClipOffer(mapOf(TEXT_UTF8 to bytes, TEXT to bytes))
			}
	}
}

const val TEXT_UTF8 = "text/plain;charset=utf-8"
const val TEXT = "text/plain"

/** The desktop's clipboard. */
object SystemClipboard {
	/** What is offered now, kept until the clipboard is taken by another program or replaced: SDL reads it as asked. */
	private var held: Held? = null

	private class Held(
		val offer: ClipOffer,
		val data: SDL_ClipboardDataCallback,
		val cleanup: SDL_ClipboardCleanupCallback,
		/** Each type's bytes in native memory, made when asked for, and freed as the offer is given up. */
		val native: MutableMap<String, ByteBuffer> = HashMap(),
	) {
		fun free() {
			native.values.forEach(MemoryUtil::memFree)
			native.clear()
		}
	}

	/** [offer] put on the clipboard, in every type it has; false where there is no clipboard to put it on. */
	fun put(offer: ClipOffer): Boolean =
		Ui.call {
			if (offer.types.isEmpty())
				return@call SDL_ClearClipboardData()

			lateinit var holding: Held

			val data =
				SDL_ClipboardDataCallback.create { _, type, size ->
					val name = MemoryUtil.memUTF8(type)
					val bytes = holding.offer.types[name]

					if (bytes == null) {
						MemoryUtil.memPutAddress(size, 0L)
						0L
					} else {
						val buffer =
							holding.native.getOrPut(name) {
								MemoryUtil.memAlloc(bytes.size.coerceAtLeast(1)).put(bytes).flip()
							}

						MemoryUtil.memPutAddress(size, bytes.size.toLong())
						MemoryUtil.memAddress(buffer)
					}
				}

			val cleanup = SDL_ClipboardCleanupCallback.create { _ -> }

			holding = Held(offer, data, cleanup)

			val types = PointerBuffer.allocateDirect(offer.types.size)
			val names = offer.types.keys.map { MemoryUtil.memUTF8(it) }

			names.forEach(types::put)
			types.flip()

			val done = SDL_SetClipboardData(data, cleanup, 0L, types)

			names.forEach(MemoryUtil::memFree)
			// What was held before is given up now that SDL holds this instead.
			held?.let {
				it.free()
				it.data.free()
				it.cleanup.free()
			}
			held = holding.takeIf { done }

			done
		}

	/** The MIME types what is on the clipboard is offered as. */
	fun types(): List<String> =
		Ui.call {
			val types = SDL_GetClipboardMimeTypes() ?: return@call emptyList()

			try {
				(0 until types.remaining()).map { types.getStringUTF8(it) }
			} finally {
				nSDL_free(types.address())
			}
		}

	/** What is on the clipboard as [type]; null where it is not offered so. */
	fun read(type: String): ByteArray? =
		Ui.call {
			val buffer = SDL_GetClipboardData(type) ?: return@call null
			// Where SDL's memory starts, taken before reading moves the buffer's position along it.
			val start = MemoryUtil.memAddress(buffer)

			try {
				ByteArray(buffer.remaining()).also { buffer.get(it) }
			} finally {
				nSDL_free(start)
			}
		}

	/** What is on the clipboard as text, if it is text. */
	fun text(): String? = Ui.call { SDL_GetClipboardText()?.takeIf { it.isNotEmpty() } }
}

/**
 * Compose's clipboard, over the desktop's: what a text field copies and pastes. Compose for the desktop writes and
 * reads its entries as AWT's transferables, which are only values here, as text.
 */
internal object ComposeClipboard : Clipboard {
	override suspend fun getClipEntry(): ClipEntry? =
		SystemClipboard.text()?.let {
			ClipEntry(StringSelection(it))
		}

	override suspend fun setClipEntry(clipEntry: ClipEntry?) {
		val text = (clipEntry?.nativeClipEntry as? Transferable)?.textOf()

		SystemClipboard.put(text?.let(ClipOffer::text) ?: ClipOffer(emptyMap()))
	}

	private fun Transferable.textOf(): String? =
		try {
			getTransferData(DataFlavor.stringFlavor) as? String
		} catch (unsupported: UnsupportedFlavorException) {
			null
		} catch (failed: IOException) {
			null
		}
}
