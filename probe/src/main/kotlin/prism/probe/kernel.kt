package prism.probe

import prism.ebpf.Bytes
import prism.ebpf.KernelFunction
import prism.ebpf.KernelStruct
import prism.ebpf.Opaque
import prism.ebpf.Ptr
import prism.ebpf.S32
import prism.ebpf.U16
import prism.ebpf.U32
import prism.ebpf.U64
import prism.ebpf.U8

/*
 * The part of the kernel's VFS the watcher reads: the structures a change is described by, and the functions every
 * change of a directory's entries goes through, whichever system call or file system asked for it (io_uring's and
 * nfsd's included, which a system call tracepoint would not see). Each signature is the one this project was written
 * against (Linux 6.18); the loader checks each against the running kernel's BTF and leaves out a hook whose function
 * has moved, so an older or newer kernel costs that hook rather than the program.
 */

object SuperBlock : KernelStruct<SuperBlock>("super_block") {
	/** The device, in the kernel's own encoding (the minor in the low 20 bits). */
	val sDev by field(U32)
}

object Inode : KernelStruct<Inode>("inode") {
	val iMode by field(U16)
	val iIno by field(U64)
	val iSb by field(Ptr(SuperBlock))
}

/** A name and its hash, as a dentry holds it. */
object Qstr : KernelStruct<Qstr>("qstr") {
	val name by field(Ptr(Bytes))
}

object Dentry : KernelStruct<Dentry>("dentry") {
	val dParent by field(Ptr(Dentry))
	val dName by embedded(Qstr)
	val dInode by field(Ptr(Inode))
}

object Path : KernelStruct<Path>("path") {
	val dentry by field(Ptr(Dentry))
}

object File : KernelStruct<File>("file") {
	val fMode by field(U32)
	val fPath by embedded(Path)
}

/** What a rename is given, since 6.17 with the parents as dentries. */
object RenameData : KernelStruct<RenameData>("renamedata") {
	val oldParent by field(Ptr(Dentry))
	val oldDentry by field(Ptr(Dentry))
	val newParent by field(Ptr(Dentry))
	val newDentry by field(Ptr(Dentry))
	val flags by field(U32)
}

/** A parameter only passed along: the idmap of the mount the change was made through. */
object MntIdmap : KernelStruct<MntIdmap>("mnt_idmap")

/** `struct dentry *vfs_mkdir(struct mnt_idmap *, struct inode *dir, struct dentry *, umode_t)`, since 6.15. */
object VfsMkdir : KernelFunction<VfsMkdir>("vfs_mkdir") {
	val idmap by param(Ptr(MntIdmap))
	val dir by param(Ptr(Inode))
	val dentry by param(Ptr(Dentry))
	val mode by param(U16)

	/** The directory made: the dentry given, or another the file system made instead, or an error. */
	override val returns = returning(Ptr(Dentry))
}

/** `int vfs_rmdir(struct mnt_idmap *, struct inode *dir, struct dentry *)` */
object VfsRmdir : KernelFunction<VfsRmdir>("vfs_rmdir") {
	val idmap by param(Ptr(MntIdmap))
	val dir by param(Ptr(Inode))
	val dentry by param(Ptr(Dentry))

	override val returns = returning(S32)
}

/** `int vfs_unlink(struct mnt_idmap *, struct inode *dir, struct dentry *, struct inode **delegated_inode)` */
object VfsUnlink : KernelFunction<VfsUnlink>("vfs_unlink") {
	val idmap by param(Ptr(MntIdmap))
	val dir by param(Ptr(Inode))
	val dentry by param(Ptr(Dentry))
	val delegated by param(Ptr(Opaque))

	override val returns = returning(S32)
}

/** `int vfs_rename(struct renamedata *)` */
object VfsRename : KernelFunction<VfsRename>("vfs_rename") {
	val data by param(Ptr(RenameData))

	override val returns = returning(S32)
}

/** `int vfs_create(struct mnt_idmap *, struct inode *dir, struct dentry *, umode_t, bool want_excl)`: mknod's path. */
object VfsCreate : KernelFunction<VfsCreate>("vfs_create") {
	val idmap by param(Ptr(MntIdmap))
	val dir by param(Ptr(Inode))
	val dentry by param(Ptr(Dentry))
	val mode by param(U16)
	val exclusive by param(U8)

	override val returns = returning(S32)
}

/** `int vfs_symlink(struct mnt_idmap *, struct inode *dir, struct dentry *, const char *oldname)` */
object VfsSymlink : KernelFunction<VfsSymlink>("vfs_symlink") {
	val idmap by param(Ptr(MntIdmap))
	val dir by param(Ptr(Inode))
	val dentry by param(Ptr(Dentry))
	val target by param(Ptr(Bytes))

	override val returns = returning(S32)
}

/** `int vfs_link(struct dentry *old, struct mnt_idmap *, struct inode *dir, struct dentry *new, struct inode **)` */
object VfsLink : KernelFunction<VfsLink>("vfs_link") {
	val existing by param(Ptr(Dentry))
	val idmap by param(Ptr(MntIdmap))
	val dir by param(Ptr(Inode))
	val made by param(Ptr(Dentry))
	val delegated by param(Ptr(Opaque))

	override val returns = returning(S32)
}

/** `int vfs_mknod(struct mnt_idmap *, struct inode *dir, struct dentry *, umode_t, dev_t)`: a FIFO, a socket, a node. */
object VfsMknod : KernelFunction<VfsMknod>("vfs_mknod") {
	val idmap by param(Ptr(MntIdmap))
	val dir by param(Ptr(Inode))
	val dentry by param(Ptr(Dentry))
	val mode by param(U16)
	val device by param(U32)

	override val returns = returning(S32)
}

/**
 * `struct file *do_filp_open(int dfd, struct filename *, const struct open_flags *)`: every open, the ones that made
 * the file they open included, which is how a file made by `open(O_CREAT)` is seen, since that path does not go
 * through [VfsCreate].
 */
object DoFilpOpen : KernelFunction<DoFilpOpen>("do_filp_open") {
	val dfd by param(S32)
	val pathname by param(Ptr(Opaque))
	val flags by param(Ptr(Opaque))

	override val returns = returning(Ptr(File))

	/** `FMODE_CREATED`: this open made the file. */
	const val CREATED = 1L shl 20
}
