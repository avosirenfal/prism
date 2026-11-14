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
import prism.desktop.CommandLine
import prism.desktop.FolderLine
import prism.desktop.Read
import prism.desktop.TerminalChoice

/*
 * The terminal the user chooses, as the preferences file writes it (docs/explorer.md, "Choosing the terminal"):
 *
 *     "terminal": null                     the desktop's own, as found
 *     "terminal": "ghostty"                a terminal by its name, told a folder and given a command as prism (or
 *                                          its desktop entry) knows it takes them
 *     "terminal": {                        its command lines, each written out in full:
 *         "open": ["ghostty", "--working-directory={folder}"],
 *                                          opened in a folder ("Open terminal here")
 *         "run": ["ghostty", "--working-directory={folder}", "-e", "{command}"]
 *                                          opened in a folder, running a command there
 *     }
 *
 * Either line may be left out, and what it is for is then off. What does not read as one of these is kept as it was
 * written, and said to be wrong where the terminal is asked for, rather than losing the rest of the preferences with it.
 */
object TerminalChoiceSerializer : KSerializer<TerminalChoice> {
	override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

	override fun deserialize(decoder: Decoder): TerminalChoice =
		choiceOf((decoder as JsonDecoder).decodeJsonElement())

	override fun serialize(encoder: Encoder, value: TerminalChoice) =
		(encoder as JsonEncoder).encodeJsonElement(jsonOf(value))

	private const val SHAPE =
		"expected a terminal name, or command lines: {\"open\": [...], \"run\": [...]}"

	/** [json] as the user's choice of terminal. */
	fun choiceOf(json: JsonElement): TerminalChoice {
		fun wrong(why: String) =
			TerminalChoice.Unreadable(
				json.toString(),
				"the terminal setting is invalid: $why",
			)

		return when (json) {
			JsonNull -> TerminalChoice.Found
			is JsonPrimitive ->
				json
					.takeIf {
						it.isString && it.content.isNotBlank()
					}?.let { TerminalChoice.Named(it.content.trim()) }
					?: wrong(SHAPE)
			is JsonArray -> wrong(SHAPE)
			is JsonObject -> {
				val unknown = json.keys - setOf("open", "run")

				if (unknown.isNotEmpty())
					return wrong(
						"unknown key ${unknown.joinToString { "“$it”" }}; expected “open” or “run”",
					)

				val open =
					json["open"]?.let { line ->
						when (
							val read =
								wordsOf(line)?.let(FolderLine::of)
									?: return wrong("“open” must be a list of words")
						) {
							is Read.Right -> read.value
							is Read.Wrong -> return wrong("“open”: ${read.why}")
						}
					}

				val run =
					json["run"]?.let { line ->
						when (
							val read =
								wordsOf(line)?.let(CommandLine::of)
									?: return wrong("“run” must be a list of words")
						) {
							is Read.Right -> read.value
							is Read.Wrong -> return wrong("“run”: ${read.why}")
						}
					}

				TerminalChoice.CommandLines.of(open, run)
					?: wrong("it needs an “open” or a “run” command line")
			}
		}
	}

	private fun wordsOf(json: JsonElement): List<String>? =
		(json as? JsonArray)?.map {
			(it as? JsonPrimitive)?.takeIf { word -> word.isString }?.content
				?: return null
		}

	/** [choice] as the file writes it. */
	fun jsonOf(choice: TerminalChoice): JsonElement =
		when (choice) {
			TerminalChoice.Found -> JsonNull
			is TerminalChoice.Named -> JsonPrimitive(choice.name)
			is TerminalChoice.CommandLines ->
				buildJsonObject {
					choice.inFolder?.let { put("open", JsonArray(it.words.map(::JsonPrimitive))) }
					choice.running?.let { put("run", JsonArray(it.words.map(::JsonPrimitive))) }
				}
			// What could not be read is written back as it was, for the user to mend.
			is TerminalChoice.Unreadable ->
				try {
					Json.parseToJsonElement(choice.written)
				} catch (unparsable: SerializationException) {
					JsonPrimitive(choice.written)
				}
		}
}
