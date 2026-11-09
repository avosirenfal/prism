package barion.dbus

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * D-Bus's types: what a signature string says. A signature is a list of complete types, each a code (`s` a string,
 * `u` an unsigned 32-bit number) or a container of others (`a` an array, `(...)` a struct, `a{..}` a dictionary,
 * `v` a variant, which carries its own signature with its value). Here a type is data, and the containers hold only
 * what D-Bus allows: a dictionary's key is a basic type by its declaration, a struct has at least one field, and a
 * dictionary entry exists only as a dictionary, never on its own.
 *
 * Nobody writes a signature by hand: the D-Bus format works one out from a kotlinx.serialization descriptor
 * (`typeOf`), and a signature that arrives in a message is parsed ([Signature.parse]) and compared with it.
 */

/** A complete D-Bus type. */
sealed interface Type {
	/** The type as a signature writes it. */
	val code: kotlin.String

	/** What a value of this type is aligned to on the wire, from the start of the message. */
	val alignment: Int

	/** A type a dictionary may be keyed by: a fixed-size number, or a string of one of the three kinds. */
	sealed class Basic(
		override val code: kotlin.String,
		override val alignment: Int,
	) : Type {
		override fun toString(): kotlin.String = code

		data object Byte : Basic("y", 1)

		/** A boolean, as a 32-bit 0 or 1. */
		data object Boolean : Basic("b", 4)

		data object Int16 : Basic("n", 2)

		data object UInt16 : Basic("q", 2)

		data object Int32 : Basic("i", 4)

		data object UInt32 : Basic("u", 4)

		data object Int64 : Basic("x", 8)

		data object UInt64 : Basic("t", 8)

		data object Double : Basic("d", 8)

		/** UTF-8 text, with no NUL in it. */
		data object String : Basic("s", 4)

		data object ObjectPath : Basic("o", 4)

		data object Signature : Basic("g", 1)

		/** An index into the file descriptors a message carries, which this library does not negotiate. */
		data object UnixFd : Basic("h", 4)

		companion object {
			/**
			 * The basic type [code] names. A `when`, not a list of the objects: a list built in this companion would
			 * hold null for whichever object's own initialisation started the companion's.
			 */
			fun of(code: Char): Basic? =
				when (code) {
					'y' -> Byte
					'b' -> Boolean
					'n' -> Int16
					'q' -> UInt16
					'i' -> Int32
					'u' -> UInt32
					'x' -> Int64
					't' -> UInt64
					'd' -> Double
					's' -> String
					'o' -> ObjectPath
					'g' -> Signature
					'h' -> UnixFd
					else -> null
				}
		}
	}

	/** Values of [element], any number of them. */
	data class Array(
		val element: Type,
	) : Type {
		override val code: kotlin.String get() = "a${element.code}"
		override val alignment: Int get() = 4

		override fun toString(): kotlin.String = code
	}

	/** Entries of a [key] and a [value]: an array of dictionary entries, the one place an entry may be. */
	data class Dict(
		val key: Basic,
		val value: Type,
	) : Type {
		override val code: kotlin.String get() = "a{${key.code}${value.code}}"
		override val alignment: Int get() = 4

		override fun toString(): kotlin.String = code
	}

	/** Values of [fields], one of each, in order. */
	data class Struct(
		val fields: List<Type>,
	) : Type {
		init {
			require(fields.isNotEmpty()) { "D-Bus has no empty struct" }
		}

		override val code: kotlin.String get() = fields.joinToString("", "(", ")") { it.code }
		override val alignment: Int get() = 8

		override fun toString(): kotlin.String = code
	}

	/** A value of any type, which carries its type's signature with it. */
	data object Variant : Type {
		override val code: kotlin.String get() = "v"
		override val alignment: Int get() = 1
	}
}

/**
 * A list of complete types: a message body's, or a variant's (one type). What D-Bus allows of one is checked as it
 * is made: at most 255 bytes, and arrays and structs nested at most 32 deep each.
 */
