package barion.ebpf

/**
 * What is wrong with a program as declared: the family of mistakes the types cannot refuse, returned as data by
 * [program] rather than thrown, each naming what it is about. Nothing is emitted or loaded from a program that has
 * any of these.
 */
sealed class ProgramError(
	val message: String,
) {
	/** A record went to the host, or into a map's key, with fields nothing wrote. */
	class FieldUnset(
		record: Record<*>,
		fields: List<String>,
		why: String,
	) : ProgramError("${record.cName} leaves ${fields.joinToString()} unset: $why")

	/** A helper the kernel gives only to GPL programs, in a program that is not one. */
	class HelperNeedsGpl(
		helper: Helper,
		license: License,
	) : ProgramError("${helper.cName} is GPL-only and the program is ${license.text}")

	class MapNamedTwice(
		name: String,
	) : ProgramError("two maps are named $name")

	class HookedTwice(
		section: String,
	) : ProgramError(
			"$section is hooked twice in one program; one hook says everything it does",
		)

	class NoHooks(
		name: String,
	) : ProgramError("$name hooks nothing, so loading it would do nothing")

	/** More records on the stack than the verifier allows a program. */
	class StackTooLarge(
		section: String,
		bytes: Int,
	) : ProgramError(
			"$section holds $bytes bytes of records on a stack the verifier limits to 512",
		)

	/** A ring too small to hold two of its records, which could never keep up with anything. */
	class RingTooSmall(
		name: String,
		record: Record<*>,
		bytes: Int,
	) : ProgramError(
			"$name is $bytes bytes, too small for two ${record.cName} records of " +
				"${record.layout.sizeBytes} bytes each",
		)

	override fun toString(): String = message
}
