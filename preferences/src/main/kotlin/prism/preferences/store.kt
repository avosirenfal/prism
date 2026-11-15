package prism.preferences

import java.nio.file.Path
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import prism.shared.Format
import prism.shared.Loaded
import prism.shared.OnDisk
import prism.shared.Parsed
import prism.shared.Stored

/*
 * Where the preferences are kept, and how a change reaches every window. They are a value kept in a file
 * (prism.shared.Stored): the store holds what they are now as a state every window collects, and a change is made
 * through the store alone, which reads the file as it is, makes the change to that, writes it whole, and hands it
 * to every window. No window reads the file, and nothing reads it again because it changed. A process has one
 * store of the file, read once as it starts; a file changed by hand while prism runs is as it was written the next
 * time prism starts.
 */

/** The preferences, as every window reads them, and the one way to change them. */
typealias PreferenceStore = Stored<Preferences>

/** What the preferences are now, and each thing they become. */
val PreferenceStore.preferences: StateFlow<Preferences> get() = value

/** Preferences held in memory alone: a picker or an explorer run without a file, and the tests. */
@Suppress("FunctionName")
fun MemoryPreferences(initial: Preferences = Preferences.DEFAULT): PreferenceStore =
	Stored.inMemory(initial)

/**
 * The preferences as a file: JSON, every setting written, defaults too, so the file shows what can be set, and read
 * leniently (a setting it does not know is left alone). A file that does not read as preferences is every default
 * meanwhile, and is kept beside itself (`.unreadable`) before anything is written over it.
 */
object PreferenceFormat : Format<Preferences> {
	private val json =
		Json {
			prettyPrint = true
			encodeDefaults = true
			ignoreUnknownKeys = true
		}

	override val none: Preferences get() = Preferences.DEFAULT

	override fun read(bytes: ByteArray): Parsed<Preferences> =
		try {
			Loaded.Read(
				json.decodeFromString(Preferences.serializer(), bytes.toString(Charsets.UTF_8)),
			)
		} catch (wrong: SerializationException) {
			Loaded.Unreadable(wrong.message ?: wrong.toString())
		} catch (wrong: IllegalArgumentException) {
			Loaded.Unreadable(wrong.message ?: wrong.toString())
		}

	override fun written(value: Preferences): ByteArray =
		(
			json.encodeToString(
				Preferences.serializer(),
				value,
			) + "\n"
		).toByteArray(Charsets.UTF_8)
}

/** The preferences' file. */
object PreferenceFile {
	/** The preferences in [file], held, and kept there as they change. */
	fun open(file: Path = defaultFile()): PreferenceStore = Stored.of(file, PreferenceFormat)

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

	/** What [file] says, read now: for looking at one that no store here holds. */
	fun read(file: Path): Loaded<Preferences> = OnDisk(file, PreferenceFormat).read()
}
