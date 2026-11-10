package prism.dbus

import java.nio.ByteOrder
import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@Serializable
data class SettingKey(
	val namespace: String,
	val key: String,
)

@Serializable
data class Point(
	val x: Int,
	val y: Int,
)

@Serializable
enum class Scheme {
	NoPreference,
	PreferDark,
	PreferLight,
}

@Serializable
@AsString
enum class Speed {
	Fast,
	Slow,
}

@Serializable
enum class Coded {
	@Code(10)
	Ten,

	@Code(20)
	Twenty,
}

@Serializable
@JvmInline
value class Label(
	val text: String,
)

/** An instant as D-Bus's 64-bit number of milliseconds: a type of the user's own, by its own serializer. */
object InstantAsMillis : KSerializer<Instant> {
	override val descriptor: SerialDescriptor =
		PrimitiveSerialDescriptor("test.InstantAsMillis", PrimitiveKind.LONG)

	override fun serialize(encoder: Encoder, value: Instant) =
		encoder.encodeLong(value.toEpochMilli())

	override fun deserialize(decoder: Decoder): Instant =
		Instant.ofEpochMilli(decoder.decodeLong())
}

/** Every kind of field at once. */
@Serializable
data class Everything(
	val name: String,
	val points: List<Point>,
	val settings: Map<String, AnyVariant>,
	val id: UInt,
	val small: UByte,
	val wide: ULong,
	val path: ObjectPath,
	val raw: ByteArray,
	val scheme: Scheme,
	val speed: Speed,
	val coded: Coded,
	val label: Label,
	@Serializable(with = InstantAsMillis::class) val at: Instant,
	val typed: Variant<UInt>,
	val flag: Boolean,
	val ratio: Double,
) {
	// A ByteArray compares by identity, so the test compares the encoded trees instead.
	override fun equals(other: Any?): Boolean =
		other is Everything && encodeBody(serializer(), this) == encodeBody(serializer(), other)

	override fun hashCode(): Int = name.hashCode()
}

@Serializable
data class WithNull(
	val maybe: String?,
)

@Serializable
data class WithChar(
	val letter: Char,
)

@Serializable
sealed interface Shape

@Serializable
data class WithSealed(
	val shape: Shape,
)

@Serializable
class Empty

@Serializable
data class WithEmpty(
	val nothing: Empty,
)

@Serializable
data class KeyedByStruct(
	val map: Map<Point, String>,
)

/**
 * Kotlin types as D-Bus types: the signatures worked out from them, the types refused with where and why, values
 * there and back (through the wire as well), and what does not match reported as data.
 */
class SerialTest {
	private val everything =
		Everything(
			name = "café",
			points = listOf(Point(1, 2), Point(-3, 4)),
			settings =
				mapOf(
					"color-scheme" to AnyVariant.of(1u),
					"font" to AnyVariant.of("Inter 11"),
				),
			id = UInt.MAX_VALUE,
			small = 200u,
			wide = ULong.MAX_VALUE,
			path = ObjectPath("/org/freedesktop/portal/desktop"),
			raw = byteArrayOf(1, 2, 3),
			scheme = Scheme.PreferDark,
			speed = Speed.Slow,
			coded = Coded.Twenty,
			label = Label("Documents"),
			at = Instant.ofEpochMilli(1_762_600_000_000),
			typed = Variant(7u),
			flag = true,
			ratio = 0.75,
		)

	@Test
	fun signaturesAreWorkedOutFromTheTypes() {
		assertEquals("ss", bodySignatureOf(serializer<SettingKey>().descriptor).code)
		// name s, points a(ii), settings a{sv}, id u, small y, wide t, path o, raw ay, scheme u, speed s, coded u,
		// label s, at x, typed v, flag b, ratio d.
		assertEquals(
			"sa(ii)a{sv}uytoayususxvbd",
			bodySignatureOf(serializer<Everything>().descriptor).code,
		)
		assertEquals("", bodySignatureOf(serializer<Unit>().descriptor).code)
		assertEquals("s", bodySignatureOf(serializer<String>().descriptor).code)
		assertEquals("v", bodySignatureOf(serializer<AnyVariant>().descriptor).code)
		assertEquals("(ii)", typeOf(serializer<Point>().descriptor).code)
		assertEquals("a{s(ii)}", typeOf(serializer<Map<String, Point>>().descriptor).code)
		assertEquals("aay", typeOf(serializer<List<ByteArray>>().descriptor).code)
	}

