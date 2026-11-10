package prism.dbus

/**
 * A D-Bus value of any type, as immutable data: what a message's body holds between the wire (wire.kt) and the
 * Kotlin types it is decoded into (serial.kt). Each value knows its type, and a container checks that what it holds
 * is of the types it says, so a tree cannot claim one signature and hold another. Dictionaries keep their entries in
 * order, as the wire does, since D-Bus does not promise their keys are distinct.
 */
sealed interface Value {
	val type: Type

	data class Byte(
		val value: UByte,
	) : Value {
		override val type: Type get() = Type.Basic.Byte
	}

	data class Boolean(
		val value: kotlin.Boolean,
	) : Value {
		override val type: Type get() = Type.Basic.Boolean
	}

	data class Int16(
		val value: Short,
	) : Value {
		override val type: Type get() = Type.Basic.Int16
	}

	data class UInt16(
		val value: UShort,
	) : Value {
		override val type: Type get() = Type.Basic.UInt16
	}

	data class Int32(
		val value: Int,
	) : Value {
		override val type: Type get() = Type.Basic.Int32
	}

	data class UInt32(
		val value: UInt,
	) : Value {
		override val type: Type get() = Type.Basic.UInt32
	}

	data class Int64(
		val value: Long,
	) : Value {
		override val type: Type get() = Type.Basic.Int64
	}

	data class UInt64(
		val value: ULong,
	) : Value {
		override val type: Type get() = Type.Basic.UInt64
	}

	data class Double(
		val value: kotlin.Double,
	) : Value {
		override val type: Type get() = Type.Basic.Double
	}

	/** Text: D-Bus's strings are UTF-8 and hold no NUL. */
	data class String(
		val value: kotlin.String,
	) : Value {
		init {
			require('\u0000' !in value) { "a D-Bus string holds no NUL" }
		}

		override val type: Type get() = Type.Basic.String
	}

	data class ObjectPath(
		val value: prism.dbus.ObjectPath,
	) : Value {
		override val type: Type get() = Type.Basic.ObjectPath
	}

	data class Signature(
		val value: prism.dbus.Signature,
	) : Value {
		override val type: Type get() = Type.Basic.Signature
	}

	/** An index into a message's file descriptors; read as it is, since this library passes none. */
	data class UnixFd(
		val index: UInt,
	) : Value {
		override val type: Type get() = Type.Basic.UnixFd
	}

	data class Array(
		val element: Type,
		val items: List<Value>,
	) : Value {
		init {
			require(
				items.all {
					it.type == element
				},
			) { "an array of $element holds ${items.map { it.type }.distinct()}" }
		}

		override val type: Type get() = Type.Array(element)
	}

	data class Dict(
		val key: Type.Basic,
		val value: Type,
		val entries: List<Pair<Value, Value>>,
	) : Value {
		init {
			require(
				entries.all { (k, v) ->
					k.type == key && v.type == value
				},
			) { "a dictionary of $key to $value holds others" }
		}

		override val type: Type get() = Type.Dict(key, value)
	}

	data class Struct(
		val fields: List<Value>,
	) : Value {
		init {
			require(fields.isNotEmpty()) { "D-Bus has no empty struct" }
		}

		override val type: Type get() = Type.Struct(fields.map { it.type })
	}

	/** A value that carries its own type: its signature goes on the wire before it. */
	data class Variant(
		val value: Value,
	) : Value {
		override val type: Type get() = Type.Variant
	}
}

/** A message's body: values, and the signature that says what they are, which cannot disagree with them. */
data class Body(
	val values: List<Value>,
) {
	val signature: Signature = Signature(values.map { it.type })

	companion object {
		val EMPTY = Body(emptyList())
	}
}

/** What is wrong with bytes that were to be a D-Bus value or message: the reader stops at the first thing. */
internal class Malformed(
	why: String,
) : Exception(why)
