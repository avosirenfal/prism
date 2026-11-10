package prism.dbus

import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * A D-Bus message, as immutable data, and its bytes. A message is one of four kinds, each holding the header fields the
 * specification requires of that kind and no others, so a signal without an interface or an error without the call
 * it answers cannot be made. On the wire: the byte order, the kind, the flags, the protocol version, the body's
 * length, the serial, the header fields as `a(yv)` (a code and a variant each), padding to 8, and the body.
 */

/** What a message asks of whoever handles it. */
data class Flags(
	/** The caller will not read a reply, so none is sent. */
	val noReplyExpected: Boolean = false,
	/** The bus is not to start a service to take the call. */
	val noAutoStart: Boolean = false,
	/** The callee may ask the user to authorise the call (polkit), and wait while they do. */
	val allowInteractiveAuthorization: Boolean = false,
) {
	internal val bits: Int
		get() =
			(if (noReplyExpected) 0x1 else 0) or (if (noAutoStart) 0x2 else 0) or
				(if (allowInteractiveAuthorization) 0x4 else 0)

	companion object {
		val NONE = Flags()

		internal fun of(bits: Int) =
			Flags(
				bits and 0x1 != 0,
				bits and 0x2 != 0,
				bits and 0x4 != 0,
			)
	}
}

/** One message: a call, the return or the error that answers one, or a signal. */
sealed interface Message {
	/** Its number on the connection that sent it, which a reply names; never 0. */
	val serial: UInt
	val flags: Flags
	val destination: BusName?

	/** Who sent it, as the bus says: set by the bus on what it delivers, and by no sender. */
	val sender: BusName?
	val body: Body

	/** A call of [member] on the object at [path]. */
	data class Call(
		override val serial: UInt,
		val path: ObjectPath,
		val interfaceName: InterfaceName?,
		val member: MemberName,
		override val destination: BusName?,
		override val body: Body = Body.EMPTY,
		override val flags: Flags = Flags.NONE,
		override val sender: BusName? = null,
	) : Message {
		init {
			require(serial != 0u) { "a message's serial is never 0" }
		}
	}

	/** What a call [replyTo] returned. */
	data class Return(
		override val serial: UInt,
		val replyTo: UInt,
		override val destination: BusName?,
		override val body: Body = Body.EMPTY,
		override val flags: Flags = Flags.NONE,
		override val sender: BusName? = null,
	) : Message {
		init {
			require(serial != 0u) { "a message's serial is never 0" }
		}
	}

	/** The error a call [replyTo] failed with: its [name], and by convention a message as the body's first string. */
	data class Error(
		override val serial: UInt,
		val replyTo: UInt,
		val name: ErrorName,
		override val destination: BusName?,
		override val body: Body = Body.EMPTY,
		override val flags: Flags = Flags.NONE,
		override val sender: BusName? = null,
	) : Message {
		init {
			require(serial != 0u) { "a message's serial is never 0" }
		}

		/** The error's message, where the body starts with one. */
		val text: String? get() = (body.values.firstOrNull() as? Value.String)?.value
	}

	/** A signal [member] of [interfaceName], from the object at [path]. */
	data class Signal(
		override val serial: UInt,
		val path: ObjectPath,
		val interfaceName: InterfaceName,
		val member: MemberName,
		override val destination: BusName? = null,
		override val body: Body = Body.EMPTY,
		override val flags: Flags = Flags.NONE,
		override val sender: BusName? = null,
	) : Message {
		init {
			require(serial != 0u) { "a message's serial is never 0" }
		}
	}
}

/** The header fields, by the codes the specification gives them, and the D-Bus type each must have. */
private enum class Field(
	val code: Int,
	val type: Type,
) {
	Path(1, Type.Basic.ObjectPath),
	Interface(2, Type.Basic.String),
	Member(3, Type.Basic.String),
	ErrorName(4, Type.Basic.String),
	ReplySerial(5, Type.Basic.UInt32),
	Destination(6, Type.Basic.String),
	Sender(7, Type.Basic.String),
	Signature(8, Type.Basic.Signature),
	UnixFds(9, Type.Basic.UInt32),
	;

	companion object {
		fun of(code: Int): Field? = entries.firstOrNull { it.code == code }
	}
}

