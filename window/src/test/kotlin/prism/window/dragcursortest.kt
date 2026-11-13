package prism.window

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The cursor a drag on Wayland shows, from what the compositor says of it and what prism's own windows say. Each case
 * is the events a compositor sends, in order, folded as the drag folds them.
 */
class DragCursorTest {
	/** The cursors shown as [events] come, one after another, from a drag's start, on a data device of [version]. */
	private fun shown(
		version: Int = 3,
		vararg events: (DragSeen) -> DragSeen,
	): List<CursorShape?> =
		events.runningFold(DragSeen()) { seen, event -> event(seen) }.drop(1).map {
			it.cursor(version)
		}

	private fun action(action: Int): (DragSeen) -> DragSeen = { it.copy(action = action) }

	private fun own(effect: DropEffect?): (DragSeen) -> DragSeen =
		{
			it.copy(own = OwnPlace(effect))
		}

	private val offOwn: (DragSeen) -> DragSeen = { it.copy(own = null) }

	@Test
	fun theCursorIsTheActionChosen_ThoughNiriNeverSaysTheWindowTakesIt() {
		// niri (Smithay) never says which type a window takes, only the action it chose for a drop there: a browser
		// takes a copy, and leaving it for no window is no action.
		assertEquals(
			listOf(CursorShape.Copy, CursorShape.NoDrop, CursorShape.Move, CursorShape.DndAsk),
			shown(
				3,
				action(WaylandDrags.ACTION_COPY),
				action(WaylandDrags.ACTION_NONE),
				action(WaylandDrags.ACTION_MOVE),
				action(WaylandDrags.ACTION_ASK),
			),
		)
	}

	@Test
	fun overPrismsOwnWindowsThePlaceThereSays_AndOffThemTheCompositorAgain() {
		// As the drag in the report went: SDL's window takes a copy, prism will move; off it, nothing takes it.
		assertEquals(
			listOf(
				CursorShape.Copy,
				CursorShape.Move,
				CursorShape.NoDrop,
				CursorShape.Move,
				CursorShape.NoDrop,
			),
			shown(
				3,
				action(WaylandDrags.ACTION_COPY),
				own(DropEffect.Move),
				own(null),
				own(DropEffect.Move),
				offOwn.andThen(action(WaylandDrags.ACTION_NONE)),
			),
		)
		assertEquals(CursorShape.Alias, shown(3, own(DropEffect.Link)).single())
	}

	@Test
	fun aDataDeviceTooOldToChooseLeavesTheCursorAlone_ButPrismsOwnWindowsStillSay() {
		assertNull(shown(2, action(WaylandDrags.ACTION_COPY)).single())
		assertEquals(CursorShape.Copy, shown(2, own(DropEffect.Copy)).single())
	}

	private fun ((DragSeen) -> DragSeen).andThen(
		next: (DragSeen) -> DragSeen,
	): (DragSeen) -> DragSeen = { next(this(it)) }
}
