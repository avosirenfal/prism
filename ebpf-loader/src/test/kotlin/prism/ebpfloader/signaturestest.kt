package prism.ebpfloader

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import prism.ffi.signaturesBeside

/** libbpf's functions as they are declared: each a signature, read as binding reads it, with or without libbpf. */
class SignaturesTest {
	@Test
	fun everyFunctionOfLibbpfsDeclaredIsASignature() {
		val signatures = signaturesBeside(LibBpf::class.java)

		assertTrue(
			signatures.size >= 17,
			"${signatures.map { it.method.declaringClass.simpleName }}",
		)
	}
}
