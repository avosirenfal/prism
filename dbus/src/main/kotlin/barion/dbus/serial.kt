@file:OptIn(ExperimentalSerializationApi::class)

package barion.dbus

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/*
 * Kotlin values as D-Bus values, by kotlinx.serialization. A type's D-Bus type is worked out from its serial
 * descriptor ([typeOf]), so no signature is written by hand, and a type D-Bus cannot hold is refused with the path to
 * the field that cannot be held. Encoding builds a [Value] tree, decoding reads one; the wire format (wire.kt) is the
 * other half, and knows nothing of Kotlin's types.
 *
 * What each Kotlin type is on D-Bus, by default:
 *
 *     Boolean b    Byte, UByte y    Short n    UShort q    Int i    UInt u    Long x    ULong t    Float, Double d
 *     String s     ObjectPath o     Signature g     Variant<T>, AnyVariant v
 *     List<T>, Set<T>, Array<T>, ByteArray  aT          Map<K, V>  a{KV}, K a basic type
 *     a class  (its fields), a struct; or, as a message's body, its fields as the body's values
 *     an enum  u, its entries' ordinals or their @Code; or s, their serial names, with @AsString
 *     a value class  what it holds
 *
 * Anything else (a nullable, a Char, a sealed or open class, a class with no fields as a value) has no D-Bus type,
 * and a serializer of one is refused. A custom serializer (@Serializable(with = ...)) is a D-Bus type by the
 * descriptor it gives.
 */

/** The D-Bus code of an enum's entry, where it is not the entry's ordinal. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Code(
	val value: Long,
)

/** An enum written as its entries' serial names (`s`) rather than their numbers (`u`). */
@SerialInfo
@Target(AnnotationTarget.CLASS)
annotation class AsString

/** A Kotlin type D-Bus has no type for, as a serializer describes it. */
class UnsupportedType(
	val path: String,
	why: String,
) : IllegalArgumentException("$path: $why")

/** What decoding gave: the value, or why there is none. */
sealed interface Decoded<out T> {
	data class Read<T>(
		val value: T,
	) : Decoded<T>

	/** The value's signature is not the one the Kotlin type has. */
	data class Mismatched(
		val expected: Signature,
		val got: Signature,
	) : Decoded<Nothing>

	data class Unreadable(
		val why: String,
	) : Decoded<Nothing>
}

/** The value, or null where there is none. */
fun <T> Decoded<T>.orNull(): T? = (this as? Decoded.Read)?.value

/**
 * A value of [T] in a variant: on the wire, [T]'s signature and then the value. Decoding one checks that the
 * signature the variant carries is [T]'s, so a variant of another type is a [Decoded.Mismatched], never a wrong read.
 */
@Serializable(with = VariantSerializer::class)
data class Variant<T>(
	val value: T,
)

/** A variant of any type: what it holds, as a [Value], to decode as whatever type it turns out to be. */
@Serializable(with = AnyVariantSerializer::class)
data class AnyVariant(
	val value: Value,
) {
	val signature: Signature get() = Signature.of(value.type)

	/** What this holds, as [deserializer] reads it, if its signature is that type's. */
	fun <T> decode(deserializer: DeserializationStrategy<T>): Decoded<T> =
		decodeValue(deserializer, value)

	inline fun <reified T> decode(): Decoded<T> = decode(serializer<T>())

	companion object {
		fun <T> of(serializer: SerializationStrategy<T>, value: T): AnyVariant =
			AnyVariant(encodeValue(serializer, value))

		inline fun <reified T> of(value: T): AnyVariant = of(serializer<T>(), value)
	}
}

