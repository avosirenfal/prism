package prism.window

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.Platform
import org.lwjgl.system.windows.WinBase
import org.lwjgl.system.linux.DynamicLinkLoader as LinuxLoader
import org.lwjgl.system.macosx.DynamicLinkLoader as MacLoader

/**
 * Libraries loaded as the system's loader loads them, then let go of: by `dlopen` on Linux and macOS, which says why one
 * cannot be (`libEGL_nvidia.so.0: cannot open shared object file: No such file or directory`); by `LoadLibrary` on
 * Windows, which says by a number. On Linux, where one was loaded from is read from the process's own map of its files.
 */
object SystemLibraries : Libraries {
	override fun load(name: String): Loading =
		when (Platform.get()) {
			Platform.WINDOWS ->
				MemoryStack.stackPush().use { stack ->
					val error = stack.mallocInt(1)
					val handle = WinBase.LoadLibrary(error, name)

					if (handle == 0L)
						Loading.Fails(windowsError(error[0]))
					else
						Loading.Loads(null).also { WinBase.FreeLibrary(null, handle) }
				}
			Platform.MACOSX ->
				opened(
					{ MacLoader.dlopen(name, MacLoader.RTLD_LAZY or MacLoader.RTLD_LOCAL) },
					MacLoader::dlerror,
					{ null },
				) { MacLoader.dlclose(it) }
			else ->
				opened(
					{ LinuxLoader.dlopen(name, LinuxLoader.RTLD_LAZY or LinuxLoader.RTLD_LOCAL) },
					LinuxLoader::dlerror,
					{ mapped(name) },
				) { LinuxLoader.dlclose(it) }
		}

	/**
	 * A library [open] (`dlopen`) loads, found at [where] while it is loaded, then let go of by [close]; or, where it
	 * cannot be, why not, as [error] (`dlerror`) says. The loader keeps one error, which each of its calls clears: so
	 * [error] is asked once before, which readies its own call (the first, looking itself up, would clear it) and clears
	 * what any other left.
	 */
	private fun opened(
		open: () -> Long,
		error: () -> String?,
		where: () -> Path?,
		close: (Long) -> Unit,
	): Loading {
		error()

		val handle = open()

		return if (handle == 0L)
			Loading.Fails(error() ?: "it cannot be loaded")
		else
			Loading.Loads(where()).also { close(handle) }
	}

	/**
	 * Where the library [name] (`libEGL_nvidia.so.0`) was loaded from, as the process's map of its files has it: the file
	 * itself, its links followed (`libEGL_nvidia.so.550.78`).
	 */
	private fun mapped(name: String): Path? {
		val stem = name.substringBefore(".so") + ".so"

		return try {
			Files.readAllLines(Path.of("/proc/self/maps")).firstNotNullOfOrNull { line ->
				line
					.substringAfter('/', "")
					.takeIf { it.isNotEmpty() }
					?.let { Path.of("/$it") }
					?.takeIf { path ->
						path.fileName.toString().let {
							it == stem ||
								it.startsWith("$stem.")
						}
					}
			}
		} catch (unreadable: IOException) {
			null
		}
	}

	/** What Windows' error [code] from `LoadLibrary` means, for the codes it gives most. */
	private fun windowsError(code: Int): String =
		when (code) {
			126 -> "it, or a library it needs, cannot be found (error 126)"
			193 -> "it is not a library for this kind of processor (error 193)"
			else -> "Windows could not load it (error $code)"
		}
}
