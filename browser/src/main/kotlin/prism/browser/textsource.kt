package prism.browser

import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/*
 * Where a text search meets the machine (docs/text.md): its command run, given the files to look in and read as it
 * prints; and a file read, or printed by a command, to be shown beside a line found in it.
 */

/**
 * [run]'s command run by [commands] and waited for: the files it looks in written to its standard input, each ended
 * by a NUL character; each line it finds given to [found] as it prints it; and how it ended. Having found as many
 * lines as are kept ([Hits.LIMIT]), it is stopped. [watch] is told how to stop it.
 *
 * How it ended is read from what it found and said, and not from its status: `grep` and `rg` end with 1 for nothing
 * found, and `xargs` with 123 where any command it ran did, which are not failures. A command that found nothing and
 * wrote why not to standard error failed; one that found something and wrote there as well found what it did, and
 * what it wrote is a note to it (a file it could not read).
 */
fun found(
	run: TextRun,
	commands: Commands,
	watch: Watch = Watch.NONE,
	found: (FileHit) -> Unit,
): TextEnding {
	val folder = run.scope.folder
	val words =
		run.search.words(run.query, run.on, folder)
			?: return TextEnding.Failed("Its command uses a value that isn't available here.")

	val reader = run.reader
	val count = AtomicInteger(0)
	val capped = AtomicBoolean(false)
	// How to stop it, once it has started: told to whoever watches it, and used here when it has found enough.
	val stopping = AtomicReference<(() -> Unit)?>(null)

	val ran =
		commands.stream(
			Invocation(
				words,
				folder,
				mapOf(TextSearch.QUERY to run.query, "folder" to folder.toString()),
			),
			{ input ->
				val written = input.buffered()

				run.scope.paths.forEach {
					written.write(it.toString().encodeToByteArray())
					written.write(0)
				}
				written.flush()
			},
			object : Watch {
				override fun started(stop: () -> Unit) {
					stopping.set(stop)
					watch.started(stop)
				}

				override fun said(line: String, error: Boolean) = watch.said(line, error)
			},
		) { line ->
			if (!capped.get())
				reader.read(line)?.let { hit ->
					if (count.incrementAndGet() > Hits.LIMIT) {
						capped.set(true)
						stopping.get()?.invoke()
					} else {
						found(hit)
					}
				}
		}

	val wrote = ran.errors.trim()

	return when {
		capped.get() -> TextEnding.Capped
		// Stopped by whoever watched it.
		ran.status == null && ran.errors == ProcessCommands.STOPPED -> TextEnding.Stopped
		ran.status == null -> TextEnding.Failed(ran.why)
		count.get() == 0 && !ran.worked && wrote.isNotEmpty() -> TextEnding.Failed(ran.why)
		else -> TextEnding.Done(wrote.ifEmpty { null })
	}
}

/**
 * The file [ask] asks for, as it is shown: what its command prints, read as a terminal would colour it; or, where it
 * names none, the file read as text, about the line wanted where it is too long to read whole.
 */
fun previewOf(ask: PreviewAsk, commands: Commands): Preview =
	when (val words = ask.words) {
		null -> fileRead(ask.path, ask.around)
		else ->
			commands.run(Invocation(words, ask.folder)).let { ran ->
				if (ran.worked || (ran.status != null && ran.output.isNotBlank())) {
					val lines = ran.output.removeSuffix("\n").lineSequence()

					Preview.Shown(
						1,
						inkedLines(lines).take(Preview.LINES).toList(),
						whole = lines.drop(Preview.LINES).none(),
					)
				} else {
					Preview.Unshown(ran.why)
				}
			}
	}

/** How much of a file is looked at to tell whether it is text. */
private const val SNIFFED = 8192

/** [path] read as text, from its first line; or, where it is longer than is shown, from a while before line [around]. */
private fun fileRead(path: Path, around: Long?): Preview =
	try {
		when {
			Files.isDirectory(path) -> Preview.Unshown("This is a folder.")
			!textual(path) -> Preview.Unshown("This file isn't text.")
			else -> {
				// What is not UTF-8 is read as near as it can be, and not refused: a line of it found is still a line.
				val decoder =
					Charsets.UTF_8
						.newDecoder()
						.onMalformedInput(CodingErrorAction.REPLACE)
						.onUnmappableCharacter(CodingErrorAction.REPLACE)

				java.io
					.InputStreamReader(
						Files.newInputStream(path),
						decoder,
					).buffered()
					.use { reader ->
						val lines = reader.lineSequence().iterator()
						val head = lines.asSequence().take(Preview.LINES).toList()

						if (!lines.hasNext()) {
							Preview.Shown(1, head.map(::InkedLine), whole = true)
						} else {
							// Too long to read whole: read on to where the line wanted is in sight, keeping no more than is shown.
							val first = ((around ?: 1L) - Preview.BEFORE).coerceAtLeast(1L)
							val skipped = (first - 1 - head.size).coerceAtLeast(0L)
							val kept = head.drop((first - 1).coerceAtMost(head.size.toLong()).toInt())
							val rest =
								lines
									.asSequence()
									.drop(skipped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
									.take(Preview.LINES - kept.size)

							Preview.Shown(first, (kept + rest).map(::InkedLine), whole = false)
						}
					}
			}
		}
	} catch (failed: IOException) {
		Preview.Unshown(failed.message ?: failed.toString())
	}

/** Whether [path] holds text, as far as its beginning says: no NUL character is in it. */
private fun textual(path: Path): Boolean =
	Files.newInputStream(path).use { stream ->
		val start = stream.readNBytes(SNIFFED)

		start.none { it == 0.toByte() }
	}
