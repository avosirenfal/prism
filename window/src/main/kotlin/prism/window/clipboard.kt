@file:OptIn(ExperimentalComposeUiApi::class)

package prism.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lwjgl.PointerBuffer
import org.lwjgl.sdl.SDLClipboard.SDL_ClearClipboardData
import org.lwjgl.sdl.SDLClipboard.SDL_GetClipboardData
import org.lwjgl.sdl.SDLClipboard.SDL_GetClipboardMimeTypes
import org.lwjgl.sdl.SDLClipboard.SDL_GetClipboardText
import org.lwjgl.sdl.SDLClipboard.SDL_HasClipboardData
import org.lwjgl.sdl.SDLClipboard.SDL_SetClipboardData
import org.lwjgl.sdl.SDLStdinc.nSDL_free
import org.lwjgl.sdl.SDLVideo.SDL_GetCurrentVideoDriver
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
	private val EMPTY = ByteArray(0)

	/** What is offered now, kept until the clipboard is taken by another program or replaced: SDL reads it as asked. */
	private var held: Held? = null

	private val ours = MutableStateFlow(false)

	/**
	 * Whether what is on the clipboard is this process's to give: put there by [put], and not since replaced by
	 * another program's or cleared. A clipboard's contents are handed over by the program that put them when they
	 * are pasted, so they are gone with it: a process that is about to end with this true takes them with it.
	 */
	val offering: StateFlow<Boolean> = ours.asStateFlow()

	private class Held(
		val offer: ClipOffer,
		/** A type this offer alone has, with nothing in it: what tells it on the clipboard from any other. */
		val mark: String,
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
					val bytes = holding.offer.types[name] ?: EMPTY.takeIf { name == holding.mark }

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

			// Called as what was offered is let go of: replaced, by this process or another, or cleared.
			val cleanup =
				SDL_ClipboardCleanupCallback.create { _ ->
					Log.say { "clipboard: what was put is let go" }
					ours.value = false
				}

			holding = Held(offer, "application/x-prism-offer-${UUID.randomUUID()}", data, cleanup)

			val types = PointerBuffer.allocateDirect(offer.types.size + 1)
			val names = (offer.types.keys + holding.mark).map { MemoryUtil.memUTF8(it) }

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
			ours.value = done
			Log.say { "clipboard: put ${offer.types.keys}, ${if (done) "taken" else "refused"}" }

			done
		}

	/**
	 * What this process put on the clipboard, while it is still what is there: read from here, and the desktop not
	 * asked for it.
	 *
	 * Whether it is still there is told by a type of its own that every offer is given ([Held.mark]), asked of the
	 * desktop like any other type.
	 *
	 * On Wayland that is not all, because of what SDL does where a clipboard manager runs (one that keeps what was
	 * copied, by taking it and offering it again as its own, type for type). SDL puts a mark of its own in every
	 * offer, so as to know its own selection when its compositor shows it back; the manager's offer has that mark
	 * too, so SDL takes it for its own selection come round again and throws the offer away, and, told meanwhile
	 * that its own selection was replaced, lets go of that as well. It is left with nothing: no types, and nothing
	 * to give for any of them, while the clipboard holds exactly what was copied and every other program pastes
	 * it. So there, SDL knowing of nothing at all on the clipboard, after it let go of what this process put, is
	 * read as this: what the manager holds is what is held here. An offer of anyone else's, SDL does tell of.
	 */
	private fun own(): ClipOffer? =
		held
			?.takeIf {
				SDL_HasClipboardData(it.mark) || (takenByAManager() && desktopTypes().isEmpty())
			}?.offer

	/** Whether what this process put was let go of where a clipboard manager would have taken it: on Wayland. */
	private fun takenByAManager(): Boolean =
		!ours.value && Video.of(SDL_GetCurrentVideoDriver()) == Video.Wayland

	/** The types SDL says are on the clipboard. */
	private fun desktopTypes(): List<String> {
		val types = SDL_GetClipboardMimeTypes() ?: return emptyList()

		return try {
			(0 until types.remaining()).map { types.getStringUTF8(it) }
		} finally {
			nSDL_free(types.address())
		}
	}

	/**
	 * Whether what is on the clipboard is offered as [type].
	 *
	 * Asked a type at a time: all a paste wants to know is whether the kind it can take is there.
	 */
	fun has(type: String): Boolean =
		Ui.call {
			val own = own()

			(own?.let { type in it.types } ?: SDL_HasClipboardData(type)).also {
				Log.say {
					"clipboard: ${if (own != null)
						"this prism's own"
					else
						"the desktop's"} " +
						"${if (it) "has" else "has no"} $type"
				}
			}
		}

	/** What is on the clipboard as [type]; null where it is not offered so. */
	fun read(type: String): ByteArray? =
		Ui.call {
			own()?.let { return@call it.types[type] }

			val buffer =
				SDL_GetClipboardData(type)
					?: return@call null.also {
						Log.say { "clipboard: the desktop gave nothing as $type" }
					}
			// Where SDL's memory starts, taken before reading moves the buffer's position along it.
			val start = MemoryUtil.memAddress(buffer)

			try {
				ByteArray(buffer.remaining()).also { buffer.get(it) }
			} finally {
				nSDL_free(start)
			}
		}

	/** What is on the clipboard as text, if it is text. */
	fun text(): String? =
		Ui.call {
			own()?.let {
				return@call it.types[TEXT_UTF8]?.decodeToString()?.takeIf(String::isNotEmpty)
			}

			SDL_GetClipboardText()?.takeIf { it.isNotEmpty() }
		}
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

	/** The desktop's clipboard as AWT's, which a text field's own menu asks whether there is text to paste. */
	@Suppress("OVERRIDE_DEPRECATION") // Compose's interface still asks for it.
	override val nativeClipboard: Any get() = AwtClipboard
}

/**
 * The desktop's clipboard as AWT names one: only a value of AWT's here, as its transferables are, over SDL's
 * clipboard, holding text.
 */
private object AwtClipboard : java.awt.datatransfer.Clipboard("prism") {
	override fun getContents(requestor: Any?): Transferable? =
		SystemClipboard.text()?.let(::StringSelection)

	override fun setContents(
		contents: Transferable?,
		owner: java.awt.datatransfer.ClipboardOwner?,
	) {
		SystemClipboard.put(contents?.textOf()?.let(ClipOffer::text) ?: ClipOffer(emptyMap()))
	}

	override fun getAvailableDataFlavors(): Array<DataFlavor> =
		if (SystemClipboard.text() != null)
			arrayOf(DataFlavor.stringFlavor)
		else
			emptyArray()

	override fun isDataFlavorAvailable(flavor: DataFlavor): Boolean =
		flavor in availableDataFlavors

	override fun getData(flavor: DataFlavor): Any =
		SystemClipboard.text()?.takeIf { flavor == DataFlavor.stringFlavor }
			?: throw UnsupportedFlavorException(flavor)
}

/** What [this] holds as text; null where it holds none. */
private fun Transferable.textOf(): String? =
	try {
		getTransferData(DataFlavor.stringFlavor) as? String
	} catch (unsupported: UnsupportedFlavorException) {
		null
	} catch (failed: IOException) {
		null
	}

/**
 * [content] given the window's own clipboard again: what a popup's content needs, since Compose gives each popup the
 * platform clipboard of its own making (AWT's, which has none here), whatever its window provides.
 */
@Composable
fun WindowLocals(content: @Composable () -> Unit) =
	CompositionLocalProvider(LocalClipboard provides ComposeClipboard, content = content)
