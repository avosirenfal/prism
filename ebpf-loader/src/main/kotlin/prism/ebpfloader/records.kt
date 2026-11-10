package prism.ebpfloader

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import prism.ebpf.Chars
import prism.ebpf.Field
import prism.ebpf.Integral
import prism.ebpf.Record
import prism.ebpf.S32
import prism.ebpf.S64
import prism.ebpf.U16
import prism.ebpf.U32
import prism.ebpf.U64
import prism.ebpf.U8

/*
 * A record as the host reads and writes it: in place, by the layout the DSL worked out from the record's own
 * declaration, which is the same layout the emitted C asserts. A field is reached by its declaration, so only a
 * field of this record can be read from it, and a number is read at its own width and signedness.
 */

/**
 * A record of [record]'s type, read where it lies. What a ring buffer hands out is a view of the ring's own memory,
 * valid only while the visitor runs; whatever is kept must be copied out of it, which is what a decoder is for.
 */
class RecordView<R : Record<R>> internal constructor(
	val record: R,
	private val bytes: MemorySegment,
) {
	/**
	 * The field's value, widened to a Long: zero-extended for an unsigned field, sign-extended for a signed one. A
	 * `__u64` beyond Long's range keeps its bits and reads as negative, as Kotlin's Long holds them.
	 */
	fun <T : Integral> number(field: Field<R, T>): Long {
		val at = record.layout.offsetOf(field).toLong()

		return when (field.type) {
			U8 -> bytes.get(JAVA_BYTE, at).toLong() and 0xFF
			U16 -> bytes.get(JAVA_SHORT, at).toLong() and 0xFFFF
			U32 -> bytes.get(JAVA_INT, at).toLong() and 0xFFFF_FFFFL
			U64, S64 -> bytes.get(JAVA_LONG, at)
			S32 -> bytes.get(JAVA_INT, at).toLong()
		}
	}

	/** The field's bytes up to its first NUL: a name as the kernel wrote it, which need not be UTF-8. */
	fun text(field: Field<R, Chars>): ByteArray {
		val at = record.layout.offsetOf(field).toLong()
		val length = field.type.length
		val end =
			(0 until length).firstOrNull { bytes.get(JAVA_BYTE, at + it) == 0.toByte() } ?: length

		return bytes.asSlice(at, end.toLong()).toArray(JAVA_BYTE)
	}

	/** The enum entry the field names, as the program wrote it with `code()`; null for a number no entry has. */
	fun <E : Enum<E>> entry(field: Field<R, U32>, entries: List<E>): E? =
		entries.getOrNull(number(field).toInt())
}

/** A record of [record]'s type being written by the host: a key to put in a table, or its value. */
class RecordWriter<R : Record<R>> internal constructor(
	val record: R,
	internal val bytes: MemorySegment,
) {
	operator fun <T : Integral> set(field: Field<R, T>, value: Long) {
		val at = record.layout.offsetOf(field).toLong()

		when (field.type.sizeBytes) {
			1 -> bytes.set(JAVA_BYTE, at, value.toByte())
			2 -> bytes.set(JAVA_SHORT, at, value.toShort())
			4 -> bytes.set(JAVA_INT, at, value.toInt())
			else -> bytes.set(JAVA_LONG, at, value)
		}
	}
}

/**
 * A record of this type filled by [fill], in memory of [arena]. The memory is zeroed first, so every byte the
 * kernel compares a key by (its padding included) is one the host chose.
 */
fun <R : Record<R>> R.written(
	arena: Arena,
	fill: (RecordWriter<R>) -> Unit,
): MemorySegment {
	val bytes = arena.allocate(layout.sizeBytes.toLong(), layout.alignment.toLong())

	fill(RecordWriter(this, bytes))

	return bytes
}

internal fun <R : Record<R>> R.view(bytes: MemorySegment): RecordView<R> =
	RecordView(this, bytes)
