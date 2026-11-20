package prism.index

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import prism.shared.Guarded
import prism.shared.Published

/*
 * What the disk was heard doing, kept for a minute to be looked at: the index monitor. Every change a feed hears (the
 * eBPF probe on Linux, FSEvents on a Mac) is noted here as it is heard, whatever the index makes of it, so that a
 * change the index missed can be told from one that was never heard at all.
 *
 * Changes come in bursts (a build writes a thousand files in a second, `touch` one), so they are kept by who made them
 * and when: every change one process makes in the [ActivityLog.window] after its first is one [Burst]. A burst keeps
 * at most [ActivityLog.perBurst] changes, and only counts the rest, so one busy program cannot push everything else
 * out of the log. Bursts are kept for [ActivityLog.keep], and the changes themselves in memory of their own outside
 * the JVM's heap ([HeardRecords]), never more than a fixed amount of it.
 */

/** Which of the three things that can happen to an entry a change is: what the index monitor filters by. */
enum class ActKind(
	val label: String,
) {
	Created("Created"),
	Deleted("Deleted"),
	Moved("Moved"),
}

/** What was done to an entry, as the index monitor names it. */
enum class Act(
	val label: String,
	val kind: ActKind,
) {
	FileMade("File created", ActKind.Created),
	FolderMade("Folder created", ActKind.Created),
	LinkMade("Symlink created", ActKind.Created),

	/** A FIFO, a socket or a device node. */
	NodeMade("Special file created", ActKind.Created),

	/** A second name for a file that had one. */
	Linked("Hard link created", ActKind.Created),
	FileRemoved("File deleted", ActKind.Deleted),
	FolderRemoved("Folder deleted", ActKind.Deleted),

	/** Moved, or renamed, to where it is now; or, where the change does not say where, out of its folder. */
	Moved("Moved", ActKind.Moved),

	/** Two entries swapped (`RENAME_EXCHANGE`). */
	Swapped("Swapped", ActKind.Moved),

	/** Renamed, to or from the path it is said of: FSEvents does not say which. */
	Renamed("Renamed", ActKind.Moved),
}

/** Who made a change: a process, by its id and what it was called; or nobody the feed can say (FSEvents says none). */
sealed interface Doer {
	/** The process [pid], called [name]; null where it was gone before it could be asked. */
	data class Process(
		val pid: Int,
		val name: String?,
	) : Doer

	data object Unknown : Doer

	/** The process's name, as the monitor shows it. */
	val label: String
		get() =
			when (this) {
				is Process -> name ?: "Exited process"
				Unknown -> "Unknown process"
			}
}

/** Where a change was: an entry by its folder and its name there, or by its whole path. */
sealed interface Spot {
	/**
	 * In the [folder] the kernel names by its device and inode, the entry [name]d; or the folder itself, where the
	 * change does not say which entry (a move whose destination was not read).
	 */
	data class InFolder(
		val folder: FileId,
		val name: String?,
	) : Spot

	/** At [path], as FSEvents says it. */
	data class AtPath(
		val path: String,
	) : Spot

	/** Its path: the folder where [folders] places it, or by its device and inode where it is not known. */
	fun said(folders: (FileId) -> String?): String =
		when (this) {
			is AtPath -> path
			is InFolder -> {
				val folder = folders(folder) ?: "<${folder.dev}, inode ${folder.ino}>"

				name?.let { "${folder.trimEnd('/')}/$it" } ?: folder
			}
		}
}

/** One change heard: [at] when, what was done ([act]), and where ([spot]). */
data class Heard(
	val at: Instant,
	val act: Act,
	val spot: Spot,
)

/**
 * One process's changes heard in a [ActivityLog.window] from the first: who made them ([by]), from when, how many
 * are [kept], and how many were [seen], which is more than are kept where there were more than the log keeps of one.
 */
data class Burst(
	val id: Long,
	val by: Doer,
	val from: Instant,
	val kept: Int,
	val seen: Int,
) {
	/** Whether more were seen than are kept. */
	val overflowed: Boolean get() = seen > kept
}

/**
 * What the log holds at a moment, to be read: its bursts, and the changes of each. A copy ([ActivityLog.copy]) is
 * that moment's for good; the log itself ([ActivityLog.live]) is what it holds each time it is read.
 */
interface Activity {
	/** The bursts begun in the last [ActivityLog.keep], latest first. */
	fun bursts(): List<Burst>

