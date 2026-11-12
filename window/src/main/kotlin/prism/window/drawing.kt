package prism.window

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.makeGLWithInterface
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GLCapabilities
import org.lwjgl.sdl.SDLVideo.SDL_CreateWindow
import org.lwjgl.sdl.SDLVideo.SDL_DestroyWindow
import org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext
import org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext
import org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent
import org.lwjgl.sdl.SDLVideo.SDL_GL_STENCIL_SIZE
import org.lwjgl.sdl.SDLVideo.SDL_GL_SetAttribute
import org.lwjgl.sdl.SDLVideo.SDL_GL_SetSwapInterval
import org.lwjgl.sdl.SDLVideo.SDL_GL_SwapWindow
import org.lwjgl.sdl.SDLVideo.SDL_GetWindowSurface
import org.lwjgl.sdl.SDLVideo.SDL_UpdateWindowSurface
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN
import org.lwjgl.sdl.SDLVideo.SDL_WINDOW_OPENGL
import org.lwjgl.sdl.SDLVideo.nSDL_GL_GetProcAddress
import org.lwjgl.system.MemoryUtil

/*
 * How a window's pixels are made: by Skia, on the GPU through OpenGL where there is a GPU, and on the CPU where there
 * is none (or only a GPU emulated on the CPU, slower at Skia's work than Skia's own CPU drawing). Either way nothing
 * is copied: on the GPU Skia draws into the window's own framebuffer, which is swapped onto the screen; on the CPU it
 * draws into the very pixels SDL hands the platform (an X11 shared-memory image, a Wayland shared-memory buffer, a
 * GDI or Quartz bitmap), which are then presented as they are.
 */

/** How windows are drawn: on the GPU, on the CPU, or (the default) the GPU where a real one is there. */
enum class Drawing {
	Gpu,
	Cpu,
	Auto,
	;

	companion object {
		/** As `-Dprism.window.drawing=gpu|cpu|auto` or `$PRISM_DRAWING` says; [Auto] where neither does. */
		fun configured(): Drawing =
			(System.getProperty("prism.window.drawing") ?: System.getenv("PRISM_DRAWING"))
				?.let { wanted -> entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) } }
				?: Auto
	}
}

/** An OpenGL implementation that runs on the CPU, by the names drivers give themselves. */
private val SOFTWARE_GL =
	listOf(
		"llvmpipe",
		"softpipe",
		"swrast",
		"SwiftShader",
		"GDI Generic",
		"Microsoft Basic Render",
		"Software Renderer",
	)

/** Whether [renderer], what OpenGL says it is, is OpenGL emulated on the CPU. */
internal fun isSoftware(renderer: String): Boolean =
	SOFTWARE_GL.any { renderer.contains(it, ignoreCase = true) }

/**
 * What OpenGL is here, for drawing as [wanted]: what a window of OpenGL's own, never shown, says it is, asked once
 * before the first window (a window is made for one way of drawing or the other). Not asked where the CPU is asked for,
 * which may be because OpenGL is broken here.
 */
internal fun openGlFor(wanted: Drawing): OpenGl =
	if (wanted == Drawing.Cpu)
		OpenGl.NotAsked
	else
		probe().also { Log.say { "OpenGL: $it" } }

/** What OpenGL says it is, or why it could not be started, as SDL says. */
private fun probe(): OpenGl {
	fun failed(what: String) =
		OpenGl.Unavailable(
			org.lwjgl.sdl.SDLError
				.SDL_GetError()
				?.takeIf { it.isNotBlank() } ?: what,
		)

	val window = SDL_CreateWindow("probe", 1, 1, SDL_WINDOW_OPENGL or SDL_WINDOW_HIDDEN)

	if (window == 0L)
		return failed("no window could be made for OpenGL")

	return try {
		val context = SDL_GL_CreateContext(window)

		if (context == 0L)
			return failed("no OpenGL context could be made")

		try {
			SDL_GL_MakeCurrent(window, context)
			GL.createCapabilities()
			OpenGl.Running(
				GL11.glGetString(GL11.GL_RENDERER).orEmpty(),
				GL11.glGetString(GL11.GL_VENDOR).orEmpty(),
				GL11.glGetString(GL11.GL_VERSION).orEmpty(),
			)
		} finally {
			SDL_GL_MakeCurrent(window, 0L)
			SDL_GL_DestroyContext(context)
		}
	} catch (unusable: IllegalStateException) {
		OpenGl.Unavailable(unusable.message ?: "OpenGL's functions could not be found")
	} finally {
		SDL_DestroyWindow(window)
	}
}

/** A window's pixels, made frame by frame. */
internal sealed interface Pixels : AutoCloseable {
	/** A frame of [width] by [height] pixels drawn by [draw] and put on the screen; false where it could not be. */
	fun frame(width: Int, height: Int, draw: (Canvas) -> Unit): Boolean

