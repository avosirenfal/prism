package prism.watcher

import prism.ebpf.Hook
import prism.ebpfloader.Attachment
import prism.ebpfloader.Loading
import prism.ebpfloader.ProgramMaps
import prism.ebpfloader.RecordView
import prism.ebpfloader.Refusal
import prism.ebpfloader.RingReader
import prism.ebpfloader.load
import prism.linux.Sys
import prism.linux.Wait
import prism.linux.map
import prism.platform.FileId
import prism.platform.FileKind
import prism.platform.KernelDev
import prism.probe.Change
import prism.probe.FsChange
import prism.probe.FsWatch
import prism.probe.Lost

/*
 * The watcher: the probe's object loaded from this module's resources (where the build put it), attached, and its ring
 * read into [Observed] changes, which hold nothing of the ring's memory. What is here is only what the kernel said;
 * what an index makes of it is the daemon's.
 */

/**
 * One change the kernel made to a directory's entries, as the watcher saw it: the [folder] the entry is in, or was in
 * before a move; the entry's [name] there, or after a move its new one; and [what] was done to it, which says what
 * each kind of change has to say and nothing another kind's (only a move has somewhere it went, and only an entry
 * that is still there has an inode).
 */
data class Observed(
	val folder: FileId,
	val name: EntryName,
	val what: Done,
	/** The process that did it. */
	val tgid: Int,
	val atNs: Long,
) {
	/** The change, as the probe names it. */
	val change: Change get() = what.change

	override fun toString(): String =
		"$change ${folder.dev} ${folder.ino}${(what as? Done.Moved)?.let {
			" -> ${it.to.ino}"
		}.orEmpty()}/$name"
}

/** An entry's name as its directory holds it: bytes, which need not be UTF-8, and which nothing can change. */
class EntryName(
	bytes: ByteArray,
) {
	private val bytes = bytes.copyOf()

	/** The name's bytes, copied. */
	fun bytes(): ByteArray = bytes.copyOf()

	override fun equals(other: Any?): Boolean =
		other is EntryName && bytes.contentEquals(other.bytes)

	override fun hashCode(): Int = bytes.contentHashCode()

	override fun toString(): String = bytes.decodeToString()
}

/** An entry that is there after a change: its inode, and what it is, where its mode says. */
data class Present(
	val ino: Long,
	val kind: FileKind?,
)

/** What a change did to an entry. */
sealed interface Done {
	/** The change, as the probe names it. */
	val change: Change

	/** An entry that was not there is: a file, a folder, a link, or a second name for a file that had one. */
	data class Made(
		val made: Making,
		val entry: Present,
	) : Done {
		override val change: Change get() = made.change
	}

	/** An entry is gone: found by its name, since it has nothing else left. */
	data class Removed(
		val removed: Removing,
	) : Done {
		override val change: Change get() = removed.change
	}

	/**
	 * The entry was moved [to] another folder (or the same one, renamed), where it has the change's name; or,
	 * [swapped], changed places with the entry that was there (`RENAME_EXCHANGE`).
	 */
	data class Moved(
		val to: FileId,
		val swapped: Boolean,
		val entry: Present,
	) : Done {
		override val change: Change
			get() =
				if (swapped)
					Change.Exchanged
				else
					Change.Moved
	}

	/**
	 * The entry was moved out of its folder (or, [swapped], changed places), and the record does not say where to:
	 * the kernel's own memory of where could not be read. All that is known is that the folder it was in has
	 * changed, which is still worth knowing: whoever keeps that folder reads it again.
	 */
	data class Left(
		val swapped: Boolean,
	) : Done {
		override val change: Change
			get() =
				if (swapped)
					Change.Exchanged
				else
					Change.Moved
	}

	/** What was made. */
	enum class Making(
		val change: Change,
	) {
		File(Change.FileMade),
		Directory(Change.DirectoryMade),
		Symlink(Change.SymlinkMade),
		Node(Change.NodeMade),
		Link(Change.Linked),
	}

	/** What was removed. */
	enum class Removing(
		val change: Change,
	) {
		File(Change.FileRemoved),
		Directory(Change.DirectoryRemoved),
	}

