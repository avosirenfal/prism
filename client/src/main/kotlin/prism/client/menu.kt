package prism.client

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * A menu on the terminal: a few lines of a list in sight, one of them highlighted, moved through with the arrows
 * and chosen with Enter. It is drawn on the terminal itself (`/dev/tty`), not on standard output, so a command that
 * shows one can still have what was chosen read by whatever started it (`cd "$(prism search -i report)"`).
 *
 * The terminal is put in raw mode for it, so that a key is read as it is pressed, by `stty`, which every Unix has,
 * rather than through a library a program compiled ahead of time would have to carry. However the menu ends, the
 * terminal is put back as it was.
 */

/**
 * Which of [size] lines are in sight, [lines] at a time, with the one at [at] highlighted: the first of them. The
 * highlight moves down the lines in sight until it is on the middle one; from there the list moves under it, the
 * highlight staying on that line, until the list's last line is in sight, and then the highlight moves again, down to
 * the end. Going up is the same the other way. So what is around the line chosen is in sight on both sides of it,
 * wherever in the list it is.
 */
fun firstInSight(at: Int, size: Int, lines: Int): Int =
	(at - lines / 2).coerceIn(0, (size - lines).coerceAtLeast(0))

/** What a key does to a menu. */
internal sealed interface Key {
	/** The highlight [by] lines down, or up where negative. */
	data class Move(
		val by: Int,
	) : Key

	/** To the first line, or the last. */
	data class Jump(
		val last: Boolean,
	) : Key

	/** The [line]th of the lines in sight chosen, counted from 1. */
	data class Pick(
		val line: Int,
	) : Key

	/** The highlighted line chosen. */
	data object Choose : Key

	data object Cancel : Key
}

/**
 * The key [bytes] are, as a terminal sends it: the arrows and their neighbours as escape sequences, the letters and
 * control keys that do the same, Enter, a digit; null for anything else, which does nothing. [page] is how far Page
 * Up and Page Down move.
 */
internal fun keyOf(bytes: List<Int>, page: Int): Key? =
	when (bytes) {
		listOf(ESCAPE, '['.code, 'A'.code), listOf(ESCAPE, 'O'.code, 'A'.code) -> Key.Move(-1)
		listOf(ESCAPE, '['.code, 'B'.code), listOf(ESCAPE, 'O'.code, 'B'.code) -> Key.Move(1)
		listOf(ESCAPE, '['.code, '5'.code, '~'.code) -> Key.Move(-page)
		listOf(ESCAPE, '['.code, '6'.code, '~'.code) -> Key.Move(page)
		listOf(ESCAPE, '['.code, 'H'.code), listOf(ESCAPE, '['.code, '1'.code, '~'.code) ->
			Key.Jump(last = false)
		listOf(ESCAPE, '['.code, 'F'.code), listOf(ESCAPE, '['.code, '4'.code, '~'.code) ->
			Key.Jump(last = true)
		listOf(ESCAPE) -> Key.Cancel
		else ->
			when (val byte = bytes.singleOrNull()) {
				'k'.code, CTRL_P -> Key.Move(-1)
				'j'.code, CTRL_N -> Key.Move(1)
				'g'.code -> Key.Jump(last = false)
				'G'.code -> Key.Jump(last = true)
				'\r'.code, '\n'.code -> Key.Choose
				'q'.code, CTRL_C, CTRL_D -> Key.Cancel
				in '1'.code..'9'.code -> Key.Pick(byte!! - '0'.code)
				else -> null
			}
	}

private const val ESCAPE = 0x1B
private const val CTRL_C = 0x03
private const val CTRL_D = 0x04
private const val CTRL_N = 0x0E
private const val CTRL_P = 0x10

/** [items], each drawn by [shown], with [lines] of them in sight: what a menu is of. */
internal class Menu<T>(
	private val items: List<T>,
	private val lines: Int,
	private val shown: (T) -> String,
) {
	/** How many lines of the list are in sight: fewer than [lines], for a short list. */
	val height: Int get() = minOf(lines, items.size)

	/**
	 * The lines in sight with [at] highlighted, each cut to [width] and marked where it is the one, and under them
	 * where in the list the highlight is.
	 */
	fun lines(at: Int, width: Int): List<Line> {
		val first = firstInSight(at, items.size, lines)

		return (first until first + height).map { index ->
			val mark = if (index == at) "> " else "  "

			Line((mark + shown(items[index])).take(width), index == at)
		} +
			Line(
				"${at + 1} of ${items.size} · Enter chooses, Esc cancels".take(width),
				faint = true,
			)
	}

	/** Where the highlight is after [key], from [at]; a line picked by its number is that line in sight. */
	fun moved(at: Int, key: Key): Int =
		when (key) {
			is Key.Move -> (at + key.by).coerceIn(0, items.size - 1)
			is Key.Jump ->
				if (key.last)
					items.size - 1
				else
					0
			is Key.Pick ->
				(firstInSight(at, items.size, lines) + key.line - 1).takeIf {
					key.line <= height
				} ?: at
			Key.Choose, Key.Cancel -> at
		}

	fun item(at: Int): T = items[at]
}

/** A line of a menu: its [text], and whether it is the one highlighted, or the fainter one under the list. */
internal data class Line(
	val text: String,
	val highlighted: Boolean = false,
	val faint: Boolean = false,
) {
	/** The line as a terminal is told to draw it. */
	val drawn: String
		get() =
			when {
				highlighted -> "$REVERSE$text$PLAIN"
				faint -> "$DIM$text$PLAIN"
				else -> text
			}

	private companion object {
		const val REVERSE = "\u001B[7m"
		const val DIM = "\u001B[2m"
		const val PLAIN = "\u001B[0m"
	}
}

