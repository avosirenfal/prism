package prism.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The menu a search shows on the terminal: which lines are in sight as the highlight moves, what each key does, and
 * how the lines are drawn. The terminal itself is not here: what is tested is what is written to it and made of it.
 */
class MenuTest {
	private val items = (1..30).map { "item $it" }
	private val menu = Menu(items, 8) { it }

	/** The first line in sight, and which line of those in sight the highlight is on, for each place in a list. */
	private fun sights(size: Int, lines: Int = 8): List<Pair<Int, Int>> =
		(0 until size).map { at -> firstInSight(at, size, lines).let { it to at - it } }

	@Test
	fun theHighlightMovesToTheMiddleLineThenTheListMovesUnderItUntilItsEndIsInSight() {
		val moved = sights(30)

		// Down from the top the highlight moves, the list still: lines 0 to 4, the middle of eight.
		assertEquals((0..4).map { 0 to it }, moved.take(5))
		// From there the highlight stays on that line and the list moves a line at a time.
		assertEquals((5..26).map { it - 4 to 4 }, moved.subList(5, 27))
		// With the last line in sight the list is still again, and the highlight goes down to the end.
		assertEquals((27..29).map { 22 to it - 22 }, moved.drop(27))
		// A list that fits is never moved; one of a single line is that line.
		assertEquals((0..4).map { 0 to it }, sights(5))
		assertEquals(listOf(0 to 0), sights(1))
		// Going up is the same the other way, since where the list is depends only on where the highlight is.
		assertEquals(11, firstInSight(15, 30, 8))
	}

	@Test
	fun eachKeyIsWhatATerminalSendsForIt() {
		fun key(vararg bytes: Int) = keyOf(bytes.toList(), page = 8)

		assertEquals(Key.Move(-1), key(0x1B, '['.code, 'A'.code))
		assertEquals(Key.Move(1), key(0x1B, '['.code, 'B'.code))
		// The arrows of a keypad in its other mode.
		assertEquals(Key.Move(1), key(0x1B, 'O'.code, 'B'.code))
		assertEquals(Key.Move(1), key('j'.code))
		assertEquals(Key.Move(-1), key('k'.code))
		assertEquals(Key.Move(1), key(0x0E))
		assertEquals(Key.Move(-1), key(0x10))
		assertEquals(Key.Move(8), key(0x1B, '['.code, '6'.code, '~'.code))
		assertEquals(Key.Move(-8), key(0x1B, '['.code, '5'.code, '~'.code))
		assertEquals(Key.Jump(last = false), key(0x1B, '['.code, 'H'.code))
		assertEquals(Key.Jump(last = true), key(0x1B, '['.code, 'F'.code))
		assertEquals(Key.Choose, key('\r'.code))
		assertEquals(Key.Choose, key('\n'.code))
		assertEquals(Key.Cancel, key(0x1B))
		assertEquals(Key.Cancel, key('q'.code))
		assertEquals(Key.Cancel, key(0x03))
		assertEquals(Key.Pick(3), key('3'.code))
		assertNull(key('x'.code))
		assertNull(key(0x1B, '['.code, 'Z'.code))
	}

	@Test
	fun aKeyMovesTheHighlightWithinTheListAndADigitPicksALineInSight() {
		assertEquals(1, menu.moved(0, Key.Move(1)))
		assertEquals(0, menu.moved(0, Key.Move(-1)))
		assertEquals(29, menu.moved(25, Key.Move(8)))
		assertEquals(29, menu.moved(3, Key.Jump(last = true)))
		assertEquals(0, menu.moved(3, Key.Jump(last = false)))
		// With the highlight at 15 the lines in sight are 11 to 18: the third of them is 13.
		assertEquals(13, menu.moved(15, Key.Pick(3)))
		// A line there is none of is nothing picked.
		assertEquals(15, menu.moved(15, Key.Pick(9)))
		assertEquals(1, Menu(listOf("a", "b"), 8) { it }.moved(0, Key.Pick(2)))
		assertEquals(0, Menu(listOf("a", "b"), 8) { it }.moved(0, Key.Pick(3)))
	}

	@Test
	fun theLinesInSightAreDrawnWithTheOneHighlightedAndWhereInTheListItIsUnderThem() {
		val drawn = menu.lines(15, 40)

		assertEquals(9, drawn.size)
		assertEquals("  item 12", drawn.first().text)
		assertEquals(Line("> item 16", highlighted = true), drawn[4])
		assertEquals(1, drawn.count { it.highlighted })
		assertEquals(Line("16 of 30 · Enter chooses, Esc cancels", faint = true), drawn.last())
		// A line longer than the terminal is wide is cut to it.
		assertEquals("> item", Menu(items, 8) { it }.lines(0, 6).first().text)
		// A short list takes as many lines as it has.
		assertEquals(3, Menu(listOf("a", "b"), 8) { it }.lines(0, 40).size)
		assertEquals(
			"\u001B[7m> a\u001B[0m",
			Menu(listOf("a"), 8) {
				it
			}.lines(0, 40).first().drawn,
		)
	}
}