	companion object {
		/**
		 * What a record of [change] says was done: [to] is the inode of the folder a move went to, on [dev], and
		 * [entry] what the entry is now, both as the record has them (0 where it has none). Every kind of change is
		 * something done: a move the record has no destination of is one that [Left].
		 */
		internal fun of(change: Change, dev: KernelDev, to: Long, entry: Present): Done =
			when (change) {
				Change.FileMade -> Made(Making.File, entry)
				Change.DirectoryMade -> Made(Making.Directory, entry)
				Change.SymlinkMade -> Made(Making.Symlink, entry)
				Change.NodeMade -> Made(Making.Node, entry)
				Change.Linked -> Made(Making.Link, entry)
				Change.FileRemoved -> Removed(Removing.File)
				Change.DirectoryRemoved -> Removed(Removing.Directory)
				Change.Moved, Change.Exchanged ->
					if (to != 0L)
						Moved(FileId(dev, to), change == Change.Exchanged, entry)
					else
						Left(change == Change.Exchanged)
			}
	}
}

/** What starting the watcher gave: a watch, or why there is none. */
sealed interface Watching {
	data class Started(
		val watch: Watch,
	) : Watching

	data class Refused(
		val why: Refusal,
	) : Watching

	/** The build put no compiled watcher in the resources: a build without clang's BPF target. */
	data object NotBuilt : Watching

	/** Loaded and attached, but its ring could not be mapped to be read. */
	data class RingUnreadable(
		val failure: Sys.Failed,
	) : Watching
}

/**
 * The watcher, running: its hooks as the kernel took them, and its changes as they come. Loaded by this process, or
 * by another and left pinned for this one to read (pinned.kt): closing it takes the first out of the kernel, and
 * leaves the second there.
 */
class Watch internal constructor(
	private val loaded: ProgramMaps,
	/** Each hook, attached or not and why. */
	val hooks: Map<Hook, Attachment>,
	private val reader: RingReader<FsChange>,
) : AutoCloseable {
	/**
	 * The changes the kernel has made since the last read, after waiting as [wait] says for there to be any, each
	 * given to [seen]. How many records were read, including any [seen] was not given because they named nothing.
	 */
	fun await(wait: Wait, seen: (Observed) -> Unit): Sys<Int> =
		reader.await(wait) { record -> observed(record)?.let(seen) }

	/**
	 * The changes the watcher could not send because the ring was full. When this grows, the index cannot know what
	 * it missed, and reads again the directories it holds.
	 */
	fun lost(): Sys<Long> =
		loaded.counted(FsWatch.maps.lost).map {
			it.getValue(Lost.RingFull)
		}

	override fun close() {
		reader.close()
		loaded.close()
	}

	private fun observed(record: RecordView<FsChange>): Observed? {
		val name = record.text(FsChange.name).takeIf { it.isNotEmpty() } ?: return null
		val change = record.entry(FsChange.change, Change.entries) ?: return null
		val dev = KernelDev.ofEncoded(record.number(FsChange.dev).toInt())

		val entry =
			Present(
				record.number(FsChange.ino),
				FileKind.ofMode(record.number(FsChange.mode).toInt()),
			)

		return Observed(
			folder = FileId(dev, record.number(FsChange.parentIno)),
			name = EntryName(name),
			what = Done.of(change, dev, record.number(FsChange.toParentIno), entry),
			tgid = record.number(FsChange.tgid).toInt(),
			atNs = record.number(FsChange.atNs),
		)
	}
}

/** The watcher's compiled object, as the build put it in the resources, or null where it did not. */
fun watcherObject(): ByteArray? =
	Watch::class.java.getResourceAsStream("/prism/probe/fswatch.bpf.o")?.use {
		it.readBytes()
	}

/** The watcher loaded, attached and ready to read. */
fun startWatching(): Watching {
	val bytes = watcherObject() ?: return Watching.NotBuilt

	return when (val loading = load(FsWatch.program, bytes)) {
		is Loading.Refused -> Watching.Refused(loading.why)
		is Loading.Loaded ->
			when (val reader = loading.loaded.reader(FsWatch.maps.changes)) {
				is Sys.Ok ->
					Watching.Started(Watch(loading.loaded, loading.loaded.attachments, reader.value))
				is Sys.Failed -> {
					loading.loaded.close()

					Watching.RingUnreadable(reader)
				}
			}
	}
}