class VariantSerializer<T>(
	private val inner: KSerializer<T>,
) : KSerializer<Variant<T>> {
	override val descriptor: SerialDescriptor =
		buildClassSerialDescriptor(SpecialNames.VARIANT) { element("value", inner.descriptor) }

	override fun serialize(encoder: Encoder, value: Variant<T>) =
		treeEncoder(encoder).variant(encodeValue(inner, value.value))

	override fun deserialize(decoder: Decoder): Variant<T> {
		val held = treeDecoder(decoder).variant().value
		val expected = typeOf(inner.descriptor)

		if (held.type != expected)
			throw SignatureMismatch(Signature.of(expected), Signature.of(held.type))

		return Variant(decodeTree(inner, held))
	}
}

object AnyVariantSerializer : KSerializer<AnyVariant> {
	override val descriptor: SerialDescriptor =
		buildClassSerialDescriptor(SpecialNames.ANY_VARIANT)

	override fun serialize(encoder: Encoder, value: AnyVariant) =
		treeEncoder(encoder).variant(value.value)

	override fun deserialize(decoder: Decoder): AnyVariant =
		AnyVariant(treeDecoder(decoder).variant().value)
}

private fun treeEncoder(encoder: Encoder): TreeEncoder =
	encoder as? TreeEncoder
		?: throw SerializationException("a D-Bus variant is written only by the D-Bus format")

private fun treeDecoder(decoder: Decoder): TreeDecoder =
	decoder as? TreeDecoder
		?: throw SerializationException("a D-Bus variant is read only by the D-Bus format")

// ---- types, from descriptors -------------------------------------------------------------------------------

/** The D-Bus type of values [descriptor] describes, or [UnsupportedType] naming what D-Bus cannot hold. */
fun typeOf(descriptor: SerialDescriptor): Type =
	typeOf(descriptor, descriptor.serialName.substringAfterLast('.'))

/**
 * The signature of a message body of [descriptor]: a class's fields, each a value of the body (its arguments, in
 * order); nothing for an object or `Unit`; anything else, one value.
 */
fun bodySignatureOf(descriptor: SerialDescriptor): Signature =
	if (isArgumentList(descriptor))
		Signature(
			fields(descriptor, descriptor.serialName.substringAfterLast('.'), allowEmpty = true),
		)
	else
		Signature.of(typeOf(descriptor))

/** Whether a body of [descriptor] is its fields, one value each, rather than one value. */
private fun isArgumentList(descriptor: SerialDescriptor): Boolean =
	descriptor.serialName !in SPECIAL &&
		!descriptor.isInline &&
		(descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT)

private val SPECIAL =
	setOf(
		SpecialNames.OBJECT_PATH,
		SpecialNames.SIGNATURE,
		SpecialNames.VARIANT,
		SpecialNames.ANY_VARIANT,
	)

private fun typeOf(descriptor: SerialDescriptor, path: String): Type {
	if (descriptor.isNullable)
		throw UnsupportedType(
			path,
			"D-Bus has no null; give it a value, or carry it in a variant",
		)

	when (descriptor.serialName) {
		SpecialNames.OBJECT_PATH -> return Type.Basic.ObjectPath
		SpecialNames.SIGNATURE -> return Type.Basic.Signature
		SpecialNames.VARIANT, SpecialNames.ANY_VARIANT -> return Type.Variant
		"kotlin.UByte" -> return Type.Basic.Byte
		"kotlin.UShort" -> return Type.Basic.UInt16
		"kotlin.UInt" -> return Type.Basic.UInt32
		"kotlin.ULong" -> return Type.Basic.UInt64
	}

	if (descriptor.isInline)
		return typeOf(descriptor.getElementDescriptor(0), path)

	return when (val kind = descriptor.kind) {
		PrimitiveKind.BOOLEAN -> Type.Basic.Boolean
		PrimitiveKind.BYTE -> Type.Basic.Byte
		PrimitiveKind.SHORT -> Type.Basic.Int16
		PrimitiveKind.INT -> Type.Basic.Int32
		PrimitiveKind.LONG -> Type.Basic.Int64
		PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> Type.Basic.Double
		PrimitiveKind.STRING -> Type.Basic.String
		PrimitiveKind.CHAR -> throw UnsupportedType(
			path,
			"D-Bus has no character type; use a String or a number",
		)
		SerialKind.ENUM -> enumType(descriptor, path)
		StructureKind.CLASS -> Type.Struct(fields(descriptor, path, allowEmpty = false))
		StructureKind.OBJECT ->
			throw UnsupportedType(
				path,
				"an object has no fields to send, and D-Bus has no empty struct",
			)
		StructureKind.LIST -> Type.Array(typeOf(descriptor.getElementDescriptor(0), "$path[]"))
		StructureKind.MAP -> {
			val key = typeOf(descriptor.getElementDescriptor(0), "$path{key}")

			Type.Dict(
				key as? Type.Basic
					?: throw UnsupportedType(
						path,
						"a D-Bus dictionary is keyed by a basic type, not $key",
					),
				typeOf(descriptor.getElementDescriptor(1), "$path{value}"),
			)
		}
		is PolymorphicKind ->
			throw UnsupportedType(
				path,
				"D-Bus has no tagged unions; send each case with its own method, or a variant",
			)
		SerialKind.CONTEXTUAL -> throw UnsupportedType(
			path,
			"a contextual serializer has no type until it is resolved",
		)
	}
}

