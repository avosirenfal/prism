package barion.dbus

import kotlin.time.Duration

/** Why a call gave no value, or a signal could not be sent. */
sealed interface DBusError {
	val message: String

	/** The other side answered with an error: its name, and the message it gave. */
	data class Remote(
		val name: ErrorName,
		val detail: String?,
	) : DBusError {
		override val message: String get() = detail?.let { "${name.value}: $it" } ?: name.value
	}

	/** The reply is not the type the method was declared to return. */
	data class Mismatched(
		val expected: Signature,
		val got: Signature,
	) : DBusError {
		override val message: String get() = "expected a reply of '$expected', got '$got'"
	}

	/** The reply has the right signature but could not be read as its type (an enum code no entry has). */
	data class Unreadable(
		val why: String,
	) : DBusError {
		override val message: String get() = "the reply could not be read: $why"
	}

	data class TimedOut(
		val after: Duration,
	) : DBusError {
		override val message: String get() = "no reply after $after"
	}

	/** The connection is closed, or closed while the call waited. */
	data class Closed(
		val why: String,
	) : DBusError {
		override val message: String get() = "the connection is closed: $why"
	}
}

/** What a call gave: its reply, or why there is none. */
sealed interface Reply<out T> {
	data class Returned<T>(
		val value: T,
	) : Reply<T>

	data class Failed(
		val error: DBusError,
	) : Reply<Nothing>
}

/** The reply's value, or null. */
fun <T> Reply<T>.orNull(): T? = (this as? Reply.Returned)?.value

/** The reply's value, or a [DBusException] saying why there is none. */
fun <T> Reply<T>.orThrow(): T =
	when (this) {
		is Reply.Returned -> value
		is Reply.Failed -> throw DBusException(error)
	}

/** A [DBusError] as an exception: what a flow ends with, since a flow says why it stopped by throwing. */
class DBusException(
	val error: DBusError,
) : Exception(error.message)

/** What a method served by this connection answers a call with: a value, or an error by name. */
sealed interface Answer<out R> {
	data class Returned<R>(
		val value: R,
	) : Answer<R>

	data class Failed(
		val name: ErrorName,
		val text: String,
	) : Answer<Nothing>
}

/** A call of a method this connection serves: who called, on which object, with what. */
data class Called<A>(
	val sender: BusName?,
	val path: ObjectPath,
	val arguments: A,
)

/** A signal received: who sent it (the bus gives the sender's unique name), from which object, and its arguments. */
data class Received<A>(
	val sender: BusName?,
	val path: ObjectPath,
	val arguments: A,
)
