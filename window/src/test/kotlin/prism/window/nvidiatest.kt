package prism.window

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.lwjgl.sdl.SDLStdinc.SDL_getenv_unsafe
import org.lwjgl.sdl.SDLStdinc.SDL_setenv_unsafe
import org.lwjgl.sdl.SDLStdinc.SDL_unsetenv_unsafe
import org.lwjgl.system.Platform

class NvidiaTest {
	@Test
	fun nvidiasThreadingIsTurnedOffInTheProcesssOwnEnvironment_UnlessTheUserSetIt() {
		assumeTrue(Platform.get() == Platform.LINUX)

		val was = SDL_getenv_unsafe(NVIDIA_THREADED)

		try {
			// Unset: turned off, where the driver reads the environment (the C library's, not the JVM's copy).
			SDL_unsetenv_unsafe(NVIDIA_THREADED)
			unthreadNvidia()
			assertEquals("0", SDL_getenv_unsafe(NVIDIA_THREADED))

			// Set by the user: kept.
			SDL_setenv_unsafe(NVIDIA_THREADED, "1", 1)
			unthreadNvidia()
			assertEquals("1", SDL_getenv_unsafe(NVIDIA_THREADED))
		} finally {
			if (was == null)
				SDL_unsetenv_unsafe(NVIDIA_THREADED)
			else
				SDL_setenv_unsafe(NVIDIA_THREADED, was, 1)
		}
	}
}
