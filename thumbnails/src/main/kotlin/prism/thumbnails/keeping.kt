package prism.thumbnails

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import prism.shared.Guarded
import prism.shared.Snapshot
import prism.shared.Used

/*
 * Which thumbnails are kept. The cache has a budget, in bytes and in files, and when it is over either, some are
 * removed. Which, is decided the way prism decides which folders gone to are worth keeping (prism.shared.Uses): by how
 * much each is used and how lately, with what has been used once kept apart from what is used again and again, so
 * that neither can crowd the other out.
 *
 * An example. The cache holds the thumbnails of the photographs a user looks through every week, and they open a
 * folder of five thousand pictures they have never opened, and scroll through all of it. Five thousand thumbnails are
 * made, each used once. If the cache kept whatever was used last, the five thousand would push out the pictures looked
 * at every week. If it kept whatever was used most, none of the five thousand could ever stay, and neither could
 * tomorrow's, since each has been used once and everything older has been used more. So:
 *
 * - A thumbnail shown on more than one occasion is *established*. The established are kept by how much they are used
 *   ([Used.weightAt], which halves every [Keeping.HALF_LIFE] without use: long, since a picture looked at again after
 *   a season is still one that is looked at), in all the room there is but a fifth.
 * - Everything else (a thumbnail shown once so far, and any established one there was no room for) is *on trial*,
 *   and the most lately used of those are kept, in the fifth that is theirs and whatever the established leave free.
 *
 * So what is used often stays however long ago it was last used, until enough else is used more; what is new always
 * has a place, and time to be shown again and become established; and a flood of new ones takes the trial's room and
 * nothing more.
 *
 * Size counts too. A thumbnail far larger than one of its size should be (a picture of noise, or one another program
 * wrote without compressing it) costs room in proportion, so it is weighed as that much less used, and that much
 * older: of two that are otherwise alike, the heavy one goes first.
 *
 * An occasion is not a showing: a tile scrolled past ten times in a minute was looked at once. A use within
 * [Keeping.OCCASION] of the last is the same occasion, and adds nothing.
 *
 * The policy is a function of what there is ([Keeping.removed]); what is known of each entry is held by a
 * [Catalogue], which also keeps it in a file between runs. That file is prism's own knowledge of how its thumbnails
 * are used, and nothing else: whether a thumbnail is there, and whether it is current, is only ever the cache's to say.
 */

/** How much the cache may hold: no more than so many [bytes], nor so many [files]. */
@Serializable
data class Budget(
	val bytes: Long = BYTES,
	val files: Int = FILES,
) {
	/** Whether [files] files of [bytes] bytes in all are within it. */
	fun holds(files: Int, bytes: Long): Boolean = files <= this.files && bytes <= this.bytes

	companion object {
		/**
		 * Four gigabytes, and two hundred thousand files: generous, since a thumbnail made again costs seconds and one
		 * kept costs a few dozen kilobytes. About fifty thousand photographs at the size a gallery is usually drawn.
		 */
		const val BYTES = 4L shl 30
		const val FILES = 200_000

		/** The least either may be set to: room for a folder's worth, so that a cache is never too small to be one. */
		const val LEAST_BYTES = 16L shl 20
		const val LEAST_FILES = 500
	}
}

/** What is known of an entry: how many [bytes] it takes, and how it has been [used]. */
data class Kept(
	val bytes: Long,
	val used: Used,
) {
	/** This, shown [at]: once more, unless it was shown within the same occasion. */
	fun shown(at: Instant): Kept =
		if (Duration.between(used.at, at) < Keeping.OCCASION)
			this
		else
			copy(used = Used(used.weightAt(at, Keeping.HALF_LIFE) + 1, at))

	companion object {
		/** An entry of [bytes] bytes first known of [at]: made then, or found then. */
		fun new(bytes: Long, at: Instant): Kept = Kept(bytes, Used(1.0, at))
	}
}

/** How many files there are, and how many bytes they take. */
data class Totals(
	val files: Int = 0,
	val bytes: Long = 0,
)

/** Which entries are kept, and which removed. */
object Keeping {
	/** How long a thumbnail's uses take to weigh half as much. */
	val HALF_LIFE: Duration = Duration.ofDays(60)

	/** How long after one showing another is the same occasion. */
	val OCCASION: Duration = Duration.ofHours(1)

	/** How far under its budget a cache over it is brought: so that it is not over again at the next thumbnail. */
	const val CUT_TO = 0.9

