package barion.dbus

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/*
 * D-Bus's wire format: values as bytes and back. Every value is aligned to its type's alignment, counted from the
 * start of the message, with zero bytes as padding. A string is its length, its UTF-8 and a NUL; a signature the
 * same with a one-byte length; an array its length in bytes and then its elements (the padding before the first not
 * counted); a struct or a dictionary entry starts on 8; a variant is its value's signature and then the value.
 *
 * The reader is strict: padding that is not zero, a string that is not UTF-8 or holds a NUL, a boolean that is
 * neither 0 nor 1, an array whose elements do not end where its length says, or anything that runs past the end, is
 * a [Malformed] message, and nothing of it is used.
 */

/** The longest array D-Bus allows, in bytes. */
internal const val MAX_ARRAY_BYTES = 64 * 1024 * 1024

/** How deep containers and variants may nest within one value. */
private const val MAX_NESTING = 64

/** Values written in the wire format, aligned from the first byte written, which is the start of the message. */
internal class WireWriter(
	private val order: ByteOrder,
) {
	private var buffer: ByteBuffer = ByteBuffer.allocate(256).order(order)

	val position: Int get() = buffer.position()

	fun bytes(): ByteArray = buffer.array().copyOf(buffer.position())

	fun pad(alignment: Int) {
		while (buffer.position() % alignment != 0)
			byte(0)
	}

	fun byte(value: Int) {
		room(1)
		buffer.put(value.toByte())
	}

	fun int(value: Int) {
		pad(4)
		room(4)
		buffer.putInt(value)
	}

	/** [value] over the four bytes at [at], already written: an array's length, once its elements are. */
	fun intAt(at: Int, value: Int) {
		buffer.putInt(at, value)
	}

	fun write(value: Value) {
		pad(value.type.alignment)
		room(8)

		when (value) {
			is Value.Byte -> buffer.put(value.value.toByte())
			is Value.Boolean -> buffer.putInt(if (value.value) 1 else 0)
			is Value.Int16 -> buffer.putShort(value.value)
			is Value.UInt16 -> buffer.putShort(value.value.toShort())
			is Value.Int32 -> buffer.putInt(value.value)
			is Value.UInt32 -> buffer.putInt(value.value.toInt())
			is Value.Int64 -> buffer.putLong(value.value)
			is Value.UInt64 -> buffer.putLong(value.value.toLong())
			is Value.Double -> buffer.putDouble(value.value)
			is Value.UnixFd -> buffer.putInt(value.index.toInt())
			is Value.String -> text(value.value)
			is Value.ObjectPath -> text(value.value.value)
			is Value.Signature -> signature(value.value)
			is Value.Array -> array(value.element.alignment) { value.items.forEach(::write) }
			is Value.Dict ->
				array(8) {
					for ((key, entry) in value.entries) {
						pad(8)
						write(key)
						write(entry)
					}
				}
			is Value.Struct -> value.fields.forEach(::write)
			is Value.Variant -> {
				signature(Signature.of(value.value.type))
				write(value.value)
			}
		}
	}

	private fun text(text: String) {
		val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)

		int(bytes.size)
		room(bytes.size + 1)
		buffer.put(bytes)
		buffer.put(0)
	}

	private fun signature(signature: Signature) {
		val bytes = signature.code.encodeToByteArray()

		byte(bytes.size)
		room(bytes.size + 1)
		buffer.put(bytes)
		buffer.put(0)
	}

	/** An array: its length, the padding to its elements' alignment, the elements [elements] writes, the length set. */
	private fun array(alignment: Int, elements: () -> Unit) {
		int(0)

		val lengthAt = buffer.position() - 4

		pad(alignment)

		val start = buffer.position()

		elements()

		val length = buffer.position() - start

		require(length <= MAX_ARRAY_BYTES) {
			"an array of $length bytes is longer than D-Bus allows"
		}
		intAt(lengthAt, length)
	}

	/** Room for [bytes] more, the buffer doubled as often as it takes. */
	private fun room(bytes: Int) {
		if (buffer.remaining() >= bytes)
			return

		var capacity = buffer.capacity() * 2

		while (capacity - buffer.position() < bytes)
			capacity *= 2

		val grown = ByteBuffer.allocate(capacity).order(order)

		grown.put(buffer.array(), 0, buffer.position())
		buffer = grown
	}
}

/**
 * Values read from the wire format. [bytes] holds the whole message, so a position in it is a position from the start
 * of the message, which is what alignment counts from.
 */
