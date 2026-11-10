package prism.ebpf

/*
 * A program as the C that clang compiles for the BPF target. A pure function of the program's own data, so what is
 * emitted can be read in a test without a kernel, and the same source is what the build compiles.
 *
 * The C includes nothing. Every type it needs it declares: the kernel's own structures as the subsets the program
 * reads (with `preserve_access_index`, so each access carries a CO-RE relocation and the kernel's real offsets are
 * used at load time), the records as the layout prism worked out, and the helpers as pointers at the numbers the
 * kernel knows them by. So the build needs no kernel headers and no vmlinux.h, only a clang with the BPF target.
 *
 * Each record's layout is asserted where it is declared, so the C compiler itself confirms the offsets and the size
 * that prism computed and the host decodes by: if the two ever disagreed, the compile would stop at the assertion
 * rather than the host reading the wrong bytes.
 */

/** The C of this program, or null where the program has errors, in which case nothing may be emitted from it. */
fun Program.emitted(): String? = if (isLoadable) emit(this) else null

/** The C of this program, or a [NotLoadable] naming everything wrong with it. */
fun Program.emittedOrThrow(): String = emitted() ?: throw NotLoadable(this)

class NotLoadable(
	program: Program,
) : Exception("${program.name} cannot be emitted:\n${program.why()}")

/** The file the emitter writes a program to, as the loader looks for it. */
val Program.fileName: String get() = "$name.bpf.c"

private fun emit(program: Program): String {
	val lines = ArrayList<String>()

	lines += "/* ${program.name}: generated from prism's Kotlin eBPF DSL. Do not edit. */"
	lines += PRELUDE
	lines += ""
	lines += "char LICENSE[] SEC(\"license\") = \"${program.license.text}\";"

	val kinds =
		program.maps
			.map { it.kind }
			.distinct()
			.sortedBy { it.id }

	if (kinds.isNotEmpty()) {
		lines += ""
		lines +=
			"/* the map types this program declares, by the numbers the kernel knows them by */"
		for (kind in kinds)
			lines += "#define ${kind.cName} ${kind.id}"
	}
	for (group in program.enums) {
		lines += ""
		lines += "/* ${group.cPrefix.lowercase()}, as prism's own enum has it */"
		for (entry in group.entries)
			lines += "#define ${cCode(entry)} ${entry.ordinal}"
	}

	val mentioned = program.mentionedStructs

	if (mentioned.isNotEmpty()) {
		lines += ""
		lines +=
			"/* the kernel's structures this program names; CO-RE relocates every access to the running kernel */"
		for (struct in mentioned)
			lines += "${struct.cDeclaration};"
	}
	for (struct in program.structs) {
		lines += ""
		lines += "${struct.cDeclaration} {"
		for (field in struct.fields)
			lines += "\t${field.declaration()};"
		lines += "} KERNEL;"
	}
	for (record in program.records) {
		lines += ""
		lines += emitRecord(record)
	}
	if (program.maps.isNotEmpty())
		lines += ""
	for (map in program.maps)
		lines += emitMap(map)
	if (program.helpers.isNotEmpty()) {
		lines += ""
		lines +=
			"/* the helpers this program calls, at the numbers the kernel's ABI gives them */"
		for (helper in program.helpers.sortedBy { it.id })
			lines += helper.declaration()
	}
	for (hook in program.hooks) {
		lines += ""
		lines += "SEC(\"${hook.section}\")"
		lines += "int ${hook.cName}(__u64 *ctx)"
		lines += "{"
		lines += emitBlock(hook.body)
		lines += "\treturn 0;"
		lines += "}"
	}

	return lines.joinToString("\n", postfix = "\n")
}

/** What every emitted program starts with: the widths, the attributes, and the macros a map definition needs. */
private val PRELUDE =
	listOf(
		"",
		"typedef unsigned char __u8;",
		"typedef unsigned short __u16;",
		"typedef unsigned int __u32;",
		"typedef unsigned long long __u64;",
		"typedef signed int __s32;",
		"typedef signed long long __s64;",
		"",
		"#define SEC(name) __attribute__((section(name), used))",
		"#define __uint(name, val) int (*name)[val]",
		"#define __type(name, val) typeof(val) *name",
		"/* every access to a structure marked this way carries a CO-RE relocation, so the kernel's own offsets are used */",
		"#define KERNEL __attribute__((preserve_access_index))",
	)

