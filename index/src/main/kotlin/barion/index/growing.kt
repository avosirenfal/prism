package barion.index

/*
 * Arrays of primitives that grow as they are added to: what a block's columns are built in before they are handed
 * over, whole, to the block, which never changes them. Boxing millions of entries into lists would cost several
 * times the memory the index itself takes.
 */

internal class IntBuilder(
	capacity: Int = 16,
) {
	private var array = IntArray(capacity)

	var size = 0
		private set

	fun add(value: Int) {
		if (size == array.size)
			array = array.copyOf(array.size * 2)
		array[size++] = value
	}

	operator fun get(at: Int): Int = array[at]

	/** Only the first [size] kept: what a stack pops. */
	fun truncate(size: Int) {
		require(size in 0..this.size) { "a list is cut to at most its length" }
		this.size = size
	}

	fun toArray(): IntArray = array.copyOf(size)
}

internal class ByteBuilder(
	capacity: Int = 64,
) {
	private var array = ByteArray(capacity)

	var size = 0
		private set

	fun add(value: Byte) {
		room(1)
		array[size++] = value
	}

	fun add(values: ByteArray, from: Int = 0, to: Int = values.size) {
		room(to - from)
		System.arraycopy(values, from, array, size, to - from)
		size += to - from
	}

	private fun room(more: Int) {
		if (size + more > array.size)
			array = array.copyOf(maxOf(array.size * 2, size + more))
	}

	fun toArray(): ByteArray = array.copyOf(size)
}

internal class ShortBuilder(
	capacity: Int = 16,
) {
	private var array = ShortArray(capacity)

	var size = 0
		private set

	fun add(value: Short) {
		if (size == array.size)
			array = array.copyOf(array.size * 2)
		array[size++] = value
	}

	fun toArray(): ShortArray = array.copyOf(size)
}
