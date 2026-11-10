package prism.ebpf

/*
 * The records a program and the host both read: what a program sends through a ring buffer, and what a map's keys
 * and values are. A record is declared once, in Kotlin; its layout under the C ABI is worked out from the
 * declaration ([Layout]), the emitted C declares that layout with its padding spelled out and asserts the offsets
 * and the size the C compiler itself computes against it, and the host decodes it by the same layout. So the three
 * sides cannot disagree: if they would, the C compiler says so where the assertion is.
 */

/**
 * A record of [Plain] values. [Self] is the record's own type, which its fields carry, so a field of another record
 * cannot be written into this one.
 */
abstract class Record<Self : Record<Self>>(
	val cName: String,
) {
	private val declared = ArrayList<Field<Self, *>>()

	val fields: List<Field<Self, *>> get() = declared.toList()

	/** Where each field lies, and how big the record is: what the host decodes by and the emitter asserts. */
	val layout: Layout by lazy { Layout.of(this, declared) }

	protected fun u8(): Declaring<Field<Self, U8>> = field(U8)

	protected fun u16(): Declaring<Field<Self, U16>> = field(U16)

	protected fun u32(): Declaring<Field<Self, U32>> = field(U32)

	protected fun u64(): Declaring<Field<Self, U64>> = field(U64)

	protected fun s32(): Declaring<Field<Self, S32>> = field(S32)

	protected fun s64(): Declaring<Field<Self, S64>> = field(S64)

	/** [length] bytes of text the kernel wrote, NUL-terminated: a file's name. */
	protected fun chars(length: Int): Declaring<Field<Self, Chars>> = field(Chars(length))

	protected fun <T : Plain> field(type: T): Declaring<Field<Self, T>> =
		Declaring { name ->
			@Suppress("UNCHECKED_CAST")
			val field = Field(this as Self, name, type)

			declared += field

			field
		}

	override fun toString(): String = "struct $cName"
}

/** One field of [owner]. Reading or writing it needs a value of exactly its type, and a place of exactly [owner]. */
class Field<R : Record<R>, T : Plain> internal constructor(
	val owner: R,
	val cName: String,
	val type: T,
) {
	override fun toString(): String = "${owner.cName}.$cName"
}

/**
 * Where a record's fields lie and how big it is, under the C ABI: each field at the next offset its own alignment
 * allows, the record as big as its widest field's alignment allows. The layout is a list of [members] in order,
 * fields and the padding between them alike, so the emitted C declares the padding as fields of its own: nothing is
 * left to the C compiler's own choices, and every byte the host reads is a byte the program wrote.
 */
class Layout private constructor(
	val sizeBytes: Int,
	val alignment: Int,
	val members: List<Member>,
) {
	/** One part of a record: a field, or the bytes between two fields. */
	sealed interface Member {
		val offsetBytes: Int
		val sizeBytes: Int

		data class Held(
			override val offsetBytes: Int,
			val field: Field<*, *>,
		) : Member {
			// `field` alone would be the accessor's own backing field, which is this Int.
			override val sizeBytes: Int get() = this.field.type.sizeBytes
		}

		data class Padding(
			override val offsetBytes: Int,
			override val sizeBytes: Int,
		) : Member
	}

	private val offsets: Map<Field<*, *>, Int> =
		members.filterIsInstance<Member.Held>().associate { it.field to it.offsetBytes }

	/** Where [field] lies. */
	fun offsetOf(field: Field<*, *>): Int =
		offsets[field] ?: throw IllegalArgumentException("$field is not a field of this record")

	companion object {
		fun of(record: Record<*>, fields: List<Field<*, *>>): Layout {
			require(fields.isNotEmpty()) { "${record.cName} holds no fields" }

			val members = ArrayList<Member>()
			var at = 0

			for (field in fields) {
				val aligned = roundUp(at, field.type.alignment)

				if (aligned > at)
					members += Member.Padding(at, aligned - at)
				members += Member.Held(aligned, field)
				at = aligned + field.type.sizeBytes
			}

			val alignment = fields.maxOf { it.type.alignment }
			val size = roundUp(at, alignment)

			if (size > at)
				members += Member.Padding(at, size - at)

			return Layout(size, alignment, members)
		}
	}
}

internal fun roundUp(value: Int, to: Int): Int = (value + to - 1) / to * to
