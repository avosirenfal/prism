package prism.probe

import prism.ebpf.Bytes
import prism.ebpf.KernelFunction
import prism.ebpf.KernelStruct
import prism.ebpf.Opaque
import prism.ebpf.Parameter
import prism.ebpf.Ptr
import prism.ebpf.Returned
import prism.ebpf.S32
import prism.ebpf.U16
import prism.ebpf.U32
import prism.ebpf.U64
import prism.ebpf.U8

/*
 * The part of the kernel's VFS the watcher reads: the structures a change is described by, and the functions every
 * change of a directory's entries goes through, whichever system call or file system asked for it (io_uring's and
 * nfsd's included, which a system call tracepoint would not see).
 *
 * The kernel keeps none of these functions' signatures from one version to the next, so each is declared in every
 * shape it has had since 6.17: as it was up to 6.18, and as it is since 6.19, when most were given a
 * `struct delegated_inode *` (and `vfs_create` lost its directory, which is its dentry's parent), and since 7.0,
 * when `do_filp_open` became `do_file_open`. The loader checks each against the running kernel's BTF and attaches
 * the one that fits; a kernel with a shape not declared here costs that one hook rather than the program, and
 * `prism probe check` says what its shape differs on, which is what the next declaration is written from.
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

/** The shape a function has had since 6.19, as its declaration is told from the one before. */
private const val SINCE_6_19 = "since 6.19"

/** A function that changes an entry of a directory, and is given both: what the watcher's hooks of them read. */
interface OfEntry<F : KernelFunction<F>> {
	val dir: Parameter<F, Ptr<Inode>>
	val dentry: Parameter<F, Ptr<Dentry>>
}

/** A function that says how it went by a number: 0, or an error's. */
interface GivesStatus<F : KernelFunction<F>> {
	val returns: Returned<F, S32>
}

/** `struct dentry *vfs_mkdir(struct mnt_idmap *, struct inode *dir, struct dentry *, umode_t)`, from 6.15 to 6.18. */
object VfsMkdir : KernelFunction<VfsMkdir>("vfs_mkdir"), OfEntry<VfsMkdir> {
	val idmap by param(Ptr(MntIdmap))
	override val dir by param(Ptr(Inode))
	override val dentry by param(Ptr(Dentry))
	val mode by param(U16)

	/** The directory made: the dentry given, or another the file system made instead, or an error. */
	override val returns = returning(Ptr(Dentry))
}

/** `struct dentry *vfs_mkdir(const struct mnt_idmap *, struct inode *dir, struct dentry *, umode_t, struct delegated_inode *)` */
object VfsMkdirDelegated :
	KernelFunction<VfsMkdirDelegated>(
		"vfs_mkdir",
		SINCE_6_19,
	),
	OfEntry<VfsMkdirDelegated> {
	val idmap by param(Ptr(MntIdmap))
	override val dir by param(Ptr(Inode))
	override val dentry by param(Ptr(Dentry))
	val mode by param(U16)
	val delegated by param(Ptr(Opaque))

	override val returns = returning(Ptr(Dentry))
}

/** `int vfs_rmdir(struct mnt_idmap *, struct inode *dir, struct dentry *)`, to 6.18. */
object VfsRmdir :
	KernelFunction<VfsRmdir>(
		"vfs_rmdir",
	),
	OfEntry<VfsRmdir>,
	GivesStatus<VfsRmdir> {
	val idmap by param(Ptr(MntIdmap))
	override val dir by param(Ptr(Inode))
	override val dentry by param(Ptr(Dentry))

	override val returns = returning(S32)
}

/** `int vfs_rmdir(const struct mnt_idmap *, struct inode *dir, struct dentry *, struct delegated_inode *)` */
object VfsRmdirDelegated :
	KernelFunction<VfsRmdirDelegated>("vfs_rmdir", SINCE_6_19),
	OfEntry<VfsRmdirDelegated>,
	GivesStatus<VfsRmdirDelegated> {
	val idmap by param(Ptr(MntIdmap))
	override val dir by param(Ptr(Inode))
	override val dentry by param(Ptr(Dentry))
	val delegated by param(Ptr(Opaque))

	override val returns = returning(S32)
}

/**
 * `int vfs_unlink(struct mnt_idmap *, struct inode *dir, struct dentry *, struct inode **delegated_inode)`; since
 * 6.19 its last parameter is a `struct delegated_inode *`, which is a pointer nothing here reads all the same.
 */
object VfsUnlink :
	KernelFunction<VfsUnlink>(
		"vfs_unlink",
	),
	OfEntry<VfsUnlink>,
	GivesStatus<VfsUnlink> {
	val idmap by param(Ptr(MntIdmap))
	override val dir by param(Ptr(Inode))
	override val dentry by param(Ptr(Dentry))
	val delegated by param(Ptr(Opaque))

	override val returns = returning(S32)
}

/** `int vfs_rename(struct renamedata *)` */
object VfsRename : KernelFunction<VfsRename>("vfs_rename") {
	val data by param(Ptr(RenameData))