private fun fields(
	descriptor: SerialDescriptor,
	path: String,
	allowEmpty: Boolean,
): List<Type> {
	val types =
		(0 until descriptor.elementsCount).map { index ->
			typeOf(
				descriptor.getElementDescriptor(index),
				"$path.${descriptor.getElementName(index)}",
			)
		}

	if (types.isEmpty() && !allowEmpty)
		throw UnsupportedType(
			path,
			"a class with no fields has no D-Bus type (D-Bus has no empty struct)",
		)

	return types
}

private fun enumType(descriptor: SerialDescriptor, path: String): Type {
	if (descriptor.annotations.any { it is AsString })
		return Type.Basic.String

	val codes = (0 until descriptor.elementsCount).map { codeOf(descriptor, it) }
	val twice = codes.groupBy { it }.filterValues { it.size > 1 }.keys

	if (twice.isNotEmpty())
		throw UnsupportedType(path, "two entries have the code ${twice.first()}")

	return Type.Basic.UInt32
}

/** The number an enum's entry is written as: its @Code, or its ordinal. */
private fun codeOf(descriptor: SerialDescriptor, index: Int): UInt {
	val code =
		descriptor
			.getElementAnnotations(index)
			.filterIsInstance<Code>()
			.singleOrNull()
			?.value
			?: index.toLong()

	require(code in 0..UInt.MAX_VALUE.toLong()) {
		"${descriptor.serialName}.${descriptor.getElementName(
			index,
		)}: a code is an unsigned 32-bit number"
	}

	return code.toUInt()
}

// ---- encoding: Kotlin values into a tree ------------------------------------------------------------------

/** [value] as a message body of [serializer]'s type. */
fun <T> encodeBody(serializer: SerializationStrategy<T>, value: T): Body {
	val descriptor = serializer.descriptor
	val types = bodySignatureOf(descriptor).types
	val encoded = ArrayList<Value>()

	serializer.serialize(
		BodyEncoder(isArgumentList(descriptor), types) {
			encoded += it
		},
		value,
	)

	return Body(encoded.toList())
}

/** [value] as one D-Bus value of [serializer]'s type. */
fun <T> encodeValue(serializer: SerializationStrategy<T>, value: T): Value {
	var encoded: Value? = null

	serializer.serialize(
		SingleEncoder(typeOf(serializer.descriptor)) { encoded = it },
		value,
	)

	return encoded
		?: throw SerializationException("${serializer.descriptor.serialName} wrote no value")
}

/**
 * The encoder every value is written through: [current] is the D-Bus type of the value about to be written, which
 * says how a Kotlin value is written (an Int as `i` or, for a UInt, `u`; a String as `s`, `o` or `g`), and a written
 * value is handed to [accept].
 */
