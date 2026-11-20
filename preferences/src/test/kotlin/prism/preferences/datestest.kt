package prism.preferences

import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DatesTest {
	private val at = Instant.parse("2026-11-20T14:03:21.507Z")

	@Test
	fun aTimeIsWrittenToTheSecondOrTheMillisecondAsEachFormatWritesItsMinutes() {
		fun time(format: DateFormat, precision: TimePrecision) =
			format.writeTime(at, ZoneOffset.UTC, precision)

		assertEquals("14:03", time(DateFormat.Written, TimePrecision.Minutes))
		assertEquals("14:03:21", time(DateFormat.Written, TimePrecision.Seconds))
		assertEquals("14:03:21.507", time(DateFormat.Iso, TimePrecision.Milliseconds))
		// Twelve hours, with AM or PM after the seconds.
		assertEquals("2:03:21 PM", time(DateFormat.MonthFirst, TimePrecision.Seconds))
		assertEquals(
			"2:03:21.507 PM",
			time(DateFormat.WrittenMonthFirst, TimePrecision.Milliseconds),
		)
		assertEquals(
			"2026-11-20 14:03:21",
			DateFormat.Iso.write(at, ZoneOffset.UTC, TimePrecision.Seconds),
		)
		assertEquals("20 Nov 2026, 14:03", DateFormat.Written.write(at, ZoneOffset.UTC))
	}
}
