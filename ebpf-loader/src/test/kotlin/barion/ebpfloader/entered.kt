package barion.ebpfloader

import barion.ebpf.Counters
import barion.ebpf.KernelFunction
import barion.ebpf.KernelStruct
import barion.ebpf.KernelTracepoint
import barion.ebpf.Ptr
import barion.ebpf.Record
import barion.ebpf.RingBuffer
import barion.ebpf.S32
import barion.ebpf.S64
import barion.ebpf.Table
import barion.ebpf.U32
import barion.ebpf.U64
import barion.ebpf.convertedTo
import barion.ebpf.declared
import barion.ebpf.eq
import barion.ebpf.get
import barion.ebpf.of
import barion.ebpf.shr

/*
 * A program the loader's tests load: on entry to `close`, it sends the call's number and its first argument for the
 * processes the host has put in a table, and counts what it could not send. It exercises what the fswatch
 * probe will (a BTF tracepoint, a host-written table, a ring buffer, per-CPU counters) through a hook any kernel that
 * loads BPF at all will attach, so it runs where a function hook may not.
 */

object PtRegs : KernelStruct<PtRegs>("pt_regs") {
	/** The first argument of a system call, on x86-64. */
	val di by field(U64)
}

/** `sys_enter`: `void (*)(void *, struct pt_regs *regs, long id)`, of which a program is given the last two. */
object SysEnter : KernelTracepoint<SysEnter>("sys_enter") {
	val regs by param(Ptr(PtRegs))
	val id by param(S64)
}

/** A function this kernel has no such signature for: a hook of it is one the loader must leave out. */
object NoSuchFunction : KernelFunction<NoSuchFunction>("barion_no_such_function") {
	val value by param(U64)

	override val returns = returning(S32)
}

object Watched : Record<Watched>("watched") {
	val tgid by u32()
}

object Watching : Record<Watching>("watching") {
	val since by u64()
}

object Entered : Record<Entered>("entered") {
	val call by s64()
	val first by u64()
	val tgid by u32()
}

/** `close`, on x86-64. */
const val CLOSE = 3L

enum class Lost {
	RingFull,
}

class EnteredMaps(
	val watched: Table<Watched, Watching>,
	val entries: RingBuffer<Entered>,
	val lost: Counters<Lost>,
)

/** The program, with [alsoMissing] adding a hook of a function the kernel does not have. */
fun entered(alsoMissing: Boolean = false) =
	declared("entered") {
		val watched by table(Watched, Watching, maxEntries = 8)
		val entries by ringBuffer(Entered, bytes = 1 shl 16)
		val lost by counters(Lost.entries)

		tracepoint(SysEnter) {
			stopUnless(SysEnter.id.value() eq (S64 of CLOSE))

			val tgid by local((pidTgid() shr 32).convertedTo(U32))
			val key = filled(Watched) { it[Watched.tgid] = tgid }

			stopUnless(holds(watched, key))

			val regs = SysEnter.regs.value()

			send(entries, onFull = { count(lost, Lost.RingFull) }) {
				it[Entered.call] = SysEnter.id.value()
				it[Entered.first] = regs[PtRegs.di]
				it[Entered.tgid] = tgid
			}
		}

		if (alsoMissing)
			fexit(NoSuchFunction) {
				send(entries, onFull = { count(lost, Lost.RingFull) }) {
					it[Entered.call] = NoSuchFunction.returns.value().convertedTo(S64)
					it[Entered.first] = NoSuchFunction.value.value()
					it[Entered.tgid] = U32 of 0
				}
			}

		EnteredMaps(watched, entries, lost)
	}