	/** The changes kept of the burst [id], in the order they were heard; none of a burst no longer kept. */
	fun heardIn(id: Long): List<Heard>

	/**
	 * The bursts [text] finds, without case, among those with a change of one of [kinds]: first those whose process's
	 * name has it, then those with a change of those kinds whose path, as [folders] places a folder, has it; each
	 * latest first.
	 */
	fun found(text: String, kinds: Set<ActKind>, folders: (FileId) -> String?): List<Burst>
}

/**
 * The index monitor's log: what the disk was heard doing ([heard]), kept by who did it in bursts of [window], each of
 * at most [perBurst] changes kept, for [keep], in at most [maxBytes] of memory outside the heap. Changes are noted only
 * while [recording].
 *
 * It is written by whoever hears the disk (one thread at a time, a batch at a time) and read by whatever shows it,
 * each under its lock ([Guarded]), so that what is read is never half written. A reader that would take long (looking
 * through every path) reads a [copy], which is the log's memory copied once, and which it then has for good.
 */
class ActivityLog(
	val window: Duration = 5.seconds,
	val keep: Duration = 60.seconds,
	val perBurst: Int = 200,
	private val maxBytes: Long = DEFAULT_MAX_BYTES,
	/** What a process is called, asked once a burst, as its first change is heard. */
	private val processName: (Int) -> String? = ::processNameOf,
	/** The time, in milliseconds since the epoch. */
	private val clock: () -> Long = System::currentTimeMillis,
) {
	private val noting = Published(true)

	/** Whether changes heard are noted. Off, what was noted stays until it is too old to be kept. */
	var recording: Boolean
		get() = noting.now
		set(on) = noting.set(on)

	/** A burst as it is noted: what [Burst] is made of, its counts going up as its changes are heard. */
	private class Noting(
		val id: Long,
		val by: Doer,
		val from: Long,
	) {
		var kept = 0
		var seen = 0

		fun burst() = Burst(id, by, Instant.ofEpochMilli(from), kept, seen)
	}

	/** What the log holds: the changes' records, and the bursts they are of. Reached only under [state]'s lock. */
	private inner class State {
		val records = HeardRecords(maxBytes)

		/** Every burst kept, oldest first. */
		val bursts = ArrayDeque<Noting>()

		/** The bursts kept, by their ids. */
		val byId = HashMap<Long, Noting>()

		/** Each process's burst still being heard, by its id; [NOBODY] for changes nobody is said to have made. */
		val open = HashMap<Int, Noting>()

		var next = 0L

		/** What is too old to be kept let go: bursts begun before [keep], and the changes of those. */
		fun aged(now: Long) {
			val since = now - keep.inWholeMilliseconds

			while (bursts.firstOrNull()?.let { it.from < since } == true)
				forget(bursts.removeFirst())
			// A burst's changes are all heard within its window of its start, so none is newer than that.
			records.dropOlderThan(since - window.inWholeMilliseconds)
		}

		/** A record of the burst [id] let go for want of room: one fewer kept of it. */
		fun lost(id: Long) {
			byId[id]?.let { it.kept-- }
		}

		fun forget(burst: Noting) {
			byId.remove(burst.id)
			open.entries.removeIf { it.value === burst }
		}

		/** The burst [pid] is making at [now]: its open one, where that began within [window]; a new one, where not. */
		fun burstOf(pid: Int, now: Long): Noting {
			open[pid]?.takeIf { now - it.from < window.inWholeMilliseconds }?.let { return it }

			val by =
				if (pid == NOBODY)
					Doer.Unknown
				else
					Doer.Process(pid, processName(pid))

			// However many processes there are, only so many bursts are kept: the oldest go first.
			if (bursts.size >= MAX_BURSTS)
				forget(bursts.removeFirst())

			return Noting(next++, by, now).also {
				bursts.addLast(it)
				byId[it.id] = it
				open[pid] = it
			}
		}

		fun visible(now: Long): List<Burst> {
			val since = now - keep.inWholeMilliseconds

			return bursts.filter { it.from >= since }.asReversed().map(Noting::burst)
		}
	}

	private val state = Guarded(State())

	/** What notes a batch of changes, all heard at one moment: each by the process [pid] (null where none is said). */
	interface Hearing {
		fun inFolder(pid: Int?, act: Act, folder: FileId, name: ByteArray?)

		fun atPath(pid: Int?, act: Act, path: String)
	}

	/**
	 * The changes [hear] notes, as one batch heard now; nothing where the log is not [recording]. What [hear] does
	 * is done under the log's lock, so it should note what it has and do nothing else.
	 */
	fun heard(hear: Hearing.() -> Unit) {
		if (!recording)
			return

		val now = clock()

		state.guarded {
			aged(now)

			val hearing =
				object : Hearing {
					fun noted(pid: Int?, write: (Long) -> Unit) {
						val burst = burstOf(pid ?: NOBODY, now)

						burst.seen++
						if (burst.kept < perBurst) {
							write(burst.id)
							burst.kept++
						}
					}

					override fun inFolder(pid: Int?, act: Act, folder: FileId, name: ByteArray?) =
						noted(pid) { records.inFolder(now, it, act, folder, name) { id -> lost(id) } }

					override fun atPath(pid: Int?, act: Act, path: String) =
						noted(pid) { records.atPath(now, it, act, path) { id -> lost(id) } }
				}

			hearing.hear()
		}
	}

	/** The log as it is each time it is read: every reading takes its lock for as long as it reads. */
	val live: Activity =
		object : Activity {
			override fun bursts(): List<Burst> = state.guarded { visible(clock()) }

			override fun heardIn(id: Long): List<Heard> =
				state.guarded {
					if (id in byId)
						records.heardIn(id)
					else
						emptyList()
				}

			// Looking through every change takes long: done in a copy, so that nothing waits on it to note a change.
			override fun found(
				text: String,
				kinds: Set<ActKind>,
				folders: (FileId) -> String?,
			): List<Burst> =
				if (text.isBlank() && kinds.containsAll(ActKind.entries))
					bursts()
				else
					copy().found(text, kinds, folders)
		}

	/** What the log holds now, copied: its memory once, and its bursts, which this moment's are for good. */
	fun copy(): Activity =
		state.guarded {
			val now = clock()
			val bursts = visible(now)

			Copied(bursts, records.copied())
		}

	/** A copy of the log: [bursts], and the records of their changes. */
	private class Copied(
		private val bursts: List<Burst>,
		private val records: HeardRecords.Copy,
	) : Activity {
		private val ids = bursts.mapTo(HashSet()) { it.id }

		override fun bursts(): List<Burst> = bursts

		override fun heardIn(id: Long): List<Heard> =
			if (id in ids)
				records.heardIn(id)
			else
				emptyList()

		override fun found(
			text: String,
			kinds: Set<ActKind>,
			folders: (FileId) -> String?,
		): List<Burst> {
			val every = kinds.containsAll(ActKind.entries)

			if (text.isBlank() && every)
				return bursts

			fun named(burst: Burst) =
				text.isBlank() || burst.by.label.contains(text, ignoreCase = true)

			// Each folder placed once, however many of its changes there are.
			val placed = HashMap<FileId, String?>()
			val place = { folder: FileId -> placed.getOrPut(folder) { folders(folder) } }
			// The bursts with a change of the kinds asked for, and of those, the ones with such a change at a path
			// that has the text.
			val ofKinds =
				if (every)
					ids
				else
					records.burstsWhere { act, _ -> act.kind in kinds }

			val pathed =
				if (text.isBlank())
					ofKinds
				else
					records.burstsWhere { act, spot ->
						act.kind in kinds && spot.said(place).contains(text, ignoreCase = true)
					}
			val (byName, others) = bursts.filter { it.id in ofKinds }.partition(::named)

			return byName + others.filter { it.id in pathed }
		}
	}

	companion object {
		/** How much memory the changes kept may take, at most: some hundreds of thousands of them. */
		const val DEFAULT_MAX_BYTES = 16L shl 20

		/** How many bursts are kept at most, of however many processes. */
		private const val MAX_BURSTS = 65_536

		/** The process changes are noted as where none is said to have made them: no process has this id. */
		private const val NOBODY = -1
	}
}

/** What the process [pid] is called: the kernel's name for it on Linux, its program's file's name elsewhere. */
fun processNameOf(pid: Int): String? =
	runCatching { Files.readString(Path.of("/proc/$pid/comm")).trimEnd() }.getOrNull()
		?: ProcessHandle
			.of(pid.toLong())
			.flatMap { it.info().command() }
			.map { Path.of(it).fileName.toString() }
			.orElse(null)
