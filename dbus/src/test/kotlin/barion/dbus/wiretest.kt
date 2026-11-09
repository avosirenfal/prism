package barion.dbus

import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The wire format against the bytes the specification says, worked out by hand; every type there and back in both
 * byte orders; and the reader's refusals. The live tests (connectiontest.kt) then have a real dbus-daemon, which
 * checks every message it is sent, read what this writes.
 */
class WireTest {
	private fun hex(
		vararg values: Value,
		order: ByteOrder = ByteOrder.LITTLE_ENDIAN,
	): String =
		WireWriter(order)
			.apply {
				values.forEach(::write)
			}.bytes()
			.joinToString(" ") { "%02x".format(it) }

	private fun roundTrip(value: Value, order: ByteOrder) {
		val bytes = WireWriter(order).apply { write(value) }.bytes()
		val reader = WireReader(bytes, order)

		assertEquals(value, reader.read(value.type), "$value in $order")
		assertTrue(reader.atEnd, "$value left bytes unread")
	}

	private fun malformed(bytes: List<Int>, type: Type): String =
		assertThrows<Malformed> {
			WireReader(
				bytes
					.map {
						it.toByte()
					}.toByteArray(),
				ByteOrder.LITTLE_ENDIAN,
			).read(type)
		}.message!!

	@Test
	fun theBytesAreTheSpecifications() {
		assertEquals("01 00 00 00", hex(Value.UInt32(1u)))
		assertEquals("02 00 00 00 68 69 00", hex(Value.String("hi")))
		// What `busctl call ... ReadOne` printed as `v u 1`: the signature "u", padding to 4, the number.
		assertEquals("01 75 00 00 01 00 00 00", hex(Value.Variant(Value.UInt32(1u))))
		// A byte, then a 64-bit number on the next multiple of eight.
		assertEquals(
			"05 00 00 00 00 00 00 00 07 00 00 00 00 00 00 00",
			hex(Value.Byte(5u), Value.Int64(7)),
		)
		// An array's length does not count the padding to its first element.
		assertEquals(
			"10 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00 02 00 00 00 00 00 00 00",
			hex(Value.Array(Type.Basic.Int64, listOf(Value.Int64(1), Value.Int64(2)))),
		)
		// An empty array still pads to where its elements would start.
		assertEquals("00 00 00 00 00 00 00 00", hex(Value.Array(Type.Basic.Int64, emptyList())))
		// a{sv} {"a": <uint32 1>}: each entry on 8; the variant's value on 4 after its signature.
		assertEquals(
			"10 00 00 00 00 00 00 00 01 00 00 00 61 00 01 75 00 00 00 00 01 00 00 00",
			hex(
				Value.Dict(
					Type.Basic.String,
					Type.Variant,
					listOf(Value.String("a") to Value.Variant(Value.UInt32(1u))),
				),
			),
		)
		assertEquals("00 00 00 01", hex(Value.Boolean(true), order = ByteOrder.BIG_ENDIAN))
	}

