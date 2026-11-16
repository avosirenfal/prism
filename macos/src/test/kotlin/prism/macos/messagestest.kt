package prism.macos

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import prism.ffi.signaturesBeside

/**
 * The methods declared of Objective-C's classes: a type encoding read into its kinds, every declaration's selector
 * having an argument for each colon (anywhere), and on a Mac every declaration against the runtime's own account of
 * the method, whether or not anything has called it.
 */
class MessagesTest {
	@Test
	fun anEncodingIsReadIntoItsKinds() {
		assertEquals(
			listOf(Code.Object, Code.Object, Code.Selector, Code.Pointer),
			Encoding.codes("@24@0:8r*16"),
		)
		assertEquals(
			listOf(Code.Bool, Code.Object, Code.Selector, Code.Object, Code.Pointer, Code.Pointer),
			Encoding.codes("B40@0:8@16^@24^@32"),
		)
		assertEquals(
			listOf(
				Code.Object,
				Code.Object,
				Code.Selector,
				Code.Struct,
				Code.Int64,
				Code.Int64,
				Code.Bool,
			),
			Encoding.codes("@68@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16Q48Q56B64"),
		)
		assertEquals(
			listOf(
				Code.Pointer,
				Code.Object,
				Code.Selector,
				Code.Pointer,
				Code.Object,
				Code.Object,
			),
			Encoding.codes("^{CGImage=}40@0:8^{CGRect={CGPoint=dd}{CGSize=dd}}16@24@32"),
		)
		assertEquals(
			listOf(Code.Void, Code.Object, Code.Selector, Code.Object),
			Encoding.codes("v24@0:8@?16"),
		)
		assertEquals(listOf(Code.Int32, Code.Object, Code.Selector), Encoding.codes("i16@0:8"))
		assertEquals(
			listOf(Code.Object, Code.Object, Code.Selector, Code.Object),
			Encoding.codes("@24@0:8@\"NSString\"16"),
		)
		assertThrows(IllegalArgumentException::class.java) { Encoding.codes("{CGRect=dd") }
	}

	@Test
	fun everyClassIsListedAndEveryDeclarationIsOfItsSelectorsArguments() {
		val classes = ObjCClass.all()

		assertTrue(NSString in classes && NSWindow in classes && UTType in classes, "$classes")
		// Making each object makes its declarations, each of which refuses a selector that does not fit it.
		assertTrue(classes.sumOf { it.declared.size } > 60)
		assertEquals(
			listOf(Code.Bool, Code.Object, Code.Selector, Code.Object, Code.Pointer, Code.Pointer),
			NSFileManager.trashItemAtURL.declaredCodes,
		)
	}

	@Test
	fun everyCFunctionDeclaredIsASignature() {
		// Read as binding reads them, on any platform: macOS's functions are bound only as they are first called.
		val signatures = signaturesBeside(Frameworks::class.java)

		assertTrue(
			signatures.size > 30,
			"${signatures.map { it.method.declaringClass.simpleName }}",
		)
		assertTrue(
			signatures.any { it.method.declaringClass == RegisterEventHotKey::class.java },
		)
	}

	@Test
	@EnabledOnOs(OS.MAC)
	fun everyDeclarationIsTheMethodTheRuntimeHas() {
		val wrong = ObjCClass.all().flatMap { it.declared }.mapNotNull { it.mismatch() }

		assertEquals(emptyList<String>(), wrong)
	}
}
