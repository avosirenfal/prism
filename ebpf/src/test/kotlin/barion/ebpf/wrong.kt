package barion.ebpf

/*
 * Declarations that are deliberately not this kernel's, so the conformance check has something to name: a field that
 * does not exist, one of another width, a pointer to the wrong structure, a function that does not exist, and
 * signatures that have moved.
 */

object WrongInode : KernelStruct<WrongInode>("inode") {
	/** The kernel's is two bytes. */
	val iMode by field(U64)

	/** The kernel has no such field. */
	val iNonesuch by field(U64)
}

/** The kernel's own `dentry`, with one field declared to point at the wrong structure. */
object WrongDentry : KernelStruct<WrongDentry>("dentry") {
	/** The kernel's `dentry.d_parent` points at a dentry, not an inode. */
	val dParent by field(Ptr(WrongInode))
}

object VfsNonesuch : KernelFunction<VfsNonesuch>("vfs_nonesuch") {
	val dir by param(Ptr(WrongInode))
	val entry by param(Ptr(WrongDentry))

	override val returns = returning(S32)
}

/** `vfs_rmdir` takes three parameters, not two. */
object ShortRmdir : KernelFunction<ShortRmdir>("vfs_rmdir") {
	val idmap by param(Ptr(MntIdmap))
	val dir by param(Ptr(Inode))

	override val returns = returning(S32)
}

/** `d_instantiate` gives nothing back. */
object GivingInstantiate : KernelFunction<GivingInstantiate>("d_instantiate") {
	val entry by param(Ptr(Dentry))
	val inode by param(Ptr(Inode))

	override val returns = returning(S32)
}

object Noted : Record<Noted>("noted") {
	val value by u64()
}

fun wrongProgram(): Program =
	program("wrong") {
		val notes by ringBuffer(Noted, bytes = 4096)

		fexit(VfsNonesuch) {
			val dir = VfsNonesuch.dir.value()
			val entry = VfsNonesuch.entry.value()

			send(notes, onFull = {}) {
				it[Noted.value] =
					dir[WrongInode.iMode] + dir[WrongInode.iNonesuch] +
					entry[WrongDentry.dParent][WrongInode.iMode]
			}
		}

		fentry(ShortRmdir) {
			val dir = ShortRmdir.dir.value()

			send(notes, onFull = {}) {
				it[Noted.value] = dir[Inode.iIno]
			}
		}
	}

fun movedProgram(): Program =
	program("moved") {
		val notes by ringBuffer(Noted, bytes = 4096)

		fexit(ShortRmdir) {
			send(notes, onFull = {}) {
				it[Noted.value] = ShortRmdir.returns.value().convertedTo(U64)
			}
		}

		fexit(GivingInstantiate) {
			send(notes, onFull = {}) {
				it[Noted.value] = GivingInstantiate.returns.value().convertedTo(U64)
			}
		}

		fentry(DInstantiate) {
			val entry = DInstantiate.entry.value()

			send(notes, onFull = {}) {
				it[Noted.value] = entry[Dentry.dInode][Inode.iIno]
			}
		}
	}
