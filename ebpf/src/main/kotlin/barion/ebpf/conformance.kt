package barion.ebpf

/*
 * The declarations against the kernel. The DSL says what barion believes the kernel holds; BTF says what it really
 * holds. This compares the two and gives back what differs, as data, so the loader can refuse a program and name
 * the field or the signature that moved rather than let it read the wrong bytes or fail in the verifier with
 * nothing to go on.
 *
 * It is a check, not a layout: the offsets a program uses are the kernel's own, put there by CO-RE at load time.
 * What is checked is that every name a program reads is there, that it is the shape the program reads it as, and
 * that every function it hooks has the signature the hook's body was written against.
 */

/** One way a declaration and the kernel differ. */
sealed class Mismatch(
	val message: String,
) {
	class NoSuchStruct(
		struct: KernelStruct<*>,
	) : Mismatch("the kernel has no ${struct.cDeclaration}")

	class NoSuchField(
		field: KernelField<*, *>,
	) : Mismatch("the kernel's ${field.owner.cDeclaration} has no ${field.cName}")

	/** A field the kernel packs into bits, which a program cannot read with a plain load. */
	class FieldIsBitfield(
		field: KernelField<*, *>,
		bits: Int,
	) : Mismatch(
			"${field.owner.cName}.${field.cName} is a $bits-bit field in the kernel, not a whole value",
		)

	class FieldDiffers(
		field: KernelField<*, *>,
		how: String,
	) : Mismatch(
			"${field.owner.cName}.${field.cName} is declared ${field.type}, but the kernel's $how",
		)

	class NoSuchFunction(
		callable: Callable<*>,
	) : Mismatch("the kernel has no function ${callable.cName}")

	class NoSuchTracepoint(
		callable: Callable<*>,
	) : Mismatch("the kernel has no tracepoint ${callable.cName}")

	class ArityDiffers(
		callable: Callable<*>,
		declared: Int,
		kernel: Int,
	) : Mismatch(
			"${callable.cName} is declared with $declared parameters, the kernel's takes $kernel",
		)

	class ParameterDiffers(
		parameter: Parameter<*, *>,
		how: String,
	) : Mismatch("$parameter is declared ${parameter.type}, but the kernel's $how")

	class ReturnDiffers(
		callable: Callable<*>,
		how: String,
	) : Mismatch(
			"${callable.cName} is declared to give ${declaredReturn(
				callable,
			)}, but the kernel's $how",
		)

	override fun toString(): String = message

	private companion object {
		fun declaredReturn(callable: Callable<*>): String =
			when (val returns = (callable as? KernelFunction<*>)?.returns) {
				is Returned<*, *> -> returns.type.toString()
				else -> "nothing"
			}
	}
}

/**
 * Everything [program]'s declarations and [btf] differ on: empty where the program may be loaded against this
 * kernel. Where the kernel publishes no BTF there is nothing to check against, and the program is left to the
 * verifier.
 */
fun kernelMismatches(program: Program, btf: Btf): List<Mismatch> =
	buildList {
		for (struct in program.structs + program.mentionedStructs)
			addAll(structMismatches(struct, btf))
		for (hook in program.hooks.distinctBy { it.callable })
			addAll(callableMismatches(hook, btf))
	}.distinctBy { it.message }

/**
 * What [hook] alone and [btf] differ on: its own signature, and every structure its body reads. A hook with any is
 * one the loader leaves out, so that the rest of the program can still be loaded against a kernel that moved one
 * function.
 */
fun kernelMismatches(hook: Hook, btf: Btf): List<Mismatch> {
	val read = LinkedHashSet<KernelStruct<*>>()

	walk(hook.body) { expr ->
		if (expr is KernelRead) {
			read += expr.field.owner
			if (expr.field.address)
				read += (expr.field.type as Ptr<*>).target
		}
	}

	return (
		read.filter { it.needsDeclaration }.flatMap { structMismatches(it, btf) } +
			callableMismatches(hook, btf)
	).distinctBy { it.message }
}

private fun structMismatches(struct: KernelStruct<*>, btf: Btf): List<Mismatch> {
	val kernel = btf.struct(struct.cName) ?: return listOf(Mismatch.NoSuchStruct(struct))

	return struct.fields.mapNotNull { field ->
		val member = btf.member(kernel, field.cName)

		when {
			member == null -> Mismatch.NoSuchField(field)
			member.bitfieldBits != 0 -> Mismatch.FieldIsBitfield(field, member.bitfieldBits)
			else ->
				differs(field.type, btf.resolved(member.type), btf, embedded = field.address)
					?.let { Mismatch.FieldDiffers(field, it) }
		}
	}
}

