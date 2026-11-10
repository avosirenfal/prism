package prism.preferences

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/*
 * Where the preferences are kept, and how a change reaches every window. A store holds the current value as a state
 * every window collects; a change is made through the store, which makes the new value and, for a file, writes it.
 * A file is also watched: a change made by another process (another window run on its own, the daemon, or the user's
 * editor) is read back, and every window here is given it too.
 */

/** The preferences, as every window reads them, and the one way to change them. */
interface PreferenceStore {
	val preferences: StateFlow<Preferences>

	/** The preferences made anew by [change] from what they are now, and kept; every window is given them. */
	fun update(change: (Preferences) -> Preferences)
}

/** Preferences held in memory alone: a picker or an explorer run without a file, and the tests. */
class MemoryPreferences(
	initial: Preferences = Preferences.DEFAULT,
) : PreferenceStore {
	private val state = MutableStateFlow(initial)

	override val preferences: StateFlow<Preferences> = state.asStateFlow()

	override fun update(change: (Preferences) -> Preferences) {
		state.value = change(state.value)
	}
}

/** What reading a preferences file gave. */
sealed interface PreferencesRead {
	val preferences: Preferences

	data class Read(
		override val preferences: Preferences,
	) : PreferencesRead

	/** No file yet: every default. */
	data object Missing : PreferencesRead {
		override val preferences: Preferences get() = Preferences.DEFAULT
	}

	/** A file that is not preferences (written wrong by hand, say): every default meanwhile, and why. */
	data class Unreadable(
		val why: String,
	) : PreferencesRead {
		override val preferences: Preferences get() = Preferences.DEFAULT
	}
}

/**
 * Preferences kept in [file], as JSON, and watched for changes made to it from elsewhere. A file that cannot be read
 * leaves every default in place, and is kept as it was, beside it with `.unreadable` after its name, before the first
 * change written over it, so nothing written by hand is lost.
 */
class PreferenceFile private constructor(
	val file: Path,
	first: PreferencesRead,
) : PreferenceStore,
	AutoCloseable {
	private val state = MutableStateFlow(first.preferences)

	/** What the last read of the file said, where it could not be read; null where it could, or there was none. */
	@Volatile
	var problem: String? = (first as? PreferencesRead.Unreadable)?.why
		private set

	override val preferences: StateFlow<Preferences> = state.asStateFlow()

	private val watcher: WatchService? =
		try {
			Files.createDirectories(file.parent)
			file.parent.fileSystem.newWatchService().also {
				file.parent.register(it, ENTRY_CREATE, ENTRY_MODIFY)
			}
		} catch (failed: IOException) {
			null
		}

	private val thread =
		watcher?.let {
			Thread({ watch(it) }, "prism-preferences").apply {
				isDaemon = true
				start()
			}
		}

	override fun update(change: (Preferences) -> Preferences) {
		synchronized(this) {
			val next = change(state.value)

			problem?.let { keepUnreadable() }
			write(file, next)
			problem = null
			state.value = next
		}
	}

	/** The file read again, as another process changed it; what it says is given every window, where it differs. */
	private fun reread() {
		synchronized(this) {
			when (val read = read(file)) {
				is PreferencesRead.Read -> {
					problem = null
					state.value = read.preferences
				}
				is PreferencesRead.Unreadable -> problem = read.why
				// Removed: the defaults, as with no file.
				PreferencesRead.Missing -> {
					problem = null
					state.value = Preferences.DEFAULT
				}
			}
		}
	}

	private fun watch(watcher: WatchService) {
		try {
			while (true) {
				val key = watcher.take()
				val ours = key.pollEvents().any { (it.context() as? Path)?.fileName == file.fileName }

				if (ours)
					reread()
				if (!key.reset())
					return
			}
		} catch (closed: ClosedWatchServiceException) {
			Unit
		} catch (interrupted: InterruptedException) {
			Unit
		}
	}

	private fun keepUnreadable() {
		try {
			Files.copy(
				file,
				file.resolveSibling("${file.fileName}.unreadable"),
				StandardCopyOption.REPLACE_EXISTING,
			)
		} catch (gone: IOException) {
			Unit
		}
	}

	override fun close() {
		watcher?.close()
		thread?.join(1000)
	}

	companion object {
		/** How the file is written: every setting, defaults too, so the file shows what can be set; read leniently. */
		private val json =
			Json {
				prettyPrint = true
				encodeDefaults = true
				ignoreUnknownKeys = true
			}

		/** The preferences in [file], and a store that keeps them there. */
		fun open(file: Path = defaultFile()): PreferenceFile = PreferenceFile(file, read(file))

		/**
		 * `$XDG_CONFIG_HOME/prism/preferences.json` (`~/.config/prism/preferences.json`): where a user's settings for
		 * a program are kept, on Linux. Another platform's place is that platform's to say, when there is one.
		 */
		fun defaultFile(): Path {
			val config =
				System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") }?.let { Path.of(it) }
					?: Path.of(System.getProperty("user.home"), ".config")

			return config.resolve("prism/preferences.json")
		}

		fun read(file: Path): PreferencesRead =
			try {
				PreferencesRead.Read(
					json.decodeFromString(Preferences.serializer(), Files.readString(file)),
				)
			} catch (missing: NoSuchFileException) {
				PreferencesRead.Missing
			} catch (wrong: SerializationException) {
				PreferencesRead.Unreadable(wrong.message ?: wrong.toString())
			} catch (wrong: IllegalArgumentException) {
				PreferencesRead.Unreadable(wrong.message ?: wrong.toString())
			} catch (failed: IOException) {
				PreferencesRead.Unreadable(failed.message ?: failed.toString())
			}

		/** [preferences] written to [file] whole: beside it, then renamed over it, so it is never half written. */
		fun write(file: Path, preferences: Preferences) {
			val beside = file.resolveSibling("${file.fileName}.new")

			Files.createDirectories(file.parent)
			Files.writeString(
				beside,
				json.encodeToString(Preferences.serializer(), preferences) + "\n",
			)
			try {
				Files.move(beside, file, StandardCopyOption.ATOMIC_MOVE)
			} catch (unsupported: AtomicMoveNotSupportedException) {
				Files.move(beside, file, StandardCopyOption.REPLACE_EXISTING)
			}
		}
	}
}