internal class WireReader(
	bytes: ByteArray,
	order: ByteOrder,
	start: Int = 0,
	private val end: Int = bytes.size,
) {
	private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(order).position(start)

	val position: Int get() = buffer.position()

	val atEnd: Boolean get() = buffer.position() >= end

	/** Moves to [alignment], checking that what it skips is padding. */
	fun pad(alignment: Int) {
		while (buffer.position() % alignment != 0) {
			need(1)
			if (buffer.get() != 0.toByte())
				throw Malformed("padding at ${buffer.position() - 1} is not zero")
		}
	}

	fun byte(): Int {
		need(1)

		return buffer.get().toInt() and 0xFF
	}

	fun uint(): Int {
		pad(4)
		need(4)

		return buffer.int
	}

	/** One value of [type]. */
	fun read(type: Type, depth: Int = 0): Value {
		if (depth > MAX_NESTING)
			throw Malformed("values nested more than $MAX_NESTING deep")
		pad(type.alignment)

		return when (type) {
			Type.Basic.Byte -> Value.Byte(byte().toUByte())
			Type.Basic.Boolean ->
				when (val word = fixed(4) { buffer.int }) {
					0 -> Value.Boolean(false)
					1 -> Value.Boolean(true)
					else -> throw Malformed("a boolean of $word")
				}
			Type.Basic.Int16 -> Value.Int16(fixed(2) { buffer.short })
			Type.Basic.UInt16 -> Value.UInt16(fixed(2) { buffer.short }.toUShort())
			Type.Basic.Int32 -> Value.Int32(fixed(4) { buffer.int })
			Type.Basic.UInt32 -> Value.UInt32(fixed(4) { buffer.int }.toUInt())
			Type.Basic.Int64 -> Value.Int64(fixed(8) { buffer.long })
			Type.Basic.UInt64 -> Value.UInt64(fixed(8) { buffer.long }.toULong())
			Type.Basic.Double -> Value.Double(fixed(8) { buffer.double })
			Type.Basic.UnixFd -> Value.UnixFd(fixed(4) { buffer.int }.toUInt())
			Type.Basic.String -> Value.String(text(uint()))
			Type.Basic.ObjectPath ->
				text(uint()).let {
					Value.ObjectPath(
						ObjectPath.parse(it) ?: throw Malformed("'$it' is not an object path"),
					)
				}
			Type.Basic.Signature -> Value.Signature(signature())
			is Type.Array ->
				Value.Array(
					type.element,
					elements(type.element.alignment) {
						read(
							type.element,
							depth + 1,
						)
					},
				)
			is Type.Dict ->
				Value.Dict(
					type.key,
					type.value,
					elements(8) {
						pad(8)
						read(type.key, depth + 1) to read(type.value, depth + 1)
					},
				)
			is Type.Struct -> Value.Struct(type.fields.map { read(it, depth + 1) })
			Type.Variant -> {
				val inner =
					signature().single ?: throw Malformed("a variant's signature holds one type")

				Value.Variant(read(inner, depth + 1))
			}
		}
	}

	/** A signature: a one-byte length, the text, a NUL. */
	fun signature(): Signature {
		val code = text(byte())

		return when (val parsed = Signature.parse(code)) {
			is Signature.Parsed.Valid -> parsed.signature
			is Signature.Parsed.Invalid -> throw Malformed(
				"'$code' is not a signature: ${parsed.why}",
			)
		}
	}

	/** [length] bytes of UTF-8 and the NUL after them. */
	private fun text(length: Int): String {
		if (length < 0 || length > end - buffer.position() - 1)
			throw Malformed("a string of $length bytes runs past the end")

		val bytes = ByteArray(length)

		buffer.get(bytes)
		if (buffer.get() != 0.toByte())
			throw Malformed("a string is not followed by its NUL")
		if (0.toByte() in bytes)
			throw Malformed("a string holds a NUL")

		return try {
			Charsets.UTF_8
				.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString()
		} catch (invalid: CharacterCodingException) {
			throw Malformed("a string is not UTF-8")
		}
	}

	/** An array's elements, each read by [element], after its length and the padding to [alignment]. */
	private fun <T> elements(alignment: Int, element: () -> T): List<T> {
		val length = uint()

		if (length < 0 || length > MAX_ARRAY_BYTES)
			throw Malformed("an array of $length bytes is longer than D-Bus allows")
		pad(alignment)

		val stop = buffer.position() + length

		if (stop > end)
			throw Malformed("an array of $length bytes runs past the end")

		val read = ArrayList<T>()

		while (buffer.position() < stop)
			read += element()
		if (buffer.position() != stop)
			throw Malformed(
				"an array's elements end at ${buffer.position()}, not $stop as its length says",
			)

		return read
	}

	private inline fun <T> fixed(bytes: Int, read: () -> T): T {
		need(bytes)

		return read()
	}

	private fun need(bytes: Int) {
		if (end - buffer.position() < bytes)
			throw Malformed(
				"the message ends at $end, ${bytes - (end - buffer.position())} bytes short",
			)
	}
}