	/**
	 * OpenGL, its framebuffer drawn into by Skia on the GPU. Skia is given OpenGL's functions as SDL finds them, so
	 * it draws with whichever of EGL, GLX, WGL or CGL made the context, which skiko's own lookup (GLX's alone, on
	 * Linux) would not.
	 */
	class Gpu private constructor(
		private val window: Long,
		private val gl: Long,
		/** LWJGL's table of the context's functions, which it keeps per thread and is told to use. */
		private val functions: GLCapabilities,
		private val skia: DirectContext,
	) : Pixels {
		private var target: Pair<BackendRenderTarget, Surface>? = null
		private var size = 0 to 0

		override fun frame(width: Int, height: Int, draw: (Canvas) -> Unit): Boolean {
			if (width <= 0 || height <= 0)
				return false

			current()

			val surface = surfaceOf(width, height) ?: return false

			draw(surface.canvas)
			skia.flushAndSubmit(surface)

			return SDL_GL_SwapWindow(window)
		}

		private fun surfaceOf(width: Int, height: Int): Surface? {
			target?.takeIf { size == width to height }?.let { return it.second }

			target?.let { (render, surface) ->
				surface.close()
				render.close()
			}
			target = null

			val framebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)

			val render =
				BackendRenderTarget.makeGL(
					width,
					height,
					0,
					STENCIL_BITS,
					framebuffer,
					GL_RGBA8,
				)

			val surface =
				Surface.makeFromBackendRenderTarget(
					skia,
					render,
					SurfaceOrigin.BOTTOM_LEFT,
					SurfaceColorFormat.RGBA_8888,
					ColorSpace.sRGB,
				) ?: return null.also { render.close() }

			size = width to height
			target = render to surface

			return surface
		}

		private fun current() {
			SDL_GL_MakeCurrent(window, gl)
			GL.setCapabilities(functions)
		}

		override fun close() {
			current()
			target?.let { (render, surface) ->
				surface.close()
				render.close()
			}
			skia.abandon()
			skia.close()
			SDL_GL_MakeCurrent(window, 0L)
			GL.setCapabilities(null)
			SDL_GL_DestroyContext(gl)
		}

		companion object {
			private const val STENCIL_BITS = 8
			private const val GL_RGBA8 = 0x8058

			/** OpenGL's attributes, set before any window of OpenGL's is made: a stencil, which Skia clips with. */
			fun configure() {
				SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, STENCIL_BITS)
			}

			/** OpenGL for [window] (made for OpenGL), and Skia over it; null where either cannot be had. */
			fun of(window: Long, video: Video): Gpu? {
				val gl = SDL_GL_CreateContext(window)

				if (gl == 0L)
					return null

				SDL_GL_MakeCurrent(window, gl)

				val functions = GL.createCapabilities()

				// Swapped on the display's refresh, where the platform lets it be; adaptively where it can.
				if (!SDL_GL_SetSwapInterval(-1))
					SDL_GL_SetSwapInterval(1)

				val skia =
					try {
						GlLookup.egl = video == Video.Wayland
						DirectContext.makeGLWithInterface(
							GLAssembledInterface.createFromNativePointers(0L, GlLookup.stub.address()),
						)
					} catch (unusable: RuntimeException) {
						SDL_GL_DestroyContext(gl)

						return null
					}

				return Gpu(window, gl, functions, skia)
			}
		}
	}

	/** The CPU: Skia drawing straight into the pixels of the surface SDL gives the window, then presented. */
	class Cpu(
		private val window: Long,
	) : Pixels {
		/** Skia's surface over SDL's pixels, and where those pixels were: the same until SDL makes new ones. */
		private var surface: Pair<Long, Surface>? = null

		override fun frame(width: Int, height: Int, draw: (Canvas) -> Unit): Boolean {
			val pixels = SDL_GetWindowSurface(window) ?: return false
			val buffer = pixels.pixels() ?: return false
			val address = MemoryUtil.memAddress(buffer)
			val key =
				address xor (pixels.w().toLong() shl 32) xor pixels.h().toLong() xor
					(pixels.pitch().toLong() shl 48)

			val skia =
				surface?.takeIf { it.first == key }?.second
					?: run {
						surface?.second?.close()
						Surface
							.makeRasterDirect(
								// SDL's window pixels are XRGB8888: B, G, R and a byte unused, in memory order.
								ImageInfo(pixels.w(), pixels.h(), ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
								address,
								pixels.pitch(),
							).also { surface = key to it }
					}

			draw(skia.canvas)

			return SDL_UpdateWindowSurface(window)
		}

		override fun close() {
			surface?.second?.close()
			surface = null
		}
	}
}

/**
 * OpenGL's functions by name, as Skia asks for them: SDL's lookup, through an upcall Skia can call. EGL's own
 * functions are given only where the context is EGL's: GLX's lookup answers every name with something, and Skia,
 * given an `eglQueryString` that is not, would call it.
 */
private object GlLookup {
	@Volatile
	var egl: Boolean = false

	@JvmStatic
	fun find(
		@Suppress("UNUSED_PARAMETER") context: MemorySegment,
		name: MemorySegment,
	): MemorySegment {
		if (!egl && isEgl(name.reinterpret(3)))
			return MemorySegment.NULL

		return MemorySegment.ofAddress(nSDL_GL_GetProcAddress(name.address()))
	}

	private fun isEgl(name: MemorySegment): Boolean =
		name.get(JAVA_BYTE, 0) == 'e'.code.toByte() &&
			name.get(JAVA_BYTE, 1) == 'g'.code.toByte() &&
			name.get(JAVA_BYTE, 2) == 'l'.code.toByte()

	val stub: MemorySegment by lazy {
		Linker.nativeLinker().upcallStub(
			MethodHandles.lookup().findStatic(
				GlLookup::class.java,
				"find",
				MethodType.methodType(
					MemorySegment::class.java,
					MemorySegment::class.java,
					MemorySegment::class.java,
				),
			),
			FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
			Arena.global(),
		)
	}
}
