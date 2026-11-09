package barion.dbus

import kotlin.reflect.KProperty1
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/*
 * What a program says it will call and listen for, declared once each. A method is where it is (the service, the
 * object, the interface, the member) and what goes in and comes out, as Kotlin types; a signal is where it comes from
 * and what it carries. Their signatures are worked out as they are declared, so a type D-Bus cannot hold is refused
 * when the declaration runs, which is when the program starts, and never in the middle of a call.
 */

/** A method: who answers it, where, and the types of its arguments ([A]) and its reply ([R]). */
class Method<A, R>(
	/** The service that answers; null for a method served by this connection to whoever calls it. */
	val destination: BusName?,
	val path: ObjectPath,
	val interfaceName: InterfaceName,
	val member: MemberName,
	val arguments: KSerializer<A>,
	val reply: KSerializer<R>,
) {
	/** What a call's body is: [A]'s fields, or [A] itself where it is not a class. */
	val argumentSignature: Signature = bodySignatureOf(arguments.descriptor)
	val replySignature: Signature = bodySignatureOf(reply.descriptor)

	override fun toString(): String =
		"$interfaceName.$member($argumentSignature) -> ($replySignature)"
}

/**
 * A method, by its names as text, its argument and reply types given as type arguments: a body is a class's fields
 * in order (`data class SettingKey(val namespace: String, val key: String)` is `ss`), `Unit` for none, or any other
 * type for one value. A name D-Bus would refuse, or a type it cannot hold, is refused here.
 */
inline fun <reified A, reified R> method(
	destination: String?,
	path: String,
	interfaceName: String,
	member: String,
): Method<A, R> =
	Method(
		destination?.let(::BusName),
		ObjectPath(path),
		InterfaceName(interfaceName),
		MemberName(member),
		serializer<A>(),
		serializer<R>(),
	)

/** A signal: where it comes from (any object where [path] is null), and the type of its arguments ([A]). */
class Signal<A>(
	val path: ObjectPath?,
	val interfaceName: InterfaceName,
	val member: MemberName,
	val arguments: KSerializer<A>,
) {
	val signature: Signature = bodySignatureOf(arguments.descriptor)

	/**
	 * Only signals whose argument [property] is [value]: matched by the bus, as `argN` of the match rule, so the
	 * ones that do not match are never sent to this connection. [property] is a string field of [A]'s; which
	 * argument it is follows from its place in the class, so its position is never written by hand.
	 */
	fun where(property: KProperty1<A, String>, value: String): Matching<A> {
		val descriptor = arguments.descriptor
		val names = (0 until descriptor.elementsCount).map(descriptor::getElementName)
		val index = names.indexOf(property.name).takeIf { it >= 0 }

		require(index != null && signature.types.getOrNull(index) == Type.Basic.String) {
			"${property.name} is not a string argument of $this (a match compares only strings)"
		}
		require(index < MAX_MATCHED_ARGUMENT) {
			"the bus matches only the first $MAX_MATCHED_ARGUMENT arguments"
		}

		return Matching(index, value)
	}

	override fun toString(): String = "$interfaceName.$member($signature)"

	private companion object {
		const val MAX_MATCHED_ARGUMENT = 64
	}
}

/** A signal, by its names as text and its argument type. */
inline fun <reified A> signal(
	path: String?,
	interfaceName: String,
	member: String,
): Signal<A> =
	Signal(
		path?.let(::ObjectPath),
		InterfaceName(interfaceName),
		MemberName(member),
		serializer<A>(),
	)

/** One argument of a signal of [A] that must be one string: what [Signal.where] makes. */
class Matching<A> internal constructor(
	val index: Int,
	val value: String,
)

/**
 * The signals a subscription receives: what the bus is asked for (the match rule) and what the connection checks of
 * each signal it delivers, since a connection with several subscriptions receives what any of them asked for.
 */
internal data class Match(
	val sender: BusName?,
	val path: ObjectPath?,
	val interfaceName: InterfaceName,
	val member: MemberName,
	val arguments: Map<Int, String>,
) {
	/** The match rule, as `AddMatch` takes it. */
	val rule: String
		get() =
			buildList {
				add("type" to "signal")
				sender?.let { add("sender" to it.value) }
				path?.let { add("path" to it.value) }
				add("interface" to interfaceName.value)
				add("member" to member.value)
				arguments.toSortedMap().forEach { (index, value) -> add("arg$index" to value) }
			}.joinToString(",") { (key, value) -> "$key=${quoted(value)}" }

	/**
	 * Whether [signal] is one this asked for. A well-known sender is the bus's to check: what it delivers carries the
	 * unique name of whoever owns the well-known one, which the connection does not track.
	 */
	fun matches(signal: Message.Signal): Boolean =
		signal.interfaceName == interfaceName &&
			signal.member == member &&
			(path == null || signal.path == path) &&
			(sender == null || !sender.isUnique || signal.sender == sender) &&
			arguments.all { (index, value) -> stringArgument(signal, index) == value }

	private companion object {
		/** The [index]th argument of [signal], where it is a string. */
		fun stringArgument(signal: Message.Signal, index: Int): String? =
			(signal.body.values.getOrNull(index) as? Value.String)?.value

		/** A value in a match rule: in single quotes, a quote in it written as `'\''`, as the specification has it. */
		fun quoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"
	}
}