	@Test
	fun aTypeDBusCannotHoldIsRefusedWithWhereAndWhy() {
		fun refused(descriptor: SerialDescriptor): String =
			assertThrows<UnsupportedType> { bodySignatureOf(descriptor) }.message!!

		assertTrue(
			"WithNull.maybe: D-Bus has no null" in refused(serializer<WithNull>().descriptor),
		)
		assertTrue(
			"WithChar.letter: D-Bus has no character type" in
				refused(serializer<WithChar>().descriptor),
		)
		assertTrue(
			"WithSealed.shape: D-Bus has no tagged unions" in
				refused(serializer<WithSealed>().descriptor),
		)
		assertTrue(
			"WithEmpty.nothing: a class with no fields" in
				refused(serializer<WithEmpty>().descriptor),
		)
		assertTrue(
			"keyed by a basic type, not (ii)" in refused(serializer<KeyedByStruct>().descriptor),
		)
	}

	@Test
	fun aValueComesBackAsItWentInThroughTheWire() {
		val body = encodeBody(serializer<Everything>(), everything)
		val bytes =
			WireWriter(ByteOrder.BIG_ENDIAN)
				.apply {
					body.values.forEach(::write)
				}.bytes()
		val reader = WireReader(bytes, ByteOrder.BIG_ENDIAN)
		val read = Body(body.signature.types.map(reader::read))
		val decoded =
			assertInstanceOf(Decoded.Read::class.java, decodeBody(serializer<Everything>(), read))

		assertEquals(body, read)
		assertEquals(everything, decoded.value)
		assertArrayEquals(byteArrayOf(1, 2, 3), (decoded.value as Everything).raw)
	}

	@Test
	fun eachKindIsWrittenAsItsDBusTypeSays() {
		val values = encodeBody(serializer<Everything>(), everything).values

		assertEquals(Value.UInt32(UInt.MAX_VALUE), values[3])
		assertEquals(Value.Byte(200u), values[4])
		assertEquals(Value.ObjectPath(ObjectPath("/org/freedesktop/portal/desktop")), values[6])
		assertEquals(Value.UInt32(1u), values[8])
		assertEquals(Value.String("Slow"), values[9])
		assertEquals(Value.UInt32(20u), values[10])
		assertEquals(Value.String("Documents"), values[11])
		assertEquals(Value.Int64(1_762_600_000_000), values[12])
		assertEquals(Value.Variant(Value.UInt32(7u)), values[13])
	}

	@Test
	fun aBodyOfAnotherShapeIsAMismatchNotAWrongRead() {
		val body = Body(listOf(Value.String("org.freedesktop.appearance"), Value.UInt32(1u)))

		assertEquals(
			Decoded.Mismatched(Signature.of(Type.Basic.String, Type.Basic.String), body.signature),
			decodeBody(serializer<SettingKey>(), body),
		)
	}

	@Test
	fun aTypedVariantChecksWhatItHolds() {
		val holdsText = Body(listOf(Value.Variant(Value.String("dark"))))

		val mismatched =
			assertInstanceOf(
				Decoded.Mismatched::class.java,
				decodeBody(serializer<Variant<UInt>>(), holdsText),
			)

		assertEquals("u", mismatched.expected.code)
		assertEquals("s", mismatched.got.code)
		assertEquals(
			Decoded.Read(Variant(Scheme.PreferLight)),
			decodeBody(
				serializer<Variant<Scheme>>(),
				Body(listOf(Value.Variant(Value.UInt32(2u)))),
			),
		)
		// A variant within a variant, as the portal's older Read gives a setting.
		assertEquals(
			Decoded.Read(Variant(Variant(1u))),
			decodeBody(
				serializer<Variant<Variant<UInt>>>(),
				Body(listOf(Value.Variant(Value.Variant(Value.UInt32(1u))))),
			),
		)
	}

	@Test
	fun anAnyVariantIsDecodedAsWhatItTurnsOutToBe() {
		val held = AnyVariant.of(Point(3, 4))

		assertEquals("(ii)", held.signature.code)
		assertEquals(Decoded.Read(Point(3, 4)), held.decode<Point>())
		assertInstanceOf(Decoded.Mismatched::class.java, held.decode<String>())
	}

	@Test
	fun anEnumCodeNoEntryHasIsUnreadable() {
		val unread =
			assertInstanceOf(
				Decoded.Unreadable::class.java,
				decodeValue(serializer<Coded>(), Value.UInt32(7u)),
			)

		assertTrue("no entry of code 7" in unread.why, unread.why)
		assertEquals(
			Decoded.Read(Coded.Ten),
			decodeValue(serializer<Coded>(), Value.UInt32(10u)),
		)
	}
}
