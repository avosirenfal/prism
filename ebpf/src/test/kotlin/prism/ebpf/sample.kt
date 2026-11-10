package prism.ebpf

/*
 * A small set of declarations and one program over them, used by the DSL's own tests: enough of the kernel to read
 * a few fields through two pointers, a record with padding in it, every kind of map, and every statement the
 * vocabulary has. The probe module declares the real thing.
 */

object SuperBlock : KernelStruct<SuperBlock>("super_block") {
	val sDev by field(U32)
}

object Inode : KernelStruct<Inode>("inode") {
	val iMode by field(U16)
	val iIno by field(U64)
	val iSb by field(Ptr(SuperBlock))
}

object Qstr : KernelStruct<Qstr>("qstr") {
	val name by field(Ptr(Bytes))
}

object Dentry : KernelStruct<Dentry>("dentry") {
	val dName by embedded(Qstr)
	val dInode by field(Ptr(Inode))
}

object MntIdmap : KernelStruct<MntIdmap>("mnt_idmap")

/** `struct dentry *vfs_mkdir(struct mnt_idmap *, struct inode *, struct dentry *, umode_t)` */
object VfsMkdir : KernelFunction<VfsMkdir>("vfs_mkdir") {
	val idmap by param(Ptr(MntIdmap))
	val dir by param(Ptr(Inode))
	val dentry by param(Ptr(Dentry))
	val mode by param(U16)

	override val returns = returning(Ptr(Dentry))
}

/** `int vfs_rmdir(struct mnt_idmap *, struct inode *, struct dentry *)` */
object VfsRmdir : KernelFunction<VfsRmdir>("vfs_rmdir") {
	val idmap by param(Ptr(MntIdmap))
	val dir by param(Ptr(Inode))
	val dentry by param(Ptr(Dentry))

	override val returns = returning(S32)
}

/** `void d_instantiate(struct dentry *, struct inode *)`: a function that gives nothing back. */
object DInstantiate : KernelFunction<DInstantiate>("d_instantiate") {
	val entry by param(Ptr(Dentry))
	val inode by param(Ptr(Inode))

	override val returns = NoReturn
}

enum class Change {
	DirectoryMade,
	DirectoryGone,
}

enum class Missed {
	RingFull,
	Unnamed,
}

/** What the host reads: the change, where it happened, and the name. The u16 leaves a gap the layout spells out. */
object Changed : Record<Changed>("changed") {
	val change by u32()
	val mode by u16()
	val dev by u32()
	val parentIno by u64()
	val ino by u64()
	val atNs by u64()
	val name by chars(64)
}

object DirKey : Record<DirKey>("dir_key") {
	val dev by u32()
	val ino by u64()
}

object Ignored : Record<Ignored>("ignored") {
	val since by u64()
	val hits by u64()
}

/** One program over all of it: both kinds of hook, every map, and every statement the vocabulary has. */
fun sampleProgram(license: License = License.Gpl): Program =
	program("sample", license) {
		val changes by ringBuffer(Changed, bytes = 1 shl 16)
		val ignoring by table(DirKey, Ignored, maxEntries = 1024, leastRecentlyUsed = true)
		val missed by counters(Missed.entries)

		fexit(VfsMkdir) {
			val made = VfsMkdir.returns.value()

			stopIf(made.isErrorOrNull())

			val dir = VfsMkdir.dir.value()
			val key =
				filled(DirKey) {
					it[DirKey.dev] = dir[Inode.iSb][SuperBlock.sDev]
					it[DirKey.ino] = dir[Inode.iIno]
				}

			stopIf(holds(ignoring, key))

			val child = made[Dentry.dInode]

			branch(child.isNull(), otherwise = {
				send(changes, onFull = { count(missed, Missed.RingFull) }) {
					it[Changed.change] = Change.DirectoryMade.code()
					it[Changed.mode] = child[Inode.iMode]
					it[Changed.dev] = key[DirKey.dev]
					it[Changed.parentIno] = key[DirKey.ino]
					it[Changed.ino] = child[Inode.iIno]
					it[Changed.atNs] = now()
					it[Changed.name] = kernelText(made[Dentry.dName][Qstr.name])
				}
			}) {
				count(missed, Missed.Unnamed)
			}
		}

		fexit(VfsRmdir) {
			stopIf(VfsRmdir.returns.value() ne (S32 of 0))

			val dir = VfsRmdir.dir.value()
			val gone = VfsRmdir.dentry.value()
			val key =
				filled(DirKey) {
					it[DirKey.dev] = dir[Inode.iSb][SuperBlock.sDev]
					it[DirKey.ino] = dir[Inode.iIno]
				}

			find(ignoring, key, missing = {
				send(changes, onFull = { count(missed, Missed.RingFull) }) {
					it[Changed.change] = Change.DirectoryGone.code()
					it[Changed.mode] = U16 of 0
					it[Changed.dev] = key[DirKey.dev]
					it[Changed.parentIno] = key[DirKey.ino]
					it[Changed.ino] = U64 of 0
					it[Changed.atNs] = now()
					it[Changed.name] = kernelText(gone[Dentry.dName][Qstr.name])
				}
			}) { seen ->
				seen.add(Ignored.hits, U64 of 1)
			}

			remove(ignoring, key)
		}

		fentry(DInstantiate) {
			val key =
				filled(DirKey) {
					it[DirKey.dev] = U32 of 0
					it[DirKey.ino] = DInstantiate.inode.value()[Inode.iIno]
				}

			val entry =
				filled(Ignored) {
					it[Ignored.since] = now()
					it[Ignored.hits] = U64 of 0
				}

			put(ignoring, key, entry)
		}
	}
