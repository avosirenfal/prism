package prism.preferences

import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * How a date and its time are written: each of the common ways, and the system's own. Each is a pattern for the date
 * and one for its time, read into their parts ([DatePart]): the fields (the day, the month's name, the hour) and the
 * words between them. A listing lines each field up with the same field of every other row's date, so that a column of
 * dates reads as a table whatever the font, though "May" is narrower than "Nov" and the 1st than the 12th.
 */
@Serializable
enum class DateFormat(
	val label: String,
) {
	/** `12 Nov 2026, 14:03`. */
	Written("Day, month, year"),

	/** `Nov 12, 2026, 2:03 PM`. */
	WrittenMonthFirst("Month, day, year"),

	/** `2026-11-12 14:03`: ISO 8601's order, which sorts as text does. */
	Iso("Year-month-day"),

	/** `12/11/2026, 14:03`. */
	DayFirst("Day/month/year"),

	/** `11/12/2026, 2:03 PM`. */
	MonthFirst("Month/day/year"),

	/** `12.11.2026, 14:03`. */
	Dotted("Day.month.year"),

	/** As the system's language and country write a date. */
	System("The system's own"),
	;

	/** How it is written: the date's pattern, the time's, what is between them, and the language of their words. */
	private val writing: Writing by lazy {
		when (this) {
			Written -> Writing("d MMM yyyy", "HH:mm", ", ", Locale.ENGLISH)
			WrittenMonthFirst -> Writing("MMM d, yyyy", "h:mm a", ", ", Locale.ENGLISH)
			Iso -> Writing("yyyy-MM-dd", "HH:mm", " ", Locale.ROOT)
			DayFirst -> Writing("dd/MM/yyyy", "HH:mm", ", ", Locale.ROOT)
			MonthFirst -> Writing("MM/dd/yyyy", "h:mm a", ", ", Locale.ENGLISH)
			Dotted -> Writing("dd.MM.yyyy", "HH:mm", ", ", Locale.ROOT)
			System -> {
				val locale = Locale.getDefault(Locale.Category.FORMAT)

				fun pattern(date: FormatStyle?, time: FormatStyle?) =
					DateTimeFormatterBuilder.getLocalizedDateTimePattern(
						date,
						time,
						IsoChronology.INSTANCE,
						locale,
					)

				Writing(
					pattern(FormatStyle.MEDIUM, null),
					pattern(null, FormatStyle.SHORT),
					" ",
					locale,
				)
			}
		}
	}

	/** The date's parts, in order. */
	val dateParts: List<DatePart> get() = writing.date

	/** The time's parts, in order. */
	val timeParts: List<DatePart> get() = writing.time

	/** [part] of [instant], as this way writes it, in [zone]: a field's value, or the words between fields as they are. */
	fun write(
		part: DatePart,
		instant: Instant,
		zone: ZoneId = ZoneId.systemDefault(),
	): String =
		when (part) {
			is DatePart.Field -> writing.formatterOf(part).format(instant.atZone(zone))
			is DatePart.Words -> part.text
		}

	/** [instant], written this way, in [zone]: its date, then its time. */
	fun write(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
		(dateParts + DatePart.Words(writing.between) + timeParts).joinToString("") {
			write(it, instant, zone)
		}
}

/**
 * A part of a date or a time as it is written: a [Field] of it, or the [Words] between fields (a comma, a slash, a
 * colon), which are the same in every row.
 */
sealed interface DatePart {
	/**
	 * A field, written as [pattern] says (`d`, `MMM`, `yyyy`, `h`, `a`): a number, which lines up with the others at its
	 * end, as numbers do in a table, or a word (a month's or a weekday's name, AM or PM), which lines up at its start.
	 */
	data class Field(
		val pattern: String,
	) : DatePart {
		/** Whether it is written as a number: a month by its number (`M`, `MM`), and every field but the words. */
		val numeric: Boolean
			get() =
				when (pattern.first()) {
					'M', 'L' -> pattern.length <= 2
					'a', 'B', 'E', 'G', 'z', 'v', 'V', 'O', 'c', 'e' -> false
					else -> true
				}
	}

	data class Words(
		val text: String,
	) : DatePart

	companion object {
		/**
		 * The parts of [pattern], as `DateTimeFormatter` reads one: a run of one letter is a field, words in single quotes
		 * are words (two quotes being one), and anything else is words too. Words side by side are one part.
		 */
		fun of(pattern: String): List<DatePart> {
			data class Reading(
				val parts: List<DatePart> = emptyList(),
				val quoted: Boolean = false,
				val quote: Boolean = false,
			) {
				fun with(part: DatePart): Reading =
					when (val last = parts.lastOrNull()) {
						is Words if part is Words ->
							copy(
								parts =
									parts.dropLast(1) + Words(last.text + part.text),
							)
						is Field if part is Field && last.pattern.last() == part.pattern.first() ->
							copy(parts = parts.dropLast(1) + Field(last.pattern + part.pattern))
						else -> copy(parts = parts + part)
					}
			}

			return pattern
				.fold(Reading()) { at, char ->
					when {
						// Two quotes in a row are a quote, inside quoted words or out of them.
						at.quote && char == '\'' ->
							at
								.with(
									Words("'"),
								).copy(quote = false, quoted = !at.quoted)
						char == '\'' -> at.copy(quote = true, quoted = !at.quoted)
						at.quoted -> at.with(Words(char.toString())).copy(quote = false)
						char.isLetter() -> at.with(Field(char.toString())).copy(quote = false)
						else -> at.with(Words(char.toString())).copy(quote = false)
					}
				}.parts
		}
	}
}

/** A way of writing a date: its date's parts, its time's, the words between them, and the language their words are in. */
private class Writing(
	date: String,
	time: String,
	val between: String,
	private val locale: Locale,
) {
	val date: List<DatePart> = DatePart.of(date)
	val time: List<DatePart> = DatePart.of(time)
	private val formatters =
		(this.date + this.time)
			.filterIsInstance<DatePart.Field>()
			.associateWith {
				formatterOf(it.pattern)
			}

	fun formatterOf(field: DatePart.Field): DateTimeFormatter =
		formatters[field] ?: formatterOf(field.pattern)

	private fun formatterOf(pattern: String): DateTimeFormatter =
		DateTimeFormatter.ofPattern(pattern, locale)
}
