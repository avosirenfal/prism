package prism.shared

/**
 * Which way a turn of the wheel, or a swipe on a touchpad, moves what is under the pointer: as the system is set (on
 * a Mac, natural scrolling where it is on, which the system gives every application as it is), or the other way.
 */
enum class ScrollDirection {
	AsTheSystem,
	Reversed,
}