	/** How many bytes a pixel of a thumbnail is expected to take at the most: PNGs of photographs take about one. */
	private const val BYTES_A_PIXEL = 2L

	/**
	 * How heavy [entry] is for what it is, [bytes] long: 1 for a thumbnail no larger than one of its size should be,
	 * and as many times that as it is larger. A record of a failure is a few hundred bytes, and never heavy.
	 */
	fun heaviness(entry: Entry, bytes: Long): Double =
		entry.size
			?.let { bytes.toDouble() / (it.pixels.toLong() * it.pixels * BYTES_A_PIXEL) }
			?.coerceAtLeast(1.0)
			?: 1.0

	/** How much [entry] is worth keeping for its use, at [now]: what its uses weigh, less for how heavy it is. */
	fun worth(entry: Entry, kept: Kept, now: Instant): Double =
		kept.used.weightAt(now, HALF_LIFE) / heaviness(entry, kept.bytes)

	/** How old [entry]'s last use is, at [now], in seconds: longer for how heavy it is. */
	fun staleness(entry: Entry, kept: Kept, now: Instant): Double =
		Duration.between(kept.used.at, now).seconds.coerceAtLeast(1) *
			heaviness(entry, kept.bytes)

	/** How much of [room] is for what is on trial: a fifth. */
	fun trialOf(room: Budget): Budget = Budget(room.bytes / 5, room.files / 5)

	/**
	 * The entries to remove so that [entries] are within [budget], as of [now]: none, while they are. Over it, they
	 * are cut to a little under ([CUT_TO]) in whichever of bytes and files they are over, keeping the established by
	 * what they are worth and the rest by how lately they were used, as said at the head of this file.
	 */
	fun removed(entries: Map<Entry, Kept>, budget: Budget, now: Instant): List<Entry> {
		val bytes = entries.values.sumOf { it.bytes }

		if (budget.holds(entries.size, bytes))
			return emptyList()

		val room =
			Budget(
				if (bytes > budget.bytes)
					(budget.bytes * CUT_TO).toLong()
				else
					budget.bytes,
				if (entries.size > budget.files)
					(budget.files * CUT_TO).toInt()
				else
					budget.files,
			)

		val trial = trialOf(room)
		val (often, once) = entries.entries.partition { it.value.used.established }

		val established = often.sortedByDescending { worth(it.key, it.value, now) }

		val keptOften =
			fitting(
				established,
				Budget(room.bytes - trial.bytes, room.files - trial.files),
				Totals(),
			)

		val tried =
			(once + established.drop(keptOften.files))
				.sortedBy { staleness(it.key, it.value, now) }
		val keptTried = fitting(tried, room, keptOften)

		return tried.drop(keptTried.files - keptOften.files).map { it.key }
	}

	/**
	 * How much is kept once as many of [ordered], from the first, are kept as fit in [room] with what is [already]
	 * kept there: none past the first that does not fit, since each is worth less than the one before it.
	 */
	private fun fitting(
		ordered: List<Map.Entry<Entry, Kept>>,
		room: Budget,
		already: Totals,
	): Totals =
		ordered
			.asSequence()
			.runningFold(already) { kept, next ->
				Totals(
					kept.files + 1,
					kept.bytes + next.value.bytes,
				)
			}.takeWhile { room.holds(it.files, it.bytes) }
			.lastOrNull()
			?: already
}

/**
 * What prism knows of the entries of a cache: each one's size and use, by where it is in the cache, held in memory and
 * kept in [file] between runs (nowhere, where there is no file). It is told of each entry made, found, shown and gone,
 * and asked which to remove; it never looks at the disk itself.
 *
 * It is changed in place, under its lock: it is told of every thumbnail shown, a hundred a second while a gallery is
 * scrolled, and holds a couple of hundred thousand entries, which is no map to copy at each. What decides anything
 * ([Keeping.removed]) is given a copy, and is a function.
 */