private fun callableMismatches(hook: Hook, btf: Btf): List<Mismatch> {
	val callable = hook.callable
	val tracepoint = hook.kind == HookKind.Tracepoint
	val proto =
		if (tracepoint)
			btf.tracepoint(callable.cName) ?: return listOf(Mismatch.NoSuchTracepoint(callable))
		else
			btf.function(callable.cName) ?: return listOf(Mismatch.NoSuchFunction(callable))

	// A raw tracepoint's first parameter is the kernel's own `void *__data`; the rest are the tracepoint's.
	val kernelParameters =
		if (tracepoint)
			proto.members.drop(1)
		else
			proto.members
	val declared = callable.parameters

	if (declared.size != kernelParameters.size)
		return listOf(Mismatch.ArityDiffers(callable, declared.size, kernelParameters.size))

	val parameters =
		declared.zip(kernelParameters).mapNotNull { (parameter, kernel) ->
			differs(parameter.type, btf.resolved(kernel.type), btf, embedded = false)
				?.let { Mismatch.ParameterDiffers(parameter, it) }
		}

	return parameters + returnMismatch(callable, proto, btf)
}

private fun returnMismatch(
	callable: Callable<*>,
	proto: BtfType,
	btf: Btf,
): List<Mismatch> {
	val declared = (callable as? KernelFunction<*>)?.returns ?: return emptyList()
	val gives = proto.refers != 0

	return when {
		declared is Returned<*, *> && !gives ->
			listOf(
				Mismatch.ReturnDiffers(callable, "gives nothing back"),
			)
		declared === NoReturn && gives ->
			listOf(
				Mismatch.ReturnDiffers(
					callable,
					"gives ${btf.resolved(proto.refers)?.name ?: "something"} back",
				),
			)
		declared is Returned<*, *> ->
			differs(declared.type, btf.resolved(proto.refers), btf, embedded = false)
				?.let { listOf(Mismatch.ReturnDiffers(callable, it)) }
				.orEmpty()
		else -> emptyList()
	}
}

/**
 * How [declared] and [kernel] differ, or null where a program may read the kernel's type as the declared one.
 * [embedded] is a structure the kernel holds inside another, which the DSL declares as a pointer to it because
 * reading it gives its address.
 */
private fun differs(
	declared: BpfType,
	kernel: BtfType?,
	btf: Btf,
	embedded: Boolean,
): String? =
	when {
		kernel == null -> "type is not in BTF"
		embedded ->
			when {
				!kernel.isStructOrUnion -> "is not a structure held inside this one"
				kernel.name != (declared as Ptr<*>).target.cName ->
					"is ${kernel.name.ifEmpty { "an anonymous structure" }}"
				else -> null
			}
		declared is Integral ->
			when {
				!kernel.isInteger -> "is ${kernel.name.ifEmpty { "not a number" }}"
				kernel.sizeBytes != declared.sizeBytes -> "is ${kernel.sizeBytes} bytes wide"
				// An enum carries no signedness of its own, so only plain integers are compared on it.
				kernel.isPlainInteger && kernel.signed != declared.signed ->
					if (kernel.signed) "is signed" else "is unsigned"
				else -> null
			}
		declared is Ptr<*> ->
			when {
				!kernel.isPointer -> "is ${kernel.name.ifEmpty { "not a pointer" }}"
				// A pointer to plain bytes is read as whatever it points at: a name, a buffer.
				declared.target === Bytes -> null
				else -> pointeeDiffers(declared.target, btf.resolved(kernel.refers))
			}
		else -> "type cannot be a kernel type"
	}

private fun pointeeDiffers(declared: KernelStruct<*>, pointee: BtfType?): String? =
	when {
		pointee == null -> "points at nothing BTF names"
		// A pointer to a structure the kernel only forward-declares is still a pointer to that structure.
		pointee.name == declared.cName -> null
		!pointee.isStructOrUnion && !pointee.isForwardDeclaration ->
			"points at ${pointee.name.ifEmpty { "a plain value" }}"
		else -> "points at ${pointee.name.ifEmpty { "an anonymous structure" }}"
	}
