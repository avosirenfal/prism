package prism.watcher

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalNotFoundException
import java.security.MessageDigest
import kotlin.io.path.Path
import prism.ebpf.Hook
import prism.ebpf.emittedOrThrow
import prism.ebpfloader.Attachment
import prism.ebpfloader.Loading
import prism.ebpfloader.PinnedObject
import prism.ebpfloader.load
import prism.linux.Mounts
import prism.linux.Sys
import prism.linux.mount
import prism.linux.unmount
import prism.probe.FsWatch

/*
 * The watcher left in the kernel by one process for another to read. Loading it takes privileges (`CAP_BPF` and
 * `CAP_PERFMON`) that the daemon, a user's process that starts the user's applications, should not have: whatever it
 * started would have them too. So the two are done apart. A loader, run as root, loads the watcher, attaches it, and
 * gives every hook and both maps a name in a BPF file system (a "pin"), then ends: the kernel keeps what is named for
 * as long as the names are there, with no process behind it. The daemon opens the ring by its name, which takes no
 * privilege but leave to open the file, and reads it as it would one it loaded itself.
 *
 * The names are in a BPF file system of the loader's own, mounted at [WatcherPins.mount] (the system's, at
 * /sys/fs/bpf, is root's alone to look in), in a folder named for the watcher's own text, so that a daemon of another
 * version, whose records may be laid out otherwise, finds nothing rather than something it would misread. Who may
 * read is said by the folder's and the ring's owner and group: the ring tells of every name made on the machine, by
 * anyone, so it is for a user or a group that is trusted with that.
 *
 * A ring has one reader: its place in it is the ring's own. Two daemons reading one would each see part.
 */

/** Where the watcher is pinned. */
data class WatcherPins(
	/** Where the loader's BPF file system is mounted. */
	val mount: Path,
) {
	/** The folder this build's watcher is pinned in: named for what the watcher is, so that another's is not read. */
	val folder: Path get() = mount.resolve(FINGERPRINT)

	companion object {
		/** `/run/prism-watcher`, or where `PRISM_WATCHER` says: gone at shutdown, as what is pinned there is. */
		fun default(environment: Map<String, String> = System.getenv()): WatcherPins =
			WatcherPins(
				environment["PRISM_WATCHER"]?.takeIf { it.startsWith("/") }?.let(::Path)
					?: Path("/run/prism-watcher"),
			)

		/** The watcher's emitted text, as sixteen digits: two builds of one watcher have one, whatever compiled them. */
		val FINGERPRINT: String by lazy {
			MessageDigest
				.getInstance("SHA-256")
				.digest(FsWatch.program.emittedOrThrow().encodeToByteArray())
				.take(8)
				.joinToString("") { "%02x".format(it) }
		}
	}
}

/** Who may read the pinned watcher, beside root: a user, a group, either or neither, by name. */
data class Readers(
	val user: String? = null,
	val group: String? = null,
)

/** What pinning the watcher came to. */
sealed interface Pinning {
	/** Pinned in [folder]: each hook, attached or not and why. */
	data class Pinned(
		val folder: Path,
		val hooks: Map<Hook, Attachment>,
	) : Pinning

	data class Failed(
		val why: String,
	) : Pinning
}

/**
 * The watcher loaded, attached and pinned at [pins], readable by [readers], in place of whatever was pinned there
 * (an older build's, or this one's from before). It needs root: to load it, and to mount the file system it is
 * pinned in.
 */
fun pinWatcher(pins: WatcherPins, readers: Readers): Pinning =
	// All of it, or none: a watcher that could not be pinned leaves no file system mounted for it.
	pinned(pins, readers).also {
		if (it is Pinning.Failed)
			unpinWatcher(pins)
	}