/**
 * A record, with the padding C would insert written out as fields of its own, and the layout prism computed
 * asserted against the one the C compiler computes.
 */
private fun emitRecord(record: Record<*>): List<String> {
	val layout = record.layout
	val lines = ArrayList<String>()
	var pads = 0

	lines += "struct ${record.cName} {"
	for (member in layout.members)
		lines +=
			when (member) {
				is Layout.Member.Held -> "\t${member.field.type.declare(member.field.cName)};"
				is Layout.Member.Padding -> "\tchar __pad${pads++}[${member.sizeBytes}];"
			}
	lines += "};"
	lines +=
		"_Static_assert(sizeof(struct ${record.cName}) == ${layout.sizeBytes}, " +
		"\"${record.cName}: the size prism computed\");"
	for (field in record.fields)
		lines +=
			"_Static_assert(__builtin_offsetof(struct ${record.cName}, ${field.cName}) == " +
			"${layout.offsetOf(
				field,
			)}, \"${record.cName}.${field.cName}: the offset prism computed\");"

	return lines
}

private fun emitMap(map: BpfMap): List<String> =
	buildList {
		add("struct {")
		add("\t__uint(type, ${map.kind.cName});")
		add("\t__uint(max_entries, ${map.maxEntries});")
		map.keys?.let { add("\t__type(key, ${it.cName});") }
		map.values?.let { add("\t__type(value, ${it.cName});") }
		add("} ${map.cName} SEC(\".maps\");")
	}

/** [statements] as C, one tab in: a block within a block is emitted the same way, so the nesting composes. */
private fun emitBlock(statements: List<Stmt>): List<String> =
	statements.flatMap { statement -> emitStatement(statement).map { "\t$it" } }

private fun emitStatement(statement: Stmt): List<String> =
	when (statement) {
		is Stmt.Declare ->
			listOf(
				"${statement.local.type.declare(statement.local.cName)} = ${value(statement.value)};",
			)
		is Stmt.DeclareZeroed ->
			listOf("${statement.local.type.declare(statement.local.cName)} = {};")
		is Stmt.Assign -> listOf("${value(statement.place)} = ${value(statement.value)};")
		is Stmt.ReadText ->
			listOf(
				"${Helpers.readKernelText.cName}(${value(statement.place)}, " +
					"sizeof(${value(statement.place)}), ${value(statement.from)});",
			)
		is Stmt.Add -> listOf(add(statement))
		is Stmt.Stop ->
			statement.condition?.let { listOf("if (${condition(it)})", "\treturn 0;") }
				?: listOf("return 0;")
		is Stmt.Branch ->
			emitBranch(
				condition(statement.condition),
				statement.then,
				statement.otherwise,
			)
		is Stmt.Call -> listOf("${value(statement.call)};")
		is Stmt.WhilePresent ->
			listOf(
				"${statement.pointer.type.declare(
					statement.pointer.cName,
				)} = ${value(statement.from)};",
			) +
				emitBranch(statement.pointer.cName, statement.body, statement.otherwise)
		is Stmt.Send -> emitSend(statement)
	}

/**
 * [add] as C: an atomic where the place may be reached by several CPUs at once, a plain addition where it is one
 * CPU's own. The address of what a pointer points at is the pointer, so a counter's place is written as itself.
 */
private fun add(add: Stmt.Add): String {
	val place = add.place
	val address =
		if (place is Pointed)
			value(place.pointer)
		else
			"&${value(place)}"

	return if (add.atomically)
		"__sync_fetch_and_add($address, ${value(add.value)});"
	else
		"${value(place)} += ${value(add.value)};"
}

private fun emitBranch(
	test: String,
	then: List<Stmt>,
	otherwise: List<Stmt>,
): List<String> {
	val lines = ArrayList<String>()

	lines += "if ($test) {"
	lines += emitBlock(then)
	if (otherwise.isEmpty()) {
		lines += "}"
	} else {
		lines += "} else {"
		lines += emitBlock(otherwise)
		lines += "}"
	}

	return lines
}

