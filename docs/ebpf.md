# The eBPF DSL

prism's kernel-side programs are written in Kotlin, in the DSL in the `ebpf` module, and compiled to C for
clang's BPF target. The point of writing them in Kotlin rather than in C is not brevity: it is that **a program
the verifier would refuse is, wherever a type can say so, a program that does not compile**, and that the
declarations a program is written against are checked against the running kernel before anything is loaded.

Nothing in this module loads or runs anything. A program is data; `emitted()` is a pure function from that data
to C; `kernelMismatches()` is a pure function from that data and the kernel's own BTF to a list of what differs.
All three are tested without a kernel, and the conformance check is tested against the kernel the tests run on.

## What the types refuse

These are not checks that fail at run time. They are programs that cannot be written.

| what | how |
|---|---|
| arithmetic between widths | `Expr<T>` is **invariant** in its type, so `Expr<U32> + Expr<U64>` has no `plus` to resolve. A conversion is said: `convertedTo(U64)`. |
| reading a kernel field that is not declared | a field is reached only through `Expr<Ptr<S>>[field]`, and `KernelField<S, T>` carries the structure that declared it, so `inode[Dentry.dInode]` has no overload. |
| using a map's value without the null check | `find(table, key) { value -> … }` hands the value to the block that runs where the lookup found one. There is no `lookup` that gives a pointer back, so the verifier's rule about that null is the only shape the lookup has. |
| leaving a ring buffer's reservation unsubmitted | `send(ring, onFull = { … }) { … }` reserves, fills and submits in one word. The reservation never escapes the block, so it cannot be dropped, submitted twice, or read after. |
| losing an event silently | `send` requires `onFull`: a program must say what happens when the host has not kept up, because an event lost without anyone knowing is an index that is quietly wrong. |
| a name longer than the field it is read into | `place[field] = kernelText(pointer)` takes the length from the field's own declaration (`sizeof` in the emitted C), so the one number that could overflow it is not written by hand. |
| reading a parameter of another function | `Parameter<F, T>.value()` is a member of the body of a hook **of that function**, and `Parameter` carries `F`. |
| reading the return value in an entry hook | `Returned<F, T>.value()` is a member of `ExitBody` alone. |
| reading the return value of a `void` function | such a function declares `override val returns = NoReturn`, which is not a `Returned`, so there is nothing to call `value()` on. |
| branching on a number | a comparison gives a `Condition`, which is not an `Expr`, so it cannot be added to a number or put in a record; and a number cannot be branched on without saying what it is compared against. |
| putting a kernel pointer in a record | a record holds `Plain` types, and no pointer type is `Plain`: a kernel address would mean nothing to the host. |

## What is reported instead

The rest is recorded as data and reported by `program()`, all of it at once, never thrown
(`ProgramError`, mirroring the frame graph's `GraphError`). `emitted()` gives null for a program that has any,
and the loader refuses it.

- a record sent, or a key looked up, with a field nothing wrote;
- a GPL-only helper in a program that is not GPL;
- more records on a hook's stack than the verifier's 512 bytes;
- a ring too small to hold two of its records;
- one place hooked twice, two maps of one name, a program that hooks nothing.

## What is checked against the kernel

`kernelMismatches(program, Btf.ofRunningKernel())` reads the kernel's own description of its types from
`/sys/kernel/btf/vmlinux` and compares every declaration against it: that each structure and field is there and
is the shape the program reads it as (width, signedness, what a pointer points at, and that it is not a
bitfield, which a plain load would read wrongly), and that each hooked function has the arity, the parameter
types and the return the hook's body was written against. A member the kernel holds in an anonymous union is
found as C would reach it, which is how `dentry.d_name` is found on this kernel.

So a field that was renamed or retyped between kernels is a named mismatch before the load, not a wrong read at
run time. The offsets themselves are never taken from the declarations: every access carries a CO-RE relocation
and the kernel's own offsets are used at load time.

## What a hook is called

A hook's C function is what the verifier's log, the loader's errors, `bpftool prog list` and the names a loader pins
it under call it. By default it is its kind and the kernel function, with the shape it is of where two are declared
(`exit_vfs_mkdir_since_6_19`). A program can name its hooks for itself instead (`HookNaming.Versioned`): the watcher's
are `prism_`, the kind and function, and the oldest version of Linux each works on, 6.19 being the oldest prism is
for: `prism_exit_vfs_unlink_6_19` for a function of one shape, `prism_exit_do_file_open_7_0` for the shape since 7.0.
So `bpftool` says whose they are, and which shape of a function each is. A shape's versions are typed (`Shape`, of two
`KernelVersion`s), not words, so its name is worked out from them. A shape kept only for the kernels a program's tests
run on, which ended before the oldest version it is for, is named for the version it began in
(`prism_exit_vfs_mkdir_6_15`), which no newer shape is.

## The emitted C

The emitted C **includes nothing**: it declares the widths it uses, the kernel structures as the subsets the
program reads (with `preserve_access_index`), the records as the layout prism computed, and the helpers as
pointers at the numbers the kernel's ABI gives them. So the build needs no kernel headers and no `vmlinux.h`,
only a clang with the BPF target.

