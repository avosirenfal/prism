package prism.preferences

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * How a date and its time are written: each of the common ways, and the system's own. Each is a date, then its time,
 * which a listing sets apart, so that every row's times line up whatever the font.
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

	/** How the date is written, how its time is, and what is between them. */
	private val forms: Triple<DateTimeFormatter, DateTimeFormatter, String> by lazy {
		val clock24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
		val clock12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

		when (this) {
			Written ->
				Triple(
					DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
					clock24,
					", ",
				)
			WrittenMonthFirst ->
				Triple(
					DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
					clock12,
					", ",
				)
			Iso -> Triple(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT), clock24, " ")
			DayFirst ->
				Triple(
					DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT),
					clock24,
					", ",
				)
			MonthFirst ->
				Triple(
					DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ROOT),
					clock12,
					", ",
				)
			Dotted -> Triple(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT), clock24, ", ")
			System ->
				Triple(
					DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM),
					DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT),
					" ",
				)
		}
	}

	/** [instant]'s date, and its time, each written this way, in [zone]. */
	fun parts(
		instant: Instant,
		zone: ZoneId = ZoneId.systemDefault(),
	): Pair<String, String> =
		instant.atZone(zone).let { forms.first.format(it) to forms.second.format(it) }

	/** [instant], written this way, in [zone]: its date, then its time. */
	fun write(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
		parts(instant, zone).let { (date, time) -> date + forms.third + time }
}
