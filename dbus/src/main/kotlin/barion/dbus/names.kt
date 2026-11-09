package barion.dbus

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * The names D-Bus addresses things by, each a type of its own and each checked as it is made, so a member's name
 * cannot be passed where an interface's is wanted and a name the bus would refuse is refused here, where it was
 * written. The rules are the specification's ("Valid names"). A name declared in code that breaks them is a mistake
 * in the code, refused at once; one that arrives in a message is checked with `parse`, and a message that holds one
 * is malformed.
 */

private val ELEMENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val BUS_ELEMENT = Regex("[A-Za-z_-][A-Za-z0-9_-]*")
private val UNIQUE_ELEMENT = Regex("[A-Za-z0-9_-]+")
private val PATH_ELEMENT = Regex("[A-Za-z0-9_]+")
private const val MAX_NAME = 255

/** Two or more elements of [element], separated by dots, at most 255 bytes: an interface's or an error's name. */
private fun dotted(name: String, element: Regex): Boolean =
	name.length <= MAX_NAME &&
		name.split('.').let { parts -> parts.size >= 2 && parts.all(element::matches) }

/** A connection's name on the bus: a unique one the bus gave (`:1.42`), or a well-known one (`org.freedesktop.portal.Desktop`). */
@JvmInline
value class BusName(
	val value: String,
) {
	init {
		require(valid(value)) { "'$value' is not a bus name" }
	}

	/** Whether the bus gave this name to one connection, rather than a service owning it. */
	val isUnique: Boolean get() = value.startsWith(':')

	override fun toString(): String = value

	companion object {
		/** The bus itself, which answers the methods of `org.freedesktop.DBus`. */
		val BUS = BusName("org.freedesktop.DBus")

		fun parse(value: String): BusName? = if (valid(value)) BusName(value) else null

		private fun valid(value: String): Boolean =
			if (value.startsWith(':'))
				dotted(value.drop(1), UNIQUE_ELEMENT)
			else
				dotted(value, BUS_ELEMENT)
	}
}

/** An object's path on its connection: `/` or `/org/freedesktop/portal/desktop`. A D-Bus type of its own, `o`. */
@Serializable(with = ObjectPathSerializer::class)
@JvmInline
value class ObjectPath(
	val value: String,
) {
	init {
		require(valid(value)) { "'$value' is not an object path" }
	}

	override fun toString(): String = value

	companion object {
		val ROOT = ObjectPath("/")

		fun parse(value: String): ObjectPath? = if (valid(value)) ObjectPath(value) else null

		private fun valid(value: String): Boolean =
			value == "/" ||
				(value.startsWith('/') && value.drop(1).split('/').all(PATH_ELEMENT::matches))
	}
}

/** An interface's name: `org.freedesktop.portal.Settings`. */
@JvmInline
value class InterfaceName(
	val value: String,
) {
	init {
		require(dotted(value, ELEMENT)) { "'$value' is not an interface name" }
	}

	override fun toString(): String = value

	companion object {
		fun parse(value: String): InterfaceName? =
			if (dotted(value, ELEMENT))
				InterfaceName(value)
			else
				null
	}
}

/** A method's or a signal's name: `ReadOne`, `SettingChanged`. */
@JvmInline
value class MemberName(
	val value: String,
) {
	init {
		require(valid(value)) { "'$value' is not a member name" }
	}

	override fun toString(): String = value

	companion object {
		fun parse(value: String): MemberName? = if (valid(value)) MemberName(value) else null

		private fun valid(value: String): Boolean =
			value.length <= MAX_NAME && ELEMENT.matches(value)
	}
}

/** An error's name, as a method's error reply carries it: `org.freedesktop.DBus.Error.UnknownMethod`. */
@JvmInline
value class ErrorName(
	val value: String,
) {
	init {
		require(dotted(value, ELEMENT)) { "'$value' is not an error name" }
	}

	override fun toString(): String = value

	companion object {
		val UNKNOWN_METHOD = ErrorName("org.freedesktop.DBus.Error.UnknownMethod")
		val SERVICE_UNKNOWN = ErrorName("org.freedesktop.DBus.Error.ServiceUnknown")
		val INVALID_ARGS = ErrorName("org.freedesktop.DBus.Error.InvalidArgs")
		val FAILED = ErrorName("org.freedesktop.DBus.Error.Failed")

		fun parse(value: String): ErrorName? =
			if (dotted(value, ELEMENT))
				ErrorName(value)
			else
				null
	}
}

/**
 * How kotlinx.serialization reads and writes an [ObjectPath]: as a string, under a name the D-Bus format knows, so
 * its D-Bus type is `o` and not `s`.
 */
object ObjectPathSerializer : KSerializer<ObjectPath> {
	override val descriptor: SerialDescriptor =
		PrimitiveSerialDescriptor(SpecialNames.OBJECT_PATH, PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: ObjectPath) =
		encoder.encodeString(value.value)

	override fun deserialize(decoder: Decoder): ObjectPath =
		ObjectPath(decoder.decodeString())
}

/** The serial names the D-Bus format gives a type of its own to, beyond what a descriptor's kind says. */
internal object SpecialNames {
	const val OBJECT_PATH = "barion.dbus.ObjectPath"
	const val SIGNATURE = "barion.dbus.Signature"
	const val VARIANT = "barion.dbus.Variant"
	const val ANY_VARIANT = "barion.dbus.AnyVariant"
}
