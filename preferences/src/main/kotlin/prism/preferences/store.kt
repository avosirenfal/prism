package prism.preferences

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import prism.linux.WholeFile

/*
 * Where the preferences are kept, and how a change reaches every window. A store holds the current value as a state
 * every window collects; a change is made through the store, which makes the new value, writes it where it keeps a
 * file, and hands it to every window: no window reads the file, and nothing reads it again because it changed. A
 * process has one store of a file, read once as it starts; a file changed by hand while prism runs is as it was
 * written the next time prism starts.
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
 * Preferences kept in [file], as JSON: read once, as the store is made, and written whole at each change
 * ([WholeFile]). A change is made to the file as it then is, not to what this store last knew of it, so that what
 * another process wrote meanwhile (a window run on its own, beside the prism that is running) is kept where the
 * change does not touch it; every window of this process is then given what was written. A file that cannot be read
 * leaves every default in place, and is kept as it was, beside it with `.unreadable` after its name, before the first
 * change written over it, so nothing written by hand is lost.
 */
class PreferenceFile private constructor(
	val file: Path,
	first: PreferencesRead,
	private val reading: (Path) -> PreferencesRead,
) : PreferenceStore {
	private val state = MutableStateFlow(first.preferences)

	/** What the last read of the file said, where it could not be read; null where it could, or there was none. */
	@Volatile
	var problem: String? = (first as? PreferencesRead.Unreadable)?.why
		private set

	override val preferences: StateFlow<Preferences> = state.asStateFlow()

	override fun update(change: (Preferences) -> Preferences) {
		WholeFile.locked(file) {
			// What the file says now is what is changed; where it says nothing that can be read (it is not there,
			// or was written wrong), what this store holds is.
			val now =
				when (val read = reading(file)) {
					is PreferencesRead.Read -> read.preferences
					PreferencesRead.Missing -> state.value
					is PreferencesRead.Unreadable -> state.value.also { keepUnreadable() }
				}
			val next = change(now)

			write(file, next)
			problem = null
			state.value = next
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

	companion object {
		/** How the file is written: every setting, defaults too, so the file shows what can be set; read leniently. */
		private val json =
			Json {
				prettyPrint = true
				encodeDefaults = true
				ignoreUnknownKeys = true
			}

		/**
		 * The preferences in [file], and a store that keeps them there. The file is read with [reading], now and
		 * before each change is written, and at no other time.
		 */
		fun open(
			file: Path = defaultFile(),
			reading: (Path) -> PreferencesRead = ::read,
		): PreferenceFile = PreferenceFile(file, reading(file), reading)

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

		/** [preferences] written to [file] whole ([WholeFile]): it is never half written. */
		fun write(file: Path, preferences: Preferences) {
			WholeFile.replace(
				file,
				json.encodeToString(Preferences.serializer(), preferences) + "\n",
			)
		}
	}
}