internal abstract class TreeEncoder : AbstractEncoder() {
	override val serializersModule: SerializersModule = EmptySerializersModule()

	protected abstract val current: Type

	protected abstract fun accept(value: Value)

	/** A variant holding [held]: what [VariantSerializer] and [AnyVariantSerializer] write. */
	fun variant(held: Value) = accept(Value.Variant(held))

	override fun encodeBoolean(value: Boolean) = accept(Value.Boolean(value))

	override fun encodeByte(value: Byte) = accept(Value.Byte(value.toUByte()))

	override fun encodeShort(value: Short) =
		accept(
			when (current) {
				Type.Basic.UInt16 -> Value.UInt16(value.toUShort())
				else -> Value.Int16(value)
			},
		)

	override fun encodeInt(value: Int) =
		accept(
			when (current) {
				Type.Basic.UInt32 -> Value.UInt32(value.toUInt())
				else -> Value.Int32(value)
			},
		)

	override fun encodeLong(value: Long) =
		accept(
			when (current) {
				Type.Basic.UInt64 -> Value.UInt64(value.toULong())
				else -> Value.Int64(value)
			},
		)

	override fun encodeFloat(value: Float) = accept(Value.Double(value.toDouble()))

	override fun encodeDouble(value: Double) = accept(Value.Double(value))

	override fun encodeChar(value: Char) =
		throw SerializationException("D-Bus has no character type")

	override fun encodeString(value: String) =
		accept(
			when (current) {
				Type.Basic.ObjectPath -> Value.ObjectPath(ObjectPath(value))
				Type.Basic.Signature -> Value.Signature(SignatureSerializer.parsed(value))
				else -> Value.String(value)
			},
		)

	override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) =
		accept(
			if (current == Type.Basic.String)
				Value.String(enumDescriptor.getElementName(index))
			else
				Value.UInt32(codeOf(enumDescriptor, index)),
		)

	override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

	override fun encodeNull() = throw SerializationException("D-Bus has no null")

	override fun shouldEncodeElementDefault(
		descriptor: SerialDescriptor,
		index: Int,
	): Boolean = true

	override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
		when (val type = current) {
			is Type.Struct -> FieldsEncoder(type.fields) { accept(Value.Struct(it)) }
			is Type.Array ->
				ElementsEncoder(
					type.element,
				) { accept(Value.Array(type.element, it)) }
			is Type.Dict -> EntriesEncoder(type) { accept(Value.Dict(type.key, type.value, it)) }
			else -> throw SerializationException(
				"${descriptor.serialName} is written as a structure where D-Bus has $type",
			)
		}
}

/** One value of [type]. */
private class SingleEncoder(
	private val type: Type,
	private val put: (Value) -> Unit,
) : TreeEncoder() {
	override val current: Type get() = type

	override fun accept(value: Value) = put(value)
}

/** A body: a class's fields, each a value of its own ([flattened]), or one value. */
private class BodyEncoder(
	private val flattened: Boolean,
	private val types: List<Type>,
	private val put: (Value) -> Unit,
) : TreeEncoder() {
	override val current: Type get() = types.single()

	override fun accept(value: Value) = put(value)

	override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
		if (flattened)
			FieldsEncoder(types) { fields -> fields.forEach(put) }
		else
			super.beginStructure(descriptor)
}

/** A class's fields, each of its own type, in order; [done] is given them when the class ends. */
private class FieldsEncoder(
	private val types: List<Type>,
	private val done: (List<Value>) -> Unit,
) : TreeEncoder() {
	private val values = ArrayList<Value>(types.size)
	private var index = 0

	override val current: Type get() = types[index]

	override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
		this.index = index

		return true
	}

	override fun accept(value: Value) {
		values += value
	}

	override fun endStructure(descriptor: SerialDescriptor) = done(values.toList())
}