/** There is no terminal to show a menu on, or it could not be made to read keys: [why]. */
class NoTerminal(
	val why: String,
) : Exception(why)

/**
 * One of [items] chosen by the user from a menu on the terminal, [lines] of them in sight at once, each drawn as
 * [shown] says; null where they chose none (Escape, q, Ctrl+C). [NoTerminal] where there is no terminal to choose on.
 */
fun <T> chosenFrom(items: List<T>, lines: Int = 8, shown: (T) -> String): T? {
	if (items.isEmpty())
		return null

	val terminal = File("/dev/tty")
	val menu = Menu(items, lines, shown)

	val before =
		stty(terminal, "-g")?.trim()?.takeIf { it.isNotEmpty() }
			?: throw NoTerminal("there is no terminal to choose on")

	// Put back however this ends: by a choice, by Escape, or by the process being ended while the menu is up.
	val restore =
		Thread {
			stty(terminal, before)
			runCatching { FileOutputStream(terminal).use { it.write(SHOW_CURSOR.toByteArray()) } }
		}

	Runtime.getRuntime().addShutdownHook(restore)
	try {
		if (stty(terminal, "raw", "-echo") == null)
			throw NoTerminal("the terminal cannot be made to read a key at a time")

		// How wide the terminal is, where it says: one that says nothing, or nothing a line fits in, is taken for
		// the width terminals have had since they were made of glass.
		val width =
			stty(terminal, "size")
				?.trim()
				?.split(' ')
				?.getOrNull(1)
				?.toIntOrNull()
				?.takeIf { it >= NARROWEST } ?: DEFAULT_WIDTH

		return FileInputStream(terminal).use { keys ->
			FileOutputStream(terminal).use { screen ->
				fun write(text: String) {
					screen.write(text.toByteArray())
					screen.flush()
				}

				/** The menu drawn with [at] highlighted, the cursor left at its first line. */
				fun draw(at: Int) {
					val drawn = menu.lines(at, width - 1)

					write(
						drawn.joinToString("\r\n") { CLEAR_LINE + it.drawn } +
							"\r" + "\u001B[${drawn.size - 1}A",
					)
				}

				/** The menu's lines emptied, the cursor where its first was: nothing of it is left on the screen. */
				fun erase() =
					write(
						(0..menu.height).joinToString("\r\n") { CLEAR_LINE } + "\r" +
							"\u001B[${menu.height}A" + SHOW_CURSOR,
					)

				tailrec fun choosing(at: Int): T? {
					draw(at)

					return when (val key = keyOf(read(keys), menu.height)) {
						null -> choosing(at)
						Key.Cancel -> null
						Key.Choose -> menu.item(at)
						is Key.Pick -> menu.item(menu.moved(at, key))
						else -> choosing(menu.moved(at, key))
					}
				}

				write(HIDE_CURSOR)
				try {
					choosing(0)
				} finally {
					erase()
				}
			}
		}
	} catch (failed: IOException) {
		throw NoTerminal("the terminal cannot be read (${failed.message ?: failed})")
	} finally {
		restore.run()
		Runtime.getRuntime().removeShutdownHook(restore)
	}
}

private const val CLEAR_LINE = "\u001B[2K"
private const val HIDE_CURSOR = "\u001B[?25l"
private const val SHOW_CURSOR = "\u001B[?25h"
private const val DEFAULT_WIDTH = 80
private const val NARROWEST = 8

/**
 * The bytes of one key: one byte, or, after an Escape, the sequence it begins (an arrow's arrives whole; an Escape
 * pressed alone is followed by nothing). A sequence ends at the byte that says which key it was, so keys that arrive
 * together (typed ahead, or pasted) are each read as the key they are.
 */
private fun read(keys: FileInputStream): List<Int> {
	val first = keys.read()

	if (first < 0)
		return listOf(CTRL_D)
	if (first != ESCAPE)
		return listOf(first)

	// What an arrow sends after its Escape is there within a moment; a person's next key is not.
	Thread.sleep(SEQUENCE_MILLIS)
	if (keys.available() == 0)
		return listOf(first)

	val introducer = keys.read()

	if (introducer != '['.code && introducer != 'O'.code)
		return listOf(first, introducer)

	tailrec fun rest(read: List<Int>): List<Int> =
		if (read.size >= LONGEST_SEQUENCE || keys.available() == 0 ||
			read.lastOrNull() in FINAL
		)
			read
		else
			rest(read + keys.read())

	return listOf(first, introducer) + rest(emptyList())
}

private const val SEQUENCE_MILLIS = 25L
private const val LONGEST_SEQUENCE = 8

/** The bytes one of which ends a control sequence, saying which key it was. */
private val FINAL = 0x40..0x7E

/** `stty` run on [terminal] with [words], and what it printed; null where it could not be run, or failed. */
private fun stty(terminal: File, vararg words: String): String? =
	try {
		val running =
			ProcessBuilder(listOf("stty") + words)
				.redirectInput(terminal)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start()
		val said = running.inputStream.readBytes().decodeToString()

		said.takeIf {
			running.waitFor(STTY_SECONDS, TimeUnit.SECONDS) &&
				running.exitValue() == 0
		}
	} catch (failed: IOException) {
		null
	}

private const val STTY_SECONDS = 5L