class Catalogue(
	private val file: Path? = null,
) {
	private class Held {
		val entries = HashMap<Entry, Kept>()
		var bytes = 0L

		/** Whether anything has changed since the file was written. */
		var unwritten = false

		fun put(entry: Entry, kept: Kept) {
			bytes += kept.bytes - (entries.put(entry, kept)?.bytes ?: 0)
			unwritten = true
		}

		fun drop(entry: Entry) {
			entries.remove(entry)?.let {
				bytes -= it.bytes
				unwritten = true
			}
		}
	}

	private val held = Guarded(Held())

	/** How many entries there are, and how many bytes they take. */
	val totals: Totals get() = held.guarded { Totals(entries.size, bytes) }

	/** What is known of [entry]; null where nothing is. */
	fun of(entry: Entry): Kept? = held.guarded { entries[entry] }

	/** Every entry known, and what of it: a copy, as they are now. */
	fun all(): Map<Entry, Kept> = held.guarded { HashMap(entries) }

	/** What the file holds, read: what is known, from the last run. A file that is not there, or not read, is nothing known. */
	fun read() {
		val lines =
			try {
				file?.let(Files::readAllLines).orEmpty()
			} catch (missing: IOException) {
				emptyList()
			}

		held.guarded {
			for (line in lines) {
				val parts = line.split('\t', limit = 4)
				val weight = parts.getOrNull(0)?.toDoubleOrNull()
				val at = parts.getOrNull(1)?.toLongOrNull()
				val bytes = parts.getOrNull(2)?.toLongOrNull()
				val relative = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }

				if (weight != null && at != null && bytes != null && relative != null)
					put(Entry(relative), Kept(bytes, Used(weight, Instant.ofEpochSecond(at))))
			}
			unwritten = false
		}
	}

	/** What is known written to the file, where anything has changed since it last was. */
	fun write() {
		val file = file ?: return

		val lines =
			held.guarded {
				if (!unwritten)
					return

				unwritten = false
				entries.map { (entry, kept) ->
					"${kept.used.weight}\t${kept.used.at.epochSecond}\t${kept.bytes}\t${entry.relative}\n"
				}
			}

		try {
			Snapshot.write(file, listOf(ByteBuffer.wrap(lines.joinToString("").toByteArray())))
		} catch (failed: IOException) {
			// Not kept this time: it is what is known of use, and is known again from the next uses.
			held.guarded { unwritten = true }
		}
	}

	/**
	 * What is known made to be of exactly the entries [lying] in the cache: one not known is known from now, as new
	 * as its file is (so the thumbnails another program made, and a cache prism never saw, are prism's to keep as if
	 * it had made them then), or as it is [carried] from where it was known before, where the listing says; one known
	 * that is no longer there is forgotten; one whose size has changed was written again, and is what it now is. The
	 * listing takes a while, and what became known [since] it began is not forgotten for not being in it.
	 */
	fun reconcile(lying: Sequence<Pair<Lying, Kept?>>, since: Instant = Instant.MAX) {
		val there = lying.toList()
		val entries = there.mapTo(HashSet()) { it.first.entry }

		held.guarded {
			this.entries
				.filter { (entry, kept) -> entry !in entries && kept.used.at < since }
				.keys
				.forEach(::drop)
			for ((lies, carried) in there)
				found(lies, carried)
		}
	}

	/**
	 * An entry seen in the cache, [lying] as it does: made by prism just now, or come upon (another program wrote it).
	 * New where it was not known, or as it is [carried] from where it was known before (a cache moved to this folder);
	 * known as it was, at its new size, where it was written over.
	 */
	fun found(lying: Lying, carried: Kept? = null) = held.guarded { found(lying, carried) }

	private fun Held.found(lying: Lying, carried: Kept?) {
		val known = entries[lying.entry]

		if (known == null)
			put(
				lying.entry,
				carried?.copy(bytes = lying.bytes) ?: Kept.new(lying.bytes, lying.written),
			)
		else if (known.bytes != lying.bytes)
			put(lying.entry, known.copy(bytes = lying.bytes))
	}

	/** [entry] shown, [at]: used once more, where it is known and this is another occasion. */
	fun shown(entry: Entry, at: Instant) =
		held.guarded {
			entries[entry]?.let { known ->
				known.shown(at).takeIf { it != known }?.let { put(entry, it) }
			}
		}

	/** [entry] is gone from the cache. */
	fun gone(entry: Entry) = held.guarded { drop(entry) }

	/** Whether what is known of is over [budget]. */
	fun over(budget: Budget): Boolean = totals.let { !budget.holds(it.files, it.bytes) }

	/**
	 * The entries to remove for what is known to be within [budget], as of [now], forgotten here: for whoever asks to
	 * remove from the cache.
	 */
	fun removing(budget: Budget, now: Instant): List<Entry> {
		if (!over(budget))
			return emptyList()

		// Decided on a copy, outside the lock: sorting a couple of hundred thousand entries is no time to hold it.
		val removed = Keeping.removed(all(), budget, now)

		held.guarded { removed.forEach(::drop) }

		return removed
	}
}