/** A collection's elements, each of [element]'s type. */
private class ElementsEncoder(
	private val element: Type,
	private val done: (List<Value>) -> Unit,
) : TreeEncoder() {
	private val values = ArrayList<Value>()

	override val current: Type get() = element

	override fun accept(value: Value) {
		values += value
	}

	override fun endStructure(descriptor: SerialDescriptor) = done(values.toList())
}

/** A map's entries: kotlinx writes a key at each even index and its value at the odd one after. */
private class EntriesEncoder(
	private val type: Type.Dict,
	private val done: (List<Pair<Value, Value>>) -> Unit,
) : TreeEncoder() {
	private val entries = ArrayList<Pair<Value, Value>>()
	private var key: Value? = null
	private var index = 0

	override val current: Type get() = if (index % 2 == 0) type.key else type.value

	override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
		this.index = index

		return true
	}

	override fun accept(value: Value) {
		val pending = key

		if (pending == null) {
			key = value
		} else {
			entries += pending to value
			key = null
		}
	}

	override fun endStructure(descriptor: SerialDescriptor) = done(entries.toList())
}

// ---- decoding: a tree into Kotlin values ------------------------------------------------------------------

/**
 * [body] as [deserializer]'s type, if its signature is that type's. The signatures are compared before anything is
 * decoded, so a body of another shape is a [Decoded.Mismatched] and nothing is half read.
 */
fun <T> decodeBody(deserializer: DeserializationStrategy<T>, body: Body): Decoded<T> {
	val descriptor = deserializer.descriptor
	val expected = bodySignatureOf(descriptor)

	if (body.signature != expected)
		return Decoded.Mismatched(expected, body.signature)

	return decoding {
		deserializer.deserialize(BodyDecoder(isArgumentList(descriptor), body.values))
	}
}

/** [value] as [deserializer]'s type, if its type is that type's. */
fun <T> decodeValue(deserializer: DeserializationStrategy<T>, value: Value): Decoded<T> {
	val expected = typeOf(deserializer.descriptor)

	if (value.type != expected)
		return Decoded.Mismatched(Signature.of(expected), Signature.of(value.type))

	return decoding { decodeTree(deserializer, value) }
}

/** [value], already known to be of [deserializer]'s type. */
private fun <T> decodeTree(deserializer: DeserializationStrategy<T>, value: Value): T =
	deserializer.deserialize(SingleDecoder(value))

private inline fun <T> decoding(decode: () -> T): Decoded<T> =
	try {
		Decoded.Read(decode())
	} catch (mismatch: SignatureMismatch) {
		Decoded.Mismatched(mismatch.expected, mismatch.got)
	} catch (malformed: Malformed) {
		Decoded.Unreadable(malformed.message ?: "malformed")
	} catch (refused: SerializationException) {
		Decoded.Unreadable(refused.message ?: "refused")
	} catch (refused: IllegalArgumentException) {
		Decoded.Unreadable(refused.message ?: "refused")
	}

/** A variant whose contents are not the type it was to be read as: found while decoding, reported as data. */
private class SignatureMismatch(
	val expected: Signature,
	val got: Signature,
) : Exception("expected $expected, got $got")

/** The decoder every value is read through: [next] is the value to read now. */
internal abstract class TreeDecoder : AbstractDecoder() {
	override val serializersModule: SerializersModule = EmptySerializersModule()

	protected abstract fun next(): Value

	/** A variant: what [VariantSerializer] and [AnyVariantSerializer] read. */
	fun variant(): Value.Variant = expect()

	private inline fun <reified V : Value> expect(): V =
		next().let {
			it as? V
				?: throw Malformed("${it.type} where ${V::class.simpleName} was to be read")
		}

	override fun decodeBoolean(): Boolean = expect<Value.Boolean>().value

	override fun decodeByte(): Byte = expect<Value.Byte>().value.toByte()