private val HEADER_FIELDS = Type.Array(Type.Struct(listOf(Type.Basic.Byte, Type.Variant)))
private const val PROTOCOL_VERSION = 1
private const val FIXED_HEADER = 16

/** The longest message D-Bus allows. */
internal const val MAX_MESSAGE_BYTES = 128 * 1024 * 1024

private val Message.kind: Int
	get() =
		when (this) {
			is Message.Call -> 1
			is Message.Return -> 2
			is Message.Error -> 3
			is Message.Signal -> 4
		}

/** This message's bytes, little-endian. */
internal fun Message.encoded(): ByteArray {
	val body =
		WireWriter(ByteOrder.LITTLE_ENDIAN)
			.apply {
				this@encoded.body.values.forEach(::write)
			}.bytes()
	val fields = ArrayList<Pair<Field, Value>>()

	fun field(field: Field, value: Value?) {
		if (value != null)
			fields += field to value
	}

	when (this) {
		is Message.Call -> {
			field(Field.Path, Value.ObjectPath(path))
			field(Field.Interface, interfaceName?.let { Value.String(it.value) })
			field(Field.Member, Value.String(member.value))
		}
		is Message.Return -> field(Field.ReplySerial, Value.UInt32(replyTo))
		is Message.Error -> {
			field(Field.ErrorName, Value.String(name.value))
			field(Field.ReplySerial, Value.UInt32(replyTo))
		}
		is Message.Signal -> {
			field(Field.Path, Value.ObjectPath(path))
			field(Field.Interface, Value.String(interfaceName.value))
			field(Field.Member, Value.String(member.value))
		}
	}
	field(Field.Destination, destination?.let { Value.String(it.value) })
	field(Field.Sender, sender?.let { Value.String(it.value) })
	if (this.body.values.isNotEmpty())
		field(Field.Signature, Value.Signature(this.body.signature))

	val writer = WireWriter(ByteOrder.LITTLE_ENDIAN)

	writer.byte('l'.code)
	writer.byte(kind)
	writer.byte(flags.bits)
	writer.byte(PROTOCOL_VERSION)
	writer.int(body.size)
	writer.int(serial.toInt())
	writer.write(
		Value.Array(
			HEADER_FIELDS.element,
			fields.map { (field, value) ->
				Value.Struct(listOf(Value.Byte(field.code.toUByte()), Value.Variant(value)))
			},
		),
	)
	writer.pad(8)

	val header = writer.bytes()

	require(header.size + body.size <= MAX_MESSAGE_BYTES) {
		"a message of ${header.size + body.size} bytes is longer than D-Bus allows"
	}

	return header + body
}

/**
 * How many bytes the message whose first 16 bytes are [start] takes in all: the fixed header, the header fields
 * padded to 8, and the body. What a reader reads before it knows how much more to read.
 */
internal fun messageLength(start: ByteArray): Int {
	val order = orderOf(start[0])
	val buffer = ByteBuffer.wrap(start).order(order)
	val bodyLength = buffer.getInt(4).toLong() and 0xFFFF_FFFFL
	val fieldsLength = buffer.getInt(12).toLong() and 0xFFFF_FFFFL
	val headerLength = (FIXED_HEADER + fieldsLength + 7) / 8 * 8
	val total = headerLength + bodyLength

	if (fieldsLength > MAX_ARRAY_BYTES || total > MAX_MESSAGE_BYTES)
		throw Malformed("a message of $total bytes is longer than D-Bus allows")

	return total.toInt()
}

internal const val MESSAGE_START = FIXED_HEADER