	@Test
	fun everyTypeComesBackAsItWentInEitherWay() {
		val values =
			listOf(
				Value.Byte(255u),
				Value.Boolean(true),
				Value.Int16(-2),
				Value.UInt16(65535u),
				Value.Int32(Int.MIN_VALUE),
				Value.UInt32(UInt.MAX_VALUE),
				Value.Int64(Long.MIN_VALUE),
				Value.UInt64(ULong.MAX_VALUE),
				Value.Double(-0.5),
				Value.String("Téléchargements ✓"),
				Value.ObjectPath(ObjectPath("/org/freedesktop/portal/desktop")),
				Value.Signature(Signature.of(Type.Dict(Type.Basic.String, Type.Variant))),
				Value.Array(Type.Basic.String, listOf(Value.String("a"), Value.String("bc"))),
				Value.Struct(
					listOf(Value.Byte(1u), Value.Struct(listOf(Value.Int64(2), Value.String("x")))),
				),
				Value.Variant(
					Value.Variant(Value.Array(Type.Basic.Byte, listOf(Value.Byte(1u), Value.Byte(2u)))),
				),
				Value.Dict(
					Type.Basic.String,
					Type.Variant,
					listOf(
						Value.String("color-scheme") to Value.Variant(Value.UInt32(1u)),
						Value.String("accent") to
							Value.Variant(
								Value.Struct(listOf(Value.Double(0.1), Value.Double(0.2), Value.Double(0.3))),
							),
					),
				),
			)

		for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN))
			for (value in values)
				roundTrip(Value.Struct(listOf(Value.Byte(9u), value)), order)
	}

	@Test
	fun theReaderRefusesWhatIsNotAValue() {
		assertTrue(
			"not followed by its NUL" in
				malformed(listOf(2, 0, 0, 0, 0x68, 0x69, 0x21), Type.Basic.String),
		)
		assertTrue(
			"holds a NUL" in malformed(listOf(2, 0, 0, 0, 0x68, 0, 0), Type.Basic.String),
		)
		assertTrue("not UTF-8" in malformed(listOf(1, 0, 0, 0, 0xFF, 0), Type.Basic.String))
		assertTrue("a boolean of 2" in malformed(listOf(2, 0, 0, 0), Type.Basic.Boolean))
		assertTrue(
			"runs past the end" in
				malformed(listOf(0x40, 0, 0, 0, 1, 0, 0, 0), Type.Array(Type.Basic.UInt32)),
		)
		assertTrue(
			"not zero" in
				malformed(
					listOf(5, 1, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0),
					Type.Struct(listOf(Type.Basic.Byte, Type.Basic.Int64)),
				),
		)
		assertTrue("one type" in malformed(listOf(2, 0x75, 0x75, 0, 1, 0, 0, 0), Type.Variant))
		assertTrue("bytes short" in malformed(listOf(1, 0), Type.Basic.UInt32))
		assertTrue(
			"not an object path" in
				malformed(listOf(2, 0, 0, 0, 0x2F, 0x2F, 0), Type.Basic.ObjectPath),
		)
	}

	@Test
	fun signaturesAreParsedAndRefused() {
		val parsed =
			assertInstanceOf(Signature.Parsed.Valid::class.java, Signature.parse("sa{sv}(iad)v"))

		assertEquals(
			listOf(
				Type.Basic.String,
				Type.Dict(Type.Basic.String, Type.Variant),
				Type.Struct(listOf(Type.Basic.Int32, Type.Array(Type.Basic.Double))),
				Type.Variant,
			),
			parsed.signature.types,
		)
		assertEquals("sa{sv}(iad)v", parsed.signature.code)

		for (bad in listOf("a", "{sv}", "()", "a{vs}", "(i", "a{s}", "z", "a{sva}"))
			assertInstanceOf(Signature.Parsed.Invalid::class.java, Signature.parse(bad), bad)

		assertInstanceOf(
			Signature.Parsed.Invalid::class.java,
			Signature.parse("a".repeat(33) + "i"),
			"33 arrays deep",
		)
		assertInstanceOf(
			Signature.Parsed.Valid::class.java,
			Signature.parse("a".repeat(32) + "i"),
		)
	}

	@Test
	fun namesAreCheckedAsTheyAreMade() {
		assertEquals(
			"org.freedesktop.portal.Desktop",
			BusName("org.freedesktop.portal.Desktop").value,
		)
		assertTrue(BusName(":1.42").isUnique)
		assertNull(BusName.parse("org"))
		assertNull(BusName.parse("org..portal"))
		assertNull(ObjectPath.parse("/a/"))
		assertNull(ObjectPath.parse("a/b"))
		assertNull(InterfaceName.parse("org.freedesktop.9x"))
		assertNull(MemberName.parse("Read.One"))
		assertThrows<IllegalArgumentException> { InterfaceName("Settings") }
		assertThrows<IllegalArgumentException> {
			Value.Array(Type.Basic.String, listOf(Value.UInt32(1u)))
		}
		assertThrows<IllegalArgumentException> { Type.Struct(emptyList()) }
	}
}
