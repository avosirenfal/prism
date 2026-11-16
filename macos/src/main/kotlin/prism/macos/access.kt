package prism.macos

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/*
 * What macOS lets prism read. Since Mojave, some of a user's own folders (Mail's, Safari's, Messages', the TCC database
 * that records what each application is allowed) are kept from every program the user has not given Full Disk Access,
 * in System Settings › Privacy & Security; and Desktop, Documents and Downloads are asked about one by one. An index of
 * every file needs Full Disk Access, and what is refused without it is said, with how to give it.
 *
 * The grant is the program's: the app's, once it is signed (and kept across updates by its signature), or, run from a
 * terminal, the terminal's. Whether it is given is known by reading a file only a program with it may read.
 */

/** Whether prism has Full Disk Access. */
enum class DiskAccess {
	Granted,
	Refused,

	/** The file that would tell is not there (a system of another layout): nothing is known. */
	Unknown,
}

/** Whether this process may read what only Full Disk Access lets a program read: the user's TCC database. */
fun fullDiskAccess(home: Path = Path.of(System.getProperty("user.home"))): DiskAccess =
	try {
		Files
			.newByteChannel(
				home.resolve("Library/Application Support/com.apple.TCC/TCC.db"),
			).use {
				DiskAccess.Granted
			}
	} catch (missing: NoSuchFileException) {
		DiskAccess.Unknown
	} catch (refused: AccessDeniedException) {
		DiskAccess.Refused
	} catch (refused: FileSystemException) {
		// "Operation not permitted": what the system says of a file it keeps from a program, which is EPERM, not EACCES.
		if (refused.reason?.contains("not permitted") ==
			true
		)
			DiskAccess.Refused
		else
			DiskAccess.Unknown
	} catch (failed: IOException) {
		DiskAccess.Unknown
	}

/** The page of System Settings where Full Disk Access is given, as `open` takes it. */
const val FULL_DISK_ACCESS_SETTINGS =
	"x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles"

/** What is said where prism has no Full Disk Access: what it cannot read, and how to give it. */
const val FULL_DISK_ACCESS_REFUSED =
	"macOS keeps some of your folders (Mail's, Safari's, others') from programs without " +
		"Full Disk Access, so the index leaves them out. To give it: System Settings › " +
		"Privacy & Security › Full Disk Access, and add prism (or, run from a terminal, the " +
		"terminal). `open \"$FULL_DISK_ACCESS_SETTINGS\"` opens the page."
