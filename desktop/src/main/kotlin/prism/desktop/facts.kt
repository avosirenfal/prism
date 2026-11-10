package prism.desktop

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/*
 * A file's facts through the JVM's own views of the file system, which every platform has: the basic one everywhere,
 * the POSIX one (owner, group, permissions) on Linux and macOS. What the JVM cannot say, a platform says: when a file
 * was made is asked of [born], which on Linux is statx(2), since the JVM there gives the time it was modified instead
 * where the file system does not keep the other.
 */

/** Facts through the JVM's views, [types] saying a file's type, and [born] when it was made where that is known. */
class JvmFacts(
	private val types: FileTypes,
	private val born: (Path) -> java.time.Instant? = { null },
) : FileFacts {
	override fun of(path: Path): Facts? {
		val own =
			try {
				Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
			} catch (gone: IOException) {
				return null
			}

		val target =
			if (own.isSymbolicLink)
				try {
					Files.readSymbolicLink(path)
				} catch (failed: IOException) {
					null
				}
			else
				null

		val followed =
			try {
				Files.readAttributes(path, BasicFileAttributes::class.java)
			} catch (dangling: IOException) {
				own
			}

		val posix =
			try {
				Files.getFileAttributeView(path, PosixFileAttributeView::class.java)?.readAttributes()
			} catch (failed: IOException) {
				null
			} catch (unsupported: UnsupportedOperationException) {
				null
			}

		return Facts(
			path = path,
			type = types.of(path),
			kind =
				when {
					own.isSymbolicLink -> FileKind.Link
					own.isDirectory -> FileKind.Folder
					own.isRegularFile -> FileKind.File
					else -> FileKind.Other
				},
			size = followed.size(),
			modified = followed.lastModifiedTime().toInstant(),
			accessed = followed.lastAccessTime().toInstant(),
			created = born(path),
			owner = posix?.owner()?.name,
			group = posix?.group()?.name,
			permissions = posix?.permissions()?.let(::permissionsOf),
			target = target,
		)
	}

	override fun contents(folder: Path): Contents {
		var files = 0L
		var folders = 0L
		var bytes = 0L

		try {
			Files.walkFileTree(
				folder,
				object : SimpleFileVisitor<Path>() {
					override fun preVisitDirectory(
						dir: Path,
						attributes: BasicFileAttributes,
					): FileVisitResult {
						if (dir != folder)
							folders++

						return FileVisitResult.CONTINUE
					}

					override fun visitFile(
						file: Path,
						attributes: BasicFileAttributes,
					): FileVisitResult {
						files++
						bytes += attributes.size()

						return FileVisitResult.CONTINUE
					}

					// A folder that cannot be read is counted as far as it could be.
					override fun visitFileFailed(file: Path, failed: IOException): FileVisitResult =
						FileVisitResult.CONTINUE
				},
			)
		} catch (failed: IOException) {
			Unit
		}

		return Contents(files, folders, bytes)
	}

	override fun permit(path: Path, permissions: Permissions): String? =
		try {
			Files.setPosixFilePermissions(path, setOf(permissions))
			null
		} catch (failed: IOException) {
			failed.message ?: failed.toString()
		} catch (unsupported: UnsupportedOperationException) {
			"this file system has no permissions to change"
		} catch (denied: SecurityException) {
			denied.message ?: "not allowed"
		}

	private companion object {
		fun permissionsOf(set: Set<PosixFilePermission>): Permissions =
			Permissions(
				Permissions.Access(
					PosixFilePermission.OWNER_READ in set,
					PosixFilePermission.OWNER_WRITE in set,
					PosixFilePermission.OWNER_EXECUTE in set,
				),
				Permissions.Access(
					PosixFilePermission.GROUP_READ in set,
					PosixFilePermission.GROUP_WRITE in set,
					PosixFilePermission.GROUP_EXECUTE in set,
				),
				Permissions.Access(
					PosixFilePermission.OTHERS_READ in set,
					PosixFilePermission.OTHERS_WRITE in set,
					PosixFilePermission.OTHERS_EXECUTE in set,
				),
			)

		fun setOf(permissions: Permissions): Set<PosixFilePermission> =
			listOfNotNull(
				PosixFilePermission.OWNER_READ.takeIf { permissions.owner.read },
				PosixFilePermission.OWNER_WRITE.takeIf { permissions.owner.write },
				PosixFilePermission.OWNER_EXECUTE.takeIf { permissions.owner.run },
				PosixFilePermission.GROUP_READ.takeIf { permissions.group.read },
				PosixFilePermission.GROUP_WRITE.takeIf { permissions.group.write },
				PosixFilePermission.GROUP_EXECUTE.takeIf { permissions.group.run },
				PosixFilePermission.OTHERS_READ.takeIf { permissions.others.read },
				PosixFilePermission.OTHERS_WRITE.takeIf { permissions.others.write },
				PosixFilePermission.OTHERS_EXECUTE.takeIf { permissions.others.run },
			).toSet()
	}
}