private fun emitSend(send: Stmt.Send): List<String> {
	val record = send.ring.record
	val reserved = send.record.cName
	val lines = ArrayList<String>()

	lines += "${send.record.type.declare(reserved)} = " +
		"${Helpers.ringReserve.cName}(&${send.ring.cName}, sizeof(struct ${record.cName}), 0);"
	lines += "if (!$reserved) {"
	lines += emitBlock(send.onFull)
	lines += "} else {"
	lines += emitBlock(send.fill)
	lines += "\t${Helpers.ringSubmit.cName}($reserved, 0);"
	lines += "}"

	return lines
}

/** An expression as C, parenthesised where it is not a name, so nothing depends on C's precedence. */
private fun value(expr: Expr<*>): String =
	when (expr) {
		is Const -> expr.label ?: "${expr.value}${expr.type.literalSuffix}"
		is Named -> expr.cName
		is ContextWord -> "(${expr.type.cast})(ctx[${expr.index}])"
		is KernelRead -> read(expr).text
		is FieldOf ->
			if (expr.throughPointer)
				"${value(expr.record)}->${expr.field.cName}"
			else
				"${value(expr.record)}.${expr.field.cName}"
		is Pointed -> "(*${value(expr.pointer)})"
		is Called -> "${expr.helper.cName}(${expr.arguments.joinToString(
			", ",
			transform = ::argument,
		)})"
		is Converted -> "(${expr.type.cast})(${value(expr.from)})"
		is Arithmetic -> "(${value(expr.left)} ${expr.operator.c} ${value(expr.right)})"
	}

/**
 * A read of a kernel field, with whether what it names is a pointer: a structure held inside another is reached
 * with a dot and a pointer to one with an arrow, so a chain through an embedded structure reads as C would write
 * it (`dentry->d_name.name`), and CO-RE relocates each step of it.
 */
private data class Read(
	val text: String,
	val isPointer: Boolean,
)

private fun read(expr: Expr<*>): Read =
	when (expr) {
		is KernelRead -> {
			val owner = read(expr.owner)
			val reach = if (owner.isPointer) "->" else "."

			Read(
				"${owner.text}$reach${expr.field.cName}",
				!expr.field.address && expr.field.type is Ptr<*>,
			)
		}
		else ->
			Read(
				value(expr),
				expr.type is Ptr<*> || expr.type is RecordPtr<*> || expr.type is ValuePtr<*>,
			)
	}

private fun argument(argument: Argument): String =
	when (argument) {
		is Argument.Value -> value(argument.expr)
		is Argument.Map -> "&${argument.map.cName}"
		is Argument.AddressOf -> "&${value(argument.expr)}"
		is Argument.Size -> argument.bytes.toString()
	}

/**
 * A condition as C, without parentheses around the whole of it: whatever uses one puts it where it belongs (`if
 * (...)`), and a condition within a condition parenthesises its own parts, so the C has the parentheses it needs and
 * no more (clang's own `-Wparentheses-equality` objects to the rest).
 */
private fun condition(condition: Condition): String =
	when (condition) {
		is Condition.Compared ->
			"${value(condition.left)} ${condition.comparison.c} ${value(condition.right)}"
		is Condition.Both -> "(${condition(condition.left)}) && (${condition(condition.right)})"
		is Condition.Either -> "(${condition(
			condition.left,
		)}) || (${condition(condition.right)})"
		// A pointer that is not null reads as itself, rather than as the negation of a comparison with zero.
		is Condition.Not ->
			(condition.condition as? Condition.IsNull)?.let { "${value(it.pointer)} != 0" }
				?: "!(${condition(condition.condition)})"
		is Condition.IsNull -> "${value(condition.pointer)} == 0"
		// The kernel packs its errors into the top page of the address space; this is its own IS_ERR_OR_NULL.
		is Condition.IsErrorOrNull ->
			"(unsigned long)(${value(condition.pointer)}) >= (unsigned long)-4095"
		is Condition.BitsSet -> {
			val mask = "0x${condition.mask.toString(16)}"

			"(${value(condition.value)} & $mask) == $mask"
		}
	}
