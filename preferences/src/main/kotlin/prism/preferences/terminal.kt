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
import prism.desktop.TerminalChoice

/*
 * The terminal the user chooses, as the preferences file writes it (docs/explorer.md, "Choosing the terminal"):
 *
 *     "terminal": null                                     the desktop's own, as found
 *     "terminal": "ghostty"                                a terminal by its name, told a folder and given a
 *                                                          command as prism (or its desktop entry) knows it takes them
 *     "terminal": ["ghostty", "--working-directory={folder}", "-e", "{command}"]
 *                                                          its command line, as it is to be run
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
	fun choiceOf(json: JsonElement): TerminalChoice {
		fun wrong(why: String) =
			TerminalChoice.Unreadable(
				json.toString(),
				"the terminal in the preferences can't be read: $why",
			)

		return when (json) {
			JsonNull -> TerminalChoice.Found
			is JsonPrimitive ->
				json
					.takeIf {
						it.isString && it.content.isNotBlank()
					}?.let { TerminalChoice.Named(it.content.trim()) }
					?: wrong("it is a terminal's name, or its command line as a list of words")
			is JsonArray -> {
				val words =
					json.map { (it as? JsonPrimitive)?.takeIf { word -> word.isString }?.content }

				when {
					words.any { it == null } -> wrong("its command line has a word that is not text")
					// A name alone, as a list of one.
					words.size == 1 &&
						commandLineOf(words.filterNotNull()) is TerminalChoice.Written.Either.Wrong ->
						TerminalChoice.Named(words.single()!!)
					else ->
						when (val read = commandLineOf(words.filterNotNull())) {
							is TerminalChoice.Written.Either.Right -> read.written
							is TerminalChoice.Written.Either.Wrong -> wrong(read.why)
						}
				}
			}
			is JsonObject ->
				wrong(
					"it is a terminal's name, or its command line as a list of words",
				)
		}
	}

	private fun commandLineOf(words: List<String>) = TerminalChoice.Written.of(words)

	/** [choice] as the file writes it. */
	fun jsonOf(choice: TerminalChoice): JsonElement =
		when (choice) {
			TerminalChoice.Found -> JsonNull
			is TerminalChoice.Named -> JsonPrimitive(choice.name)
			is TerminalChoice.Written -> JsonArray(choice.words.map(::JsonPrimitive))
			// What could not be read is written back as it was, for the user to mend.
			is TerminalChoice.Unreadable ->
				try {
					Json.parseToJsonElement(choice.written)
				} catch (unparsable: SerializationException) {
					JsonPrimitive(choice.written)
				}
		}
}
