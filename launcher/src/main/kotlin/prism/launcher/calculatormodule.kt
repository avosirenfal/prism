package prism.launcher

import prism.calc.Calculation
import prism.calc.Completion
import prism.calc.Outcome
import prism.calc.Shape

/*
 * The calculator, as a module of the launcher's (calc holds the language). Typed bare, arithmetic is worked out as it
 * is typed, and its answer shown along the top, copied by the banner's key; a date's dashes (2024-11-18), which may be
 * arithmetic and may be a name, are worked out too, and answered along the top as well. In its view (`=` or `calc ` before what
 * is typed), everything typed is the calculator's: what is wrong with it is said as it is typed, and the name being
 * typed is completed (Tab), with the types each of its overloads takes and gives.
 */

object CalculatorModule : LauncherModule {
	override val id: ModuleId = ModuleId("calculator")
	override val title: String get() = "Calculator"
	override val reach: Reach = Reach.AlsoBare(listOf(Prefix.Sign("="), Prefix.Word("calc")))

	private const val COPY = "Copy"

	override fun bare(query: String): ModuleAnswer? {
		val calculation = Calculation.of(query.trim())

		return when (calculation.shape) {
			Shape.Calculation ->
				ModuleAnswer(
					Strength.Certain,
					banner = bannerOf(calculation.outcome),
				)
			// A date's dashes may be arithmetic and may be a name: answered along the top all the same, which costs
			// the files found for it nothing, and less sure, so that a surer module's banner is shown first.
			Shape.Ambiguous ->
				(calculation.outcome as? Outcome.Answer)?.let {
					ModuleAnswer(Strength.Possible, banner = bannerOf(it))
				}
			Shape.Plain, null -> null
		}
	}

	override fun view(text: String): ModuleView {
		val calculation = Calculation.of(text)

		return ModuleView(
			placeholder = "2 ** 10, sin(pi / 4), 255.toUInt().toHex()…",
			// What is wrong is said as it is typed, but for a name still being typed, which is what is wrong
			// with it until it is finished: there, what it could be is shown under the field instead.
			banner =
				bannerOf(calculation.outcome).takeIf {
					text.isNotBlank() &&
						(calculation.outcome is Outcome.Answer || calculation.completions.isEmpty())
				},
			rows = calculation.completions.map { rowOf(it, text) },
		)
	}

	private fun bannerOf(outcome: Outcome): Banner =
		when (outcome) {
			is Outcome.Answer ->
				Banner(
					outcome.shown,
					outcome.type,
					outcome.notes,
					key = BannerKey.Does(COPY, ModuleAct.Copy(outcome.copied)),
				)
			is Outcome.Wrong -> Banner(capitalized(outcome.why), tone = Tone.Wrong)
			is Outcome.Unread ->
				Banner(
					"${capitalized(outcome.why)}, at ${outcome.at + 1}",
					tone = Tone.Wrong,
				)
		}

	private fun capitalized(text: String): String =
		text.replaceFirstChar(Char::uppercaseChar)

	/** [completion] as a row: chosen, it is put where the name being typed at the end of [text] is. */
	private fun rowOf(completion: Completion, text: String): ModuleRow {
		val begun = text.takeLastWhile { it.isLetterOrDigit() || it == '_' }

		return ModuleRow(
			completion.name,
			completion.signatures.first(),
			listOfNotNull(
				completion.does,
				(completion.signatures.size - 1).let { more ->
					"and $more more overload${if (more == 1) "" else "s"}".takeIf { more > 0 }
				},
			).joinToString(" · "),
			completion.kind.written,
			ModuleAct.Put(text.dropLast(begun.length) + completion.inserted),
		)
	}
}