private fun orderOf(byte: Byte): ByteOrder =
	when (byte.toInt().toChar()) {
		'l' -> ByteOrder.LITTLE_ENDIAN
		'B' -> ByteOrder.BIG_ENDIAN
		else -> throw Malformed("'${byte.toInt().toChar()}' is not a byte order")
	}

/** [bytes], one whole message, as a message; [Malformed] where they are not one. */
internal fun decodeMessage(bytes: ByteArray): Message {
	if (bytes.size < FIXED_HEADER)
		throw Malformed("a message of ${bytes.size} bytes is shorter than its header")

	val order = orderOf(bytes[0])
	val kind = bytes[1].toInt()
	val flags = Flags.of(bytes[2].toInt())

	if (bytes[3].toInt() != PROTOCOL_VERSION)
		throw Malformed("protocol version ${bytes[3]}, not $PROTOCOL_VERSION")

	val reader = WireReader(bytes, order, start = 4)
	val bodyLength = reader.uint()
	val serial = reader.uint().toUInt()

	val fields =
		(reader.read(HEADER_FIELDS) as Value.Array)
			.items
			.mapNotNull { entry ->
				val (code, variant) = (entry as Value.Struct).fields
				// A field this reader does not know is passed over, as the specification asks.
				val field = Field.of((code as Value.Byte).value.toInt()) ?: return@mapNotNull null
				val value = (variant as Value.Variant).value

				if (value.type != field.type)
					throw Malformed("the header field $field is a ${value.type}, not a ${field.type}")
				field to value
			}.toMap()

	reader.pad(8)

	val bodyEnds = reader.position.toLong() + (bodyLength.toLong() and 0xFFFF_FFFFL)

	if (bodyEnds != bytes.size.toLong())
		throw Malformed(
			"the body is ${bytes.size - reader.position} bytes, not $bodyLength as the header says",
		)
	if (serial == 0u)
		throw Malformed("a message's serial is never 0")

	val signature = (fields[Field.Signature] as Value.Signature?)?.value ?: Signature.EMPTY
	val bodyReader = WireReader(bytes, order, start = reader.position)
	val body = Body(signature.types.map(bodyReader::read))

	if (!bodyReader.atEnd)
		throw Malformed("the body holds more than its signature '$signature' says")

	fun text(field: Field): String? = (fields[field] as Value.String?)?.value

	fun <T> required(field: Field, value: T?): T =
		value ?: throw Malformed("a message of kind $kind has no $field field")

	fun <T> name(field: Field, parse: (String) -> T?): T? =
		text(field)?.let {
			parse(it)
				?: throw Malformed("the $field field '$it' is not a valid name")
		}

	val path = (fields[Field.Path] as Value.ObjectPath?)?.value
	val replyTo = (fields[Field.ReplySerial] as Value.UInt32?)?.value
	val destination = name(Field.Destination, BusName::parse)
	val sender = name(Field.Sender, BusName::parse)

	return when (kind) {
		1 ->
			Message.Call(
				serial,
				required(Field.Path, path),
				name(Field.Interface, InterfaceName::parse),
				required(Field.Member, name(Field.Member, MemberName::parse)),
				destination,
				body,
				flags,
				sender,
			)
		2 ->
			Message.Return(
				serial,
				required(Field.ReplySerial, replyTo),
				destination,
				body,
				flags,
				sender,
			)
		3 ->
			Message.Error(
				serial,
				required(Field.ReplySerial, replyTo),
				required(Field.ErrorName, name(Field.ErrorName, ErrorName::parse)),
				destination,
				body,
				flags,
				sender,
			)
		4 ->
			Message.Signal(
				serial,
				required(Field.Path, path),
				required(Field.Interface, name(Field.Interface, InterfaceName::parse)),
				required(Field.Member, name(Field.Member, MemberName::parse)),
				destination,
				body,
				flags,
				sender,
			)
		else -> throw Malformed("message kind $kind")
	}
}