	override val returns = returning(S32)
}

/**
 * `int vfs_create(struct mnt_idmap *, struct inode *dir, struct dentry *, umode_t, bool want_excl)`, to 6.18:
 * mknod's path.
 */
object VfsCreate :
	KernelFunction<VfsCreate>(
		"vfs_create",
	),
	OfEntry<VfsCreate>,
	GivesStatus<VfsCreate> {
	val idmap by param(Ptr(MntIdmap))
	override val dir by param(Ptr(Inode))
	override val dentry by param(Ptr(Dentry))
	val mode by param(U16)
	val exclusive by param(U8)

	override val returns = returning(S32)
}

/**
 * `int vfs_create(const struct mnt_idmap *, struct dentry *, umode_t, struct delegated_inode *)`: given no directory,
 * which is the dentry's parent.
 */
object VfsCreateDelegated : KernelFunction<VfsCreateDelegated>("vfs_create", SINCE_6_19) {
	val idmap by param(Ptr(MntIdmap))
	val dentry by param(Ptr(Dentry))
	val mode by param(U16)
	val delegated by param(Ptr(Opaque))

	override val returns = returning(S32)
}

/** `int vfs_symlink(struct mnt_idmap *, struct inode *dir, struct dentry *, const char *oldname)`, to 6.18. */
object VfsSymlink :
	KernelFunction<VfsSymlink>(
		"vfs_symlink",
	),
	OfEntry<VfsSymlink>,
	GivesStatus<VfsSymlink> {
	val idmap by param(Ptr(MntIdmap))
	override val dir by param(Ptr(Inode))
	override val dentry by param(Ptr(Dentry))
	val target by param(Ptr(Bytes))

	override val returns = returning(S32)
}

/** `int vfs_symlink(const struct mnt_idmap *, struct inode *dir, struct dentry *, const char *, struct delegated_inode *)` */
object VfsSymlinkDelegated :
	KernelFunction<VfsSymlinkDelegated>("vfs_symlink", SINCE_6_19),
	OfEntry<VfsSymlinkDelegated>,
	GivesStatus<VfsSymlinkDelegated> {
	val idmap by param(Ptr(MntIdmap))
	override val dir by param(Ptr(Inode))
	override val dentry by param(Ptr(Dentry))
	val target by param(Ptr(Bytes))
	val delegated by param(Ptr(Opaque))

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

/**
 * `int vfs_mknod(struct mnt_idmap *, struct inode *dir, struct dentry *, umode_t, dev_t)`, to 6.18: a FIFO, a socket,
 * a node.
 */
object VfsMknod :
	KernelFunction<VfsMknod>(
		"vfs_mknod",
	),
	OfEntry<VfsMknod>,
	GivesStatus<VfsMknod> {
	val idmap by param(Ptr(MntIdmap))
	override val dir by param(Ptr(Inode))
	override val dentry by param(Ptr(Dentry))
	val mode by param(U16)
	val device by param(U32)

	override val returns = returning(S32)
}

/** `int vfs_mknod(const struct mnt_idmap *, struct inode *dir, struct dentry *, umode_t, dev_t, struct delegated_inode *)` */
object VfsMknodDelegated :
	KernelFunction<VfsMknodDelegated>("vfs_mknod", SINCE_6_19),
	OfEntry<VfsMknodDelegated>,
	GivesStatus<VfsMknodDelegated> {
	val idmap by param(Ptr(MntIdmap))
	override val dir by param(Ptr(Inode))
	override val dentry by param(Ptr(Dentry))
	val mode by param(U16)
	val device by param(U32)
	val delegated by param(Ptr(Opaque))

	override val returns = returning(S32)
}

/** A function that opens a file and gives it back: every open goes through one, whichever the kernel calls it. */
interface Opens<F : KernelFunction<F>> {
	val returns: Returned<F, Ptr<File>>
}

/** `FMODE_CREATED`: this open made the file. */
const val FMODE_CREATED = 1L shl 20

/**
 * `struct file *do_filp_open(int dfd, struct filename *, const struct open_flags *)`, to 6.19: every open, the ones
 * that made the file they open included, which is how a file made by `open(O_CREAT)` is seen, since that path does
 * not go through [VfsCreate].
 */
object DoFilpOpen : KernelFunction<DoFilpOpen>("do_filp_open"), Opens<DoFilpOpen> {
	val dfd by param(S32)
	val pathname by param(Ptr(Opaque))
	val flags by param(Ptr(Opaque))

	override val returns = returning(Ptr(File))
}

/** `struct file *do_file_open(int dfd, struct filename *, const struct open_flags *)`: the same, by its name since 7.0. */
object DoFileOpen :
	KernelFunction<DoFileOpen>("do_file_open", "since 7.0", formerly = DoFilpOpen),
	Opens<DoFileOpen> {
	val dfd by param(S32)
	val pathname by param(Ptr(Opaque))
	val flags by param(Ptr(Opaque))

	override val returns = returning(Ptr(File))
}
