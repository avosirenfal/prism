package barion.index

/**
 * A file's name as its directory holds it: bytes, which on Linux need not be UTF-8. The index keeps names as the
 * bytes they are, so a name that is not valid text is still found and still opened; [toString] is for showing one,
 * and replaces what is not UTF-8. Names compare byte by byte, unsigned, which is the order `ls -U` would not give but
 * `LC_ALL=C sort` does.
 */
class Name private constructor(
	private val bytes: ByteArray,
) : Comparable<Name> {
	val length: Int get() = bytes.size

	/** The name's bytes, copied, so a Name is never changed through what it gave out. */
	fun bytes(): ByteArray = bytes.copyOf()

	/** The name's bytes themselves, for this module, which never changes them: what packing a directory copies from. */
	internal val raw: ByteArray get() = bytes

	val isHidden: Boolean get() = bytes.isNotEmpty() && bytes[0] == '.'.code.toByte()

	override fun equals(other: Any?): Boolean =
		other is Name && bytes.contentEquals(other.bytes)

	override fun hashCode(): Int = bytes.contentHashCode()

	override fun compareTo(other: Name): Int =
		java.util.Arrays.compareUnsigned(bytes, other.bytes)

	override fun toString(): String = bytes.decodeToString()

	companion object {
		/** [bytes] as a name. A name is one path component: never empty, never `.` or `..`, and holds no `/` or NUL. */
		fun of(bytes: ByteArray): Name {
			require(bytes.isNotEmpty()) { "a name has at least one byte" }
			require(bytes.none { it == SLASH || it == NUL }) { "a name holds no '/' and no NUL" }

			val dots = bytes.size <= 2 && bytes.all { it == DOT }

			require(!dots) { "'.' and '..' are not names of entries" }

			return Name(bytes.copyOf())
		}

		fun of(text: String): Name = of(text.encodeToByteArray())

		/** Bytes the kernel gave, already known to be a name (getdents64's, a probe's): taken without a copy. */
		internal fun trusted(bytes: ByteArray): Name = Name(bytes)

		private const val SLASH = '/'.code.toByte()
		private const val NUL = 0.toByte()
		private const val DOT = '.'.code.toByte()
	}
}
