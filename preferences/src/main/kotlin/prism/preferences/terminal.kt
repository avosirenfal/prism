package prism.preferences

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import prism.desktop.ExecArg
import prism.desktop.FolderArgs
import prism.desktop.TerminalChoice

/*
 * The terminal the user chooses, as the preferences file writes it (docs/explorer.md, "Open terminal here"):
 *
 *     "terminal": null                                   the desktop's own, as found
 *     "terminal": ["ghostty", "--font-size=14"]          a terminal, with options of the user's own; told a folder
 *                                                        and given a command as it is known to take them
 *     "terminal": ["foot", "-D", "{folder}", "{command}"] words run as they are written
 *     "terminal": {                                      a terminal described, where what is known of it is wrong
 *         "command": ["my-term", "--login"],             or nothing is: each key left out is as it is known
 *         "folder": ["--start-in", "{folder}"],          the folder's words ([] for none: started in the folder)
 *         "run": ["-x"],                                 what comes before a command's words ([] for nothing;
 *                                                        null, for a terminal that cannot be given one)
 *         "alone": ["--new-process"]                     what has it open a window of its own
 *     }
 *
 * What does not read as one of these is kept as it was written, and said to be wrong where the terminal is asked for,
 * rather than losing the rest of the preferences with it.
 */
object TerminalChoiceSerializer : KSerializer<TerminalChoice> {
	override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

	override fun deserialize(decoder: Decoder): TerminalChoice =
		choiceOf((decoder as JsonDecoder).decodeJsonElement())

	override fun serialize(encoder: Encoder, value: TerminalChoice) =
		(encoder as JsonEncoder).encodeJsonElement(jsonOf(value))

	/** [json] as the user's choice of terminal. */
	fun choiceOf(json: JsonElement): TerminalChoice =
		try {
			when (json) {
				JsonNull -> TerminalChoice.Found
				is JsonArray -> wordsOf(json, "terminal").let(::listed)
				is JsonObject -> described(json)
				is JsonPrimitive -> throw wrong(
					"it is a list of words, or a description of a terminal",
				)
			}
		} catch (failed: Wrong) {
			TerminalChoice.Unreadable(json.toString(), failed.why)
		}

	/** A list of words: run as written where they say where the folder or a command goes; a terminal otherwise. */
	private fun listed(words: List<String>): TerminalChoice =
		when {
			words.isEmpty() -> TerminalChoice.Found
			words.any {
				FolderArgs.FOLDER in it || it == TerminalChoice.Written.COMMAND
			} -> TerminalChoice.Written(words)
			else -> TerminalChoice.Described(words)
		}

	private fun described(json: JsonObject): TerminalChoice.Described {
		val unknown = json.keys - setOf("command", "folder", "run", "alone")

		if (unknown.isNotEmpty())
			throw wrong(
				"it says ${unknown.joinToString {
					"“$it”"
				}}, which is not one of command, folder, run, alone",
			)

		val command =
			wordsOf(
				json["command"]
					?: throw wrong("it has no “command”: the terminal's program, and options"),
				"command",
			).ifEmpty { throw wrong("its “command” is empty") }

		val folder =
			json["folder"]?.let { words ->
				FolderArgs.of(wordsOf(words, "folder"))
					?: throw wrong(
						"its “folder” does not say once where the folder goes: write {folder} in one word",
					)
			}

		val exec =
			json["run"]?.let { words ->
				if (words == JsonNull)
					ExecArg.Unsupported
				else
					ExecArg.Before(wordsOf(words, "run"))
			}
		val alone = json["alone"]?.let { wordsOf(it, "alone") }

		return TerminalChoice.Described(command, folder, exec, alone)
	}

	private fun wordsOf(json: JsonElement, name: String): List<String> =
		(json as? JsonArray)
			?.map {
				(it as? JsonPrimitive)?.takeIf { word -> word.isString }?.content
					?: throw wrong("“$name” has a word that is not text")
			}
			?: throw wrong("“$name” is not a list of words")

	/** [choice] as the file writes it: as briefly as it reads back the same. */
	fun jsonOf(choice: TerminalChoice): JsonElement =
		when (choice) {
			TerminalChoice.Found -> JsonNull
			is TerminalChoice.Written -> JsonArray(choice.words.map(::JsonPrimitive))
			is TerminalChoice.Described ->
				if (choice.folder == null && choice.exec == null && choice.alone == null)
					JsonArray(choice.command.map(::JsonPrimitive))
				else
					buildJsonObject {
						put("command", JsonArray(choice.command.map(::JsonPrimitive)))
						choice.folder?.let { put("folder", JsonArray(it.template.map(::JsonPrimitive))) }
						choice.exec?.let {
							put(
								"run",
								when (it) {
									is ExecArg.Before -> JsonArray(it.words.map(::JsonPrimitive))
									ExecArg.Unsupported -> JsonNull
								},
							)
						}
						choice.alone?.let { put("alone", JsonArray(it.map(::JsonPrimitive))) }
					}
			// What could not be read is written back as it was, for the user to mend.
			is TerminalChoice.Unreadable ->
				try {
					Json.parseToJsonElement(choice.written)
				} catch (unparsable: SerializationException) {
					JsonPrimitive(choice.written)
				}
		}

	private class Wrong(
		val why: String,
	) : Exception(why)

	private fun wrong(why: String) =
		Wrong("the terminal in the preferences can't be read: $why")
}
