package prism.window

/*
 * How prism's windows are drawn, and why: what was asked for, and what OpenGL turned out to be when a window of its
 * own asked it, before the first window was made. A window is drawn on the GPU where OpenGL runs on one, and on the CPU
 * where it runs on none (it emulates a GPU on the CPU, more slowly than Skia's own drawing), cannot be started at all,
 * or is not wanted.
 */

/** What OpenGL is here, as a window of its own found it. */
sealed interface OpenGl {
	/** Not asked, since the CPU was asked for: OpenGL may be what was broken. */
	data object NotAsked : OpenGl

	/** It could not be started: [why], as SDL says it. */
	data class Unavailable(
		val why: String,
	) : OpenGl

	/** It runs: [renderer] (`NVIDIA GeForce RTX 4070/PCIe/SSE2`, `llvmpipe (LLVM 20.1, 256 bits)`), by [vendor]. */
	data class Running(
		val renderer: String,
		val vendor: String,
		val version: String,
	) : OpenGl {
		/** Whether it runs on the CPU, emulating a GPU: llvmpipe, SwiftShader and the like. */
		val software: Boolean get() = isSoftware(renderer)
	}
}

/** How OpenGL is reached on a display: EGL (Wayland's), GLX (X11's), WGL (Windows'), CGL (macOS's). */
enum class GlApi {
	EGL,
	GLX,
	WGL,
	CGL,
	Other,
	;

	companion object {
		fun of(video: Video): GlApi =
			when (video) {
				Video.Wayland -> EGL
				Video.X11 -> GLX
				Video.Windows -> WGL
				Video.Cocoa -> CGL
				Video.Other -> Other
			}
	}
}

/** How prism's windows are drawn: on [video], as [asked] for, with OpenGL here as [openGl] found it. */
data class Rendering(
	val video: Video,
	val asked: Drawing,
	val openGl: OpenGl,
) {
	/**
	 * Whether the GPU draws them: where it is asked for and OpenGL runs at all, or, left to prism, where OpenGL runs on
	 * a GPU and not on the CPU.
	 */
	val onGpu: Boolean
		get() =
			when (asked) {
				Drawing.Cpu -> false
				Drawing.Gpu -> openGl is OpenGl.Running
				Drawing.Auto -> (openGl as? OpenGl.Running)?.software == false
			}

	/** How OpenGL is reached on this display. */
	val api: GlApi get() = GlApi.of(video)
}

/**
 * How a window turned out to be drawn, once it was made: OpenGL found to run on the GPU is tried for each window, and a
 * window that cannot be drawn with it is drawn on the CPU instead, and says why.
 */
sealed interface Drawn {
	/** By OpenGL. */
	data object ByOpenGl : Drawn

	/** On the CPU, as [Rendering] chose. */
	data object OnCpu : Drawn

	/** On the CPU, since OpenGL, which runs here, could not draw this window, for [why]. */
	data class FellBack(
		val why: String,
	) : Drawn
}