Each record's layout is asserted where it is declared:

```c
_Static_assert(sizeof(struct changed) == 104, "changed: the size prism computed");
_Static_assert(__builtin_offsetof(struct changed, dev) == 8, "changed.dev: the offset prism computed");
```

Three sides have to agree about a record: the program that writes it, the C that declares it, and the host that
decodes it. All three read one Kotlin declaration, and the assertions mean that if the arithmetic ever
disagreed with the C compiler's, the compile would stop there rather than the host reading the wrong bytes.

## A program, and what it compiles to

```kotlin
object SuperBlock : KernelStruct<SuperBlock>("super_block") {
    val sDev by field(U32)
}

object Inode : KernelStruct<Inode>("inode") {
    val iMode by field(U16)
    val iIno by field(U64)
    val iSb by field(Ptr(SuperBlock))
}

object VfsMkdir : KernelFunction<VfsMkdir>("vfs_mkdir") {
    val idmap by param(Ptr(MntIdmap))
    val dir by param(Ptr(Inode))
    val dentry by param(Ptr(Dentry))
    val mode by param(U16)

    override val returns = returning(Ptr(Dentry))
}

val watch = program("sample", License.Gpl) {
    val changes by ringBuffer(Changed, bytes = 1 shl 16)
    val missed by counters(Missed.entries)

    fexit(VfsMkdir) {
        val made = VfsMkdir.returns.value()

        stopIf(made.isErrorOrNull())

        val dir = VfsMkdir.dir.value()

        send(changes, onFull = { count(missed, Missed.RingFull) }) {
            it[Changed.change] = Change.DirectoryMade.code()
            it[Changed.dev] = dir[Inode.iSb][SuperBlock.sDev]
            it[Changed.parentIno] = dir[Inode.iIno]
            it[Changed.atNs] = now()
            it[Changed.name] = kernelText(made[Dentry.dName][Qstr.name])
        }
    }
}
```

Every name in the C below comes from a Kotlin declaration: the structures and their fields, the record and its
layout, the enum's entries, and the local named after the parameter it reads.

```c
SEC("fexit/vfs_mkdir")
int exit_vfs_mkdir(__u64 *ctx)
{
	struct dentry *returned = (struct dentry *)(ctx[4]);
	if ((unsigned long)(returned) >= (unsigned long)-4095)
		return 0;
	struct inode *dir = (struct inode *)(ctx[1]);
	struct changed *changed_0 = bpf_ringbuf_reserve(&changes, sizeof(struct changed), 0);
	if (!changed_0) {
		__u32 index_1 = 0u;
		__u64 *count_2 = bpf_map_lookup_elem(&missed, &index_1);
		if (count_2) {
			(*count_2) += 1ULL;
		}
	} else {
		changed_0->change = CHANGE_DIRECTORY_MADE;
		changed_0->dev = dir->i_sb->s_dev;
		changed_0->parent_ino = dir->i_ino;
		changed_0->at_ns = bpf_ktime_get_ns();
		bpf_probe_read_kernel_str(changed_0->name, sizeof(changed_0->name), returned->d_name.name);
		bpf_ringbuf_submit(changed_0, 0);
	}
	return 0;
}
```

A per-CPU counter is added to without an atomic, because it is the CPU's own and the host sums them; a shared
map's entry gets `__sync_fetch_and_add`. A structure held inside another is reached with a dot and a pointer to
one with an arrow, so a chain reads as C would write it and CO-RE relocates each step.

## Hooks

| word | section | what it reads |
|---|---|---|
| `fentry(f)` | `fentry/f` | the parameters, as the caller gave them |
| `fexit(f)` | `fexit/f` | the parameters, and what the function gave back |
| `tracepoint(t)` | `tp_btf/t` | a kernel tracepoint's arguments, typed through BTF |
| `syscall(t)` | `tp/syscalls/t` | a system call's arguments, as the caller gave them |

The kernel passes a hook its arguments as a row of words, which is why every hook's C takes `__u64 *ctx`; the
body reads the ones it names, each into a local named after the declaration. A system call tracepoint's words
start after its header and its call number, which the DSL knows, so a body reads its parameters the same way.

## Layout of the module

| file | what |
|---|---|
| `types.kt` | the type system: the widths, pointers, records-as-types, and the `by` delegation that names a declaration after its Kotlin property |
| `kernel.kt` | the kernel as a program reaches it: structures and their fields, functions and tracepoints and their parameters |
| `records.kt` | the records a program and the host share, and the C ABI layout worked out from the declaration |
| `maps.kt` | ring buffers, tables, per-CPU counters |
| `ir.kt` | what a program says, as immutable data, and the helpers it may call |
| `values.kt` | the arithmetic, the comparisons and the field reads: pure functions on expressions |
| `dsl.kt` | the vocabulary: the body of a hook, and the statements it may say |
| `program.kt` | a program as declared and as it comes out, and what it reaches of the kernel |
| `check.kt` | what is wrong with a declaration, as data |
| `btf.kt` | the kernel's own types, read from `/sys/kernel/btf/vmlinux` |
| `conformance.kt` | the declarations against the kernel |
| `emit.kt` | the C |
