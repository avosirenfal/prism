package prism.preferences

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.Serializable

/** How a date and its time are written: each of the common ways, and the system's own. */
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

	private val formatter: DateTimeFormatter by lazy {
		when (this) {
			Written -> DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
			WrittenMonthFirst -> DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.ENGLISH)
			Iso -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
			DayFirst -> DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm", Locale.ROOT)
			MonthFirst -> DateTimeFormatter.ofPattern("MM/dd/yyyy, h:mm a", Locale.ENGLISH)
			Dotted -> DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.ROOT)
			System -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
		}
	}

	/** [instant], written this way, in [zone]. */
	fun write(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
		formatter.format(instant.atZone(zone))
}