@Serializable(with = SignatureSerializer::class)
class Signature(
	val types: List<Type>,
) {
	val code: String = types.joinToString("") { it.code }

	init {
		require(code.length <= MAX_BYTES) {
			"a signature is at most $MAX_BYTES bytes, not ${code.length}"
		}
		require(
			types.all {
				depth(it, arrays = 0, structs = 0)
			},
		) { "'$code' nests arrays or structs more than $MAX_DEPTH deep" }
	}

	/** The one type of a variant's signature, which holds exactly one. */
	val single: Type? get() = types.singleOrNull()

	override fun equals(other: Any?): Boolean = other is Signature && other.types == types

	override fun hashCode(): Int = types.hashCode()

	override fun toString(): String = code

	/** What parsing a signature gave: the signature, or why the text is not one. */
	sealed interface Parsed {
		data class Valid(
			val signature: Signature,
		) : Parsed

		data class Invalid(
			val text: String,
			val why: String,
		) : Parsed
	}

	companion object {
		const val MAX_BYTES = 255
		const val MAX_DEPTH = 32

		val EMPTY = Signature(emptyList())

		fun of(vararg types: Type): Signature = Signature(types.toList())

		/** [text] as a signature, or why it is not one. */
		fun parse(text: String): Parsed {
			val types = ArrayList<Type>()
			var at = 0

			while (at < text.length) {
				val (type, next) =
					parseType(text, at)
						?: return Parsed.Invalid(text, "no complete type at $at")

				types += type
				at = next
			}

			return try {
				Parsed.Valid(Signature(types))
			} catch (refused: IllegalArgumentException) {
				Parsed.Invalid(text, refused.message ?: "refused")
			}
		}

		/** One complete type from [at], and where the next starts; null where the text holds none there. */
		private fun parseType(text: String, at: Int): Pair<Type, Int>? {
			val code = text.getOrNull(at) ?: return null

			Type.Basic.of(code)?.let { return it to at + 1 }

			return when (code) {
				'v' -> Type.Variant to at + 1
				'a' ->
					if (text.getOrNull(at + 1) == '{') {
						val key = text.getOrNull(at + 2)?.let(Type.Basic::of) ?: return null
						val (value, next) = parseType(text, at + 3) ?: return null

						if (text.getOrNull(next) != '}')
							null
						else
							Type.Dict(key, value) to next + 1
					} else {
						parseType(text, at + 1)?.let { (element, next) -> Type.Array(element) to next }
					}
				'(' -> {
					val fields = ArrayList<Type>()
					var next = at + 1

					while (text.getOrNull(next) != ')') {
						val (field, after) = parseType(text, next) ?: return null

						fields += field
						next = after
					}

					if (fields.isEmpty())
						null
					else
						Type.Struct(fields) to next + 1
				}
				else -> null
			}
		}

		private fun depth(type: Type, arrays: Int, structs: Int): Boolean =
			arrays <= MAX_DEPTH &&
				structs <= MAX_DEPTH &&
				when (type) {
					is Type.Basic, Type.Variant -> true
					is Type.Array -> depth(type.element, arrays + 1, structs)
					is Type.Dict -> depth(type.value, arrays + 1, structs + 1)
					is Type.Struct -> type.fields.all { depth(it, arrays, structs + 1) }
				}
	}
}

/** How kotlinx.serialization reads and writes a [Signature]: as its text, under a name that makes its type `g`. */
object SignatureSerializer : KSerializer<Signature> {
	override val descriptor: SerialDescriptor =
		PrimitiveSerialDescriptor(SpecialNames.SIGNATURE, PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: Signature) =
		encoder.encodeString(value.code)

	override fun deserialize(decoder: Decoder): Signature =
		when (val parsed = Signature.parse(decoder.decodeString())) {
			is Signature.Parsed.Valid -> parsed.signature
			is Signature.Parsed.Invalid -> throw Malformed(
				"'${parsed.text}' is not a signature: ${parsed.why}",
			)
		}
}
