package prism.window

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import prism.ffi.signaturesBeside

/**
 * The window module's native functions as they are declared: each a signature, read as binding reads it, anywhere;
 * and, where libwayland-client is installed, every one of Wayland's bound to the library itself, with no compositor.
 */
class BindingsTest {
	@Test
	fun everyFunctionDeclaredIsASignature() {
		val signatures = signaturesBeside(Wl::class.java)

		assertTrue(
			signatures.size > 30,
			"${signatures.map { it.method.declaringClass.simpleName }}",
		)
	}

	@Test
	fun waylandsFunctionsAreInTheLibrary() {
		assumeTrue(
			listOf(
				"/usr/lib/x86_64-linux-gnu",
				"/usr/lib/aarch64-linux-gnu",
				"/usr/lib64",
				"/usr/lib",
			).any { Files.exists(Path.of(it, "libwayland-client.so.0")) } ||
				System.getenv("LD_LIBRARY_PATH").orEmpty().split(':').any {
					it.isNotEmpty() && Files.exists(Path.of(it, "libwayland-client.so.0"))
				},
			"libwayland-client is not installed",
		)
		// Making the object binds every function of it: one the library lacks, or declared as C has no type for, fails.
		assertTrue(Wl.iface("wl_registry_interface").address() != 0L)
	}
}
