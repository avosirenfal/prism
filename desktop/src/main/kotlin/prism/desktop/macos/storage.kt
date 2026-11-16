package prism.desktop.macos

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import prism.desktop.Boot
import prism.desktop.Part
import prism.desktop.Reached
import prism.desktop.Space
import prism.desktop.Storage
import prism.desktop.StorageReader
import prism.desktop.Store
import prism.desktop.StoreKind
import prism.macos.MacMounts
import prism.macos.MountFlag
import prism.macos.contains
import prism.platform.Mount

/*
 * A Mac's storage, as the System page shows it: each volume Finder shows, and how full it is.
 *
 * The computer's own is two volumes since Catalina: the system's, sealed and read-only, at `/`, and the user's data at
 * /System/Volumes/Data, which Finder does not show; both are in one APFS container and share its room. So the
 * computer's store is shown as `/` with the data volume's room (which is the container's) and as one that is written
 * to, which it is. Drives are not told apart here (that is IOKit's, and DiskArbitration's, still to ask): each volume
 * is a store of its own.
 */

/** The volumes of a Mac, as getfsstat lists them and the JVM's file stores measure them. */
class VolumeStorage(
	private val mounted: () -> List<Mount> = MacMounts::current,
	private val nameOf: (Path) -> String? = ::displayName,
) : StorageReader {
	override fun read(): Storage {
		val mounts = mounted()
		val root = Path.of("/")
		val shown =
			mounts.filter {
				it.at == root ||
					(MountFlag.NoBrowse !in it && it.at.startsWith(VOLUMES))
			}

		return Storage(
			shown
				.map { mount ->
					val own = mount.at == root
					val measured =
						if (own && Files.isDirectory(DATA))
							DATA
						else
							mount.at

					Store(
						kind =
							when {
								MountFlag.Local !in mount -> StoreKind.Network
								own -> StoreKind.Internal
								else -> StoreKind.Removable
							},
						drives = emptyList(),
						parts =
							listOf(
								Part.Volume(
									label = nameOf(mount.at),
									type = mount.type,
									source = mount.source,
									space = spaceOf(measured),
									reached =
										listOf(
											Reached.Mounted(nameOf(mount.at) ?: mount.at.toString(), mount.at, null),
										),
									readOnly = !own && MountFlag.ReadOnly in mount,
								),
							),
						boot = Boot.Root.takeIf { own },
					)
				}.sortedBy { it.kind },
		)
	}

	/** How full the file system at [at] is; null where it will not say. */
	private fun spaceOf(at: Path): Space? =
		try {
			val store = Files.getFileStore(at)
			val total = store.totalSpace
			val free = store.usableSpace

			Space(total, total - free, free)
		} catch (failed: IOException) {
			null
		}

	private companion object {
		val VOLUMES: Path = Path.of("/Volumes")
		val DATA: Path = Path.of("/System/Volumes/Data")
	}
}
