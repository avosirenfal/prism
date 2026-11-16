package prism.index

import java.nio.file.Path
import prism.linux.Mounts
import prism.macos.MacMounts
import prism.macos.MountFlag
import prism.macos.contains
import prism.macos.volumeUuidOf
import prism.platform.Mount
import prism.platform.Os

/*
 * What the index asks of the platform it runs on: what is mounted, which file system each mount is of (to know it again
 * when it comes back), how a folder is read fastest, and which mounts the platform keeps from its user, which the
 * index leaves out whatever the rules say. Everything else the index does is the same on every platform.
 */

/** The file systems of the platform the index runs on. */
interface FileSystems {
	/** What is mounted now. */
	fun mounted(): List<Mount>

	/** Which file system [mount] is of; null for one nothing says would be the same one another time. */
	fun volumeOf(mount: Mount): Volume?

	/** Whether the platform keeps [mount] from its user: one never read, whatever the rules. */
	fun hidden(mount: Mount): Boolean

	/** How a folder is read here: the fastest way the platform has. */
	val backend: Backend

	/** Linux's: mountinfo, udev's and the file systems' own ids, and getdents64. */
	object Linux : FileSystems {
		override fun mounted(): List<Mount> = Mounts.current()

		override fun volumeOf(mount: Mount): Volume? = Volumes.SYSTEM.of(mount)

		override fun hidden(mount: Mount): Boolean = false

		override val backend: Backend get() = Backend.Getdents
	}

	/**
	 * macOS's: getfsstat, each volume known by its UUID, and the JVM's reader. A volume mounted under /Volumes is a
	 * drive plugged in, a disk image opened or a share, which come and go; what Finder does not show (`nobrowse`:
	 * the system's own volumes, devfs, and the data volume, whose files are reached from `/` through its firmlinks)
	 * is never read.
	 */
	object MacOS : FileSystems {
		override fun mounted(): List<Mount> = MacMounts.current()

		override fun volumeOf(mount: Mount): Volume? =
			when {
				mount.type in NETWORK ->
					Volume(
						VolumeId("net:${mount.type}:${mount.source}"),
						portable = true,
					)
				else ->
					volumeUuidOf(mount.at)?.let {
						Volume(
							VolumeId("uuid:$it"),
							portable =
								mount.at.startsWith(VOLUMES) || MountFlag.Local !in mount,
						)
					}
			}

		override fun hidden(mount: Mount): Boolean = MountFlag.NoBrowse in mount

		override val backend: Backend get() = Backend.Jvm

		private val VOLUMES = Path.of("/Volumes")

		/** File systems that are another machine's, by the type getfsstat gives. */
		private val NETWORK = setOf("smbfs", "afpfs", "nfs", "webdav", "ftp")
	}

	companion object {
		/** The file systems of the platform this runs on. */
		val SYSTEM: FileSystems =
			when (Os.current) {
				Os.MacOS -> MacOS
				Os.Linux, Os.Windows -> Linux
			}
	}
}