private fun pinned(pins: WatcherPins, readers: Readers): Pinning {
	val bytes =
		watcherObject() ?: return Pinning.Failed("this build has no compiled watcher")

	// Who is to read is known before anything is loaded: a name nobody has is a mistake to say, not to pin past.
	val lookup = pins.mount.fileSystem.userPrincipalLookupService

	val owner =
		try {
			readers.user?.let(lookup::lookupPrincipalByName)
		} catch (unknown: UserPrincipalNotFoundException) {
			return Pinning.Failed("there is no user ${readers.user}")
		}

	val group =
		try {
			readers.group?.let(lookup::lookupPrincipalByGroupName)
		} catch (unknown: UserPrincipalNotFoundException) {
			return Pinning.Failed("there is no group ${readers.group}")
		}

	try {
		Files.createDirectories(pins.mount)
	} catch (failed: IOException) {
		return Pinning.Failed("${pins.mount} cannot be made: ${failed.message ?: failed}")
	}

	if (!isMounted(pins))
		// Anyone may look in it; what is in it says for itself who may read it.
		when (val mounted = mount("prism-watcher", pins.mount, "bpf", "mode=0755")) {
			is Sys.Failed -> return Pinning.Failed(
				"a BPF file system cannot be mounted at ${pins.mount}: $mounted",
			)
			is Sys.Ok -> Unit
		}

	return try {
		unpinAll(pins)
		Files.createDirectory(pins.folder)

		when (val loading = load(FsWatch.program, bytes)) {
			is Loading.Refused -> Pinning.Failed(loading.why.message)
			is Loading.Loaded ->
				loading.loaded.use { loaded ->
					when (val pinned = loaded.pinned(pins.folder)) {
						is Sys.Failed ->
							Pinning.Failed(
								"the watcher cannot be pinned in ${pins.folder}: $pinned",
							)
						is Sys.Ok -> {
							givenTo(pins.folder, FsWatch.program.maps.map { it.cName }, owner, group)

							Pinning.Pinned(pins.folder, loaded.attachments)
						}
					}
				}
		}
	} catch (failed: IOException) {
		// Which step it was is in what failed: its kind, and the file it names.
		Pinning.Failed("the watcher could not be left in ${pins.folder}: $failed")
	}
}

/**
 * [folder] and the files [names] in it given to [owner] and to [group], where each is said: the folder to look in,
 * and the files to read and write (a ring is read by writing where its reader has read to). Whatever else is in the
 * folder (the hooks) is left as it was made, root's alone.
 */
internal fun givenTo(
	folder: Path,
	names: List<String>,
	owner: UserPrincipal?,
	group: GroupPrincipal?,
) {
	// The folder as one more path, not as its names: a path is a list of its own names, and `+` would add those.
	for (path in names.map(folder::resolve).plusElement(folder)) {
		val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)

		owner?.let(view::setOwner)
		group?.let(view::setGroup)
		view.setPermissions(
			PosixFilePermissions.fromString(
				if (path == folder)
					"rwxr-x---"
				else
					"rw-rw----",
			),
		)
	}
}

/**
 * Whatever is pinned at [pins] taken away, and its file system with it: the hooks detached, and the maps freed once
 * no daemon has them open. What was taken away, or why it could not be.
 */
fun unpinWatcher(pins: WatcherPins): Sys<Unit> {
	if (!isMounted(pins))
		return Sys.Ok(Unit)

	return try {
		unpinAll(pins)
		unmount(pins.mount)
	} catch (failed: IOException) {
		Sys.Failed(
			"unpinning ${pins.mount}: ${failed.message ?: failed}",
			prism.linux.Errno.EIO,
		)
	}
}

/** Whether the loader's BPF file system is mounted at [pins]. */
private fun isMounted(pins: WatcherPins): Boolean =
	Mounts.current().any { it.at == pins.mount && it.type == "bpf" }

/** Every watcher pinned at [pins], of whichever build, taken away. */
private fun unpinAll(pins: WatcherPins) {
	// Each build's is a folder of its own; what else is at the top is the file system's own, and stays.
	val folders =
		Files.list(pins.mount).use { names ->
			names.filter { Files.isDirectory(it) }.toList()
		}

	for (folder in folders) {
		Files.list(folder).use { names -> names.forEach(Files::delete) }
		Files.delete(folder)
	}
}

/**
 * The watcher as it is pinned at [pins], opened to be read; or why it cannot be: none is pinned there, or one of
 * another build, or this user may not read it.
 */
fun openPinnedWatcher(pins: WatcherPins): Sys<Watch> =
	when (val opened = PinnedObject.open(FsWatch.program, pins.folder)) {
		is Sys.Failed -> opened
		is Sys.Ok ->
			when (val reader = opened.value.reader(FsWatch.maps.changes)) {
				is Sys.Ok ->
					Sys.Ok(
						Watch(
							opened.value,
							opened.value.attached.associateWith { Attachment.Attached },
							reader.value,
						),
					)
				is Sys.Failed -> reader.also { opened.value.close() }
			}
	}
