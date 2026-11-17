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
		if (keptByMacOS(refused))
			DiskAccess.Refused
		else
			DiskAccess.Unknown
	} catch (failed: IOException) {
		DiskAccess.Unknown
	}

/** The page of System Settings where Full Disk Access is given, as `open` takes it. */
const val FULL_DISK_ACCESS_SETTINGS =
	"x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles"

/**
 * The folders macOS asks the user about, one by one, before a program without Full Disk Access may read them: the
 * user's own (Desktop, Documents, Downloads, Pictures, Movies, Music, iCloud Drive and the cloud's), other
 * applications' data (Mail's, Messages', Safari's, every sandboxed application's container), and the volumes under
 * /Volumes (a drive plugged in, a share). An index read without Full Disk Access leaves these out, so that reading it
 * asks the user nothing; they are read once it is given.
 */
fun protectedFolders(home: Path = Path.of(System.getProperty("user.home"))): List<Path> =
	listOf(
		"Desktop",
		"Documents",
		"Downloads",
		"Pictures",
		"Movies",
		"Music",
	).map(home::resolve) +
		listOf(
			"Mobile Documents",
			"CloudStorage",
			"Mail",
			"Messages",
			"Safari",
			"Containers",
			"Group Containers",
			"Calendars",
			"Application Support/AddressBook",
			"Application Support/com.apple.TCC",
		).map { home.resolve("Library").resolve(it) } +
		// In a list: a path is itself a sequence of its names, and added bare, its names are what is added.
		listOf(Path.of("/Volumes"))

/** What is said where prism has no Full Disk Access: what it leaves out, and how to give it. */
const val FULL_DISK_ACCESS_REFUSED =
	"prism has no Full Disk Access, so the index leaves out the folders macOS would ask " +
		"you about one by one (Desktop, Documents, Downloads, Pictures, Movies, Music, " +
		"iCloud Drive, other applications' data), and reads them as soon as it is given. " +
		"To give it: System Settings › Privacy & Security › Full Disk Access, and turn " +
		"prism on (run from a terminal, the terminal; run by Gradle, the program that " +
		"started Gradle's daemon). `open \"$FULL_DISK_ACCESS_SETTINGS\"` opens the page."

/** What a folder macOS keeps from prism is said to be, in place of "Operation not permitted". */
const val FOLDER_REFUSED =
	"macOS keeps this folder from prism. To let prism read it, and every other folder " +
		"without asking, give it Full Disk Access: System Settings › Privacy & Security › " +
		"Full Disk Access."

/** Whether [failure] is macOS keeping a file from this program (EPERM, "Operation not permitted"). */
fun keptByMacOS(failure: Throwable): Boolean =
	failure is FileSystemException && failure.reason?.contains("not permitted") == true
