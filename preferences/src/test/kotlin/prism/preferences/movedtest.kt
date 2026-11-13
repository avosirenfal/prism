package prism.preferences

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MovedTest {
	private val letters = listOf("a", "b", "c", "d", "e")

	/** These, with [move] made among those [shown], each told apart by itself. */
	private fun List<String>.among(shown: List<String>, move: Moved): List<String> =
		movedAmong(shown, { it }, move)

	@Test
	fun oneMovedLandsWhereItIsPut_AsTakingItOutAndPuttingItBackDoes() {
		for (from in letters.indices)
			for (to in letters.indices)
				assertEquals(
					letters.toMutableList().apply { add(to, removeAt(from)) },
					letters.moved(Moved.of(from, to)),
					"$from to $to",
				)
	}

	@Test
	fun severalMovedGoTogetherInTheirOrder_TheFirstWhereTheyArePut() {
		assertEquals(listOf("b", "d", "a", "c", "e"), letters.moved(Moved(listOf(1, 3), 0)))
		assertEquals(listOf("a", "c", "b", "d", "e"), letters.moved(Moved(listOf(1, 3), 2)))
		// Past the end, they are last.
		assertEquals(listOf("a", "c", "e", "b", "d"), letters.moved(Moved(listOf(1, 3), 9)))
	}

	@Test
	fun movedAmongThoseShown_WhatIsNotShownStaysWhereItIsAmongTheRest() {
		val all = listOf("a", "x", "b", "y", "c")
		val shown = listOf("a", "b", "c")

		// c, first of those shown: before a.
		assertEquals(
			listOf("c", "a", "x", "b", "y"),
			all.among(shown, Moved.of(2, 0)),
		)
		// a, last of those shown: just after c.
		assertEquals(
			listOf("x", "b", "y", "c", "a"),
			all.among(shown, Moved.of(0, 2)),
		)
		// a and c together, between nothing shown: after b, before y, which is not shown.
		assertEquals(
			listOf("x", "b", "a", "c", "y"),
			all.among(shown, Moved(listOf(0, 2), 1)),
		)
		// Every one shown moved passes none: nothing changes.
		assertEquals(all, all.among(shown, Moved(listOf(0, 1, 2), 0)))
		// With all of them shown, it is a move as any.
		assertEquals(
			letters.moved(Moved(listOf(1, 3), 2)),
			letters.among(letters, Moved(listOf(1, 3), 2)),
		)
	}

	@Test
	fun aMoveMovesSomething_EachOnce_InOrder() {
		assertThrows<IllegalArgumentException> { Moved(emptyList(), 0) }
		assertThrows<IllegalArgumentException> { Moved(listOf(2, 1), 0) }
		assertThrows<IllegalArgumentException> { Moved(listOf(1, 1), 0) }
		assertThrows<IllegalArgumentException> { Moved(listOf(-1), 0) }
	}
}