	override fun decodeShort(): Short =
		when (val value = next()) {
			is Value.Int16 -> value.value
			is Value.UInt16 -> value.value.toShort()
			else -> throw Malformed("${value.type} where a 16-bit number was to be read")
		}

	override fun decodeInt(): Int =
		when (val value = next()) {
			is Value.Int32 -> value.value
			is Value.UInt32 -> value.value.toInt()
			else -> throw Malformed("${value.type} where a 32-bit number was to be read")
		}

	override fun decodeLong(): Long =
		when (val value = next()) {
			is Value.Int64 -> value.value
			is Value.UInt64 -> value.value.toLong()
			else -> throw Malformed("${value.type} where a 64-bit number was to be read")
		}

	override fun decodeFloat(): Float = expect<Value.Double>().value.toFloat()

	override fun decodeDouble(): Double = expect<Value.Double>().value

	override fun decodeChar(): Char =
		throw SerializationException("D-Bus has no character type")

	override fun decodeString(): String =
		when (val value = next()) {
			is Value.String -> value.value
			is Value.ObjectPath -> value.value.value
			is Value.Signature -> value.value.code
			else -> throw Malformed("${value.type} where text was to be read")
		}

	/** An enum's entry, by the serial name a string gives or the code a number gives. */
	override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
		val entries = 0 until enumDescriptor.elementsCount

		val (found, said) =
			when (val value = next()) {
				is Value.String ->
					entries.map(enumDescriptor::getElementName).indexOf(value.value) to
						"'${value.value}'"
				is Value.UInt32 ->
					entries.map { codeOf(enumDescriptor, it) }.indexOf(value.value) to
						"of code ${value.value}"
				else -> throw Malformed("${value.type} where an enum was to be read")
			}

		return found.takeIf { it >= 0 }
			?: throw Malformed("${enumDescriptor.serialName} has no entry $said")
	}

	override fun decodeInline(descriptor: SerialDescriptor): Decoder = this

	override fun decodeNotNullMark(): Boolean = true

	override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
		when (val value = next()) {
			is Value.Struct -> FieldsDecoder(value.fields, value.fields.size)
			is Value.Array -> FieldsDecoder(value.items, value.items.size)
			is Value.Dict ->
				FieldsDecoder(
					value.entries.flatMap { (key, entry) ->
						listOf(key, entry)
					},
					value.entries.size,
				)
			else -> throw Malformed("${value.type} where ${descriptor.serialName} was to be read")
		}
}

private class SingleDecoder(
	private val value: Value,
) : TreeDecoder() {
	override fun next(): Value = value

	override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
		CompositeDecoder.DECODE_DONE
}

/** A body: a class's fields, each one of [values] ([flattened]), or the one value. */
private class BodyDecoder(
	private val flattened: Boolean,
	private val values: List<Value>,
) : TreeDecoder() {
	override fun next(): Value = values.single()

	override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
		CompositeDecoder.DECODE_DONE

	override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
		if (flattened)
			FieldsDecoder(values, values.size)
		else
			super.beginStructure(descriptor)
}

/** A structure's values in order: a struct's fields, an array's items, a dictionary's keys and values in turn. */
private class FieldsDecoder(
	private val values: List<Value>,
	private val size: Int,
) : TreeDecoder() {
	private var position = 0

	override fun next(): Value =
		values.getOrNull(position++)
			?: throw Malformed("a structure ends before its last field")

	override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
		if (position < values.size)
			position
		else
			CompositeDecoder.DECODE_DONE

	override fun decodeSequentially(): Boolean = true

	override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = size
}

/** [text] as a signature, for a value written as one. */
internal fun SignatureSerializer.parsed(text: String): Signature =
	when (val parsed = Signature.parse(text)) {
		is Signature.Parsed.Valid -> parsed.signature
		is Signature.Parsed.Invalid -> throw SerializationException(
			"'$text' is not a signature: ${parsed.why}",
		)
	}
