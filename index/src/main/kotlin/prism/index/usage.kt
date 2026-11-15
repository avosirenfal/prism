package prism.index

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.zip.CRC32C
import prism.shared.Format
import prism.shared.Loaded
import prism.shared.OnDisk
import prism.shared.Parsed
import prism.shared.Stored
import prism.shared.Used
import prism.shared.Uses

/*
 * What the user has done with the picker, which a search ranks by and `recent:` asks about. It is not what is on the
 * disk, so it is not the index's: the index can be thrown away and built again from the disk, and this cannot. It is
 * kept in a file of its own ([UsageFile]), with what the user's programs keep of their state
 * (`$XDG_STATE_HOME/prism/usage.prism`), and read with the index.
 *
 * Every pick is a use of what was picked and of the folder it was picked from. A place's uses are kept as one weight
 * that halves every [Usage.HALF_LIFE] (a "frecency"): a file picked every day outweighs one picked ten times last year,
 * and a weight needs no list of every use behind it.
 */

/**
 * How much each place has been used lately ([uses]: a weight each, halving as it ages, as everything prism keeps the
 * use of is kept), and when each was last picked itself ([picks]). A value: a use makes another.
 */
class Usage private constructor(
	val uses: Uses<Where>,
	private val picks: Map<Where, Instant>,
) {
	/** Every place used, and how much. */
	val places: Map<Where, Used> get() = uses.uses

	val size: Int get() = places.size

	/** How much [where] has been used, as of [now]: about how many times lately; 0 for a place never used. */
	fun weightOf(where: Where, now: Instant): Double = uses.weightOf(where, now)

	/** When [where] was last picked, if it was. */
	fun pickedAt(where: Where): Instant? = picks[where]

	/** The places picked since [since]. */
	fun pickedSince(since: Instant): List<Where> =
		picks.filterValues { it >= since }.keys.toList()

	/**
	 * Usage once [picked] were given to an application at [at]: a use, and a pick, of each, and a use of each folder
	 * they were picked from (once, however many were picked from it).
	 */
	fun picked(picked: List<Where>, at: Instant): Usage {
		val folders = picked.mapNotNull { it.parent }.distinct() - picked.toSet()

		return Usage(
			(picked.distinct() + folders).fold(uses) { uses, where -> uses.used(where, at) },
			picks + picked.associateWith { maxOf(at, picks[it] ?: at) },
		)
	}

	/**
	 * This usage without what is too little and too old to count, as of [now]: a place used once some months ago,
	 * and not picked since [keepingPicks] ago; and, of what is left, no more than [KEPT] ([Uses.capped]). What is
	 * written is kept small by it.
	 */
	fun pruned(now: Instant, keepingPicks: Duration = Duration.ofDays(90)): Usage {
		val cutoff = now.minus(keepingPicks)

		val counted =
			Uses(
				uses.uses.filter { (where, used) ->
					used.weightAt(now) >= LEAST || picks[where]?.let { it >= cutoff } == true
				},
			).capped(KEPT, now)

		return Usage(counted, picks.filterKeys { it in counted.uses })
	}

	override fun equals(other: Any?): Boolean =
		other is Usage && uses == other.uses && picks == other.picks

	override fun hashCode(): Int = 31 * uses.hashCode() + picks.hashCode()

	override fun toString(): String = "Usage($size places)"

	companion object {
		/** How long a use takes to count half as much. */
		val HALF_LIFE: Duration = Used.HALF_LIFE

		/** The least weight worth keeping: one use, about six half-lives ago. */
		private const val LEAST = 0.02

		/** How many places' uses are kept at the most. */
		const val KEPT = 5000

		/** Nothing used yet. */
		val NONE = Usage(Uses(), emptyMap())

		/** A usage of [uses], of which [picks] were picked themselves: only what is used can have been. */
		fun of(uses: Uses<Where>, picks: Map<Where, Instant>): Usage =
			Usage(uses, picks.filterKeys { it in uses.uses })
	}
}

/**
 * The file a [Usage] is kept in: a header, each place's path and use, and a CRC-32C of everything before it. It is
 * small (a place for each thing picked lately and each folder picked from), so it is a value kept in a file
 * ([Stored]): written whole at each pick, to the file as it then is.
 */
object UsageFile {
	/** "PRISMUSE": the first eight bytes of every usage file. */
	private val MAGIC = "PRISMUSE".encodeToByteArray()
	private const val VERSION: Short = 1

	/** A pick never made, as the file writes it. */
	private const val NEVER = Long.MIN_VALUE

	/**
	 * Where the usage is kept: `$XDG_STATE_HOME/prism/usage.prism` (`~/.local/state/prism/usage.prism`), with the
	 * state programs keep that is worth keeping but is not the user's documents, where the index (a cache, made again
	 * at will) is not.
	 */
	fun default(): Path {
		val state =
			System.getenv("XDG_STATE_HOME")?.takeIf { it.startsWith("/") }?.let { Path.of(it) }
				?: Path.of(System.getProperty("user.home"), ".local", "state")

		return state.resolve("prism/usage.prism")
	}

	/**
	 * The usage in [file], held, and kept there as things are picked; what cannot be written is told [unwritten],
	 * and held all the same.
	 */
	fun open(file: Path, unwritten: (IOException) -> Unit = { throw it }): Stored<Usage> =
		Stored.of(file, FORMAT, unwritten)

	/** What [file] says, read now: for a search that keeps nothing. */
	fun read(file: Path): Loaded<Usage> = OnDisk(file, FORMAT).read()

	/** A usage as a file's bytes: one that is not a usage file, or is damaged, is nothing used, and says why. */
	val FORMAT: Format<Usage> =
		object : Format<Usage> {
			override val none: Usage get() = Usage.NONE

			override fun read(bytes: ByteArray): Parsed<Usage> {
				if (bytes.size < MAGIC.size + 6 || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC))
					return Loaded.Unreadable("it is not a usage file")

				val body = bytes.size - 4
				val crc = CRC32C().also { it.update(bytes, 0, body) }.value.toInt()
				val stored = DataInputStream(bytes.inputStream(body, 4)).readInt()

				if (crc != stored)
					return Loaded.Unreadable("it is damaged")

				return try {
					DataInputStream(bytes.inputStream(MAGIC.size, body - MAGIC.size)).use { read ->
						val version = read.readShort()

						if (version != VERSION)
							return Loaded.Unreadable(
								"it is of version $version; this reads version $VERSION",
							)

						val used =
							List(read.readInt()) {
								val path = ByteArray(read.readUnsignedShort()).also(read::readFully)
								val weight = read.readDouble()
								val at = Instant.ofEpochMilli(read.readLong())
								val picked =
									read.readLong().takeIf { it != NEVER }?.let(Instant::ofEpochMilli)

								Triple(Where(path), Used(weight, at), picked)
							}

						Loaded.Read(
							Usage.of(
								Uses(used.associate { it.first to it.second }),
								used.mapNotNull { (where, _, picked) -> picked?.let { where to it } }.toMap(),
							),
						)
					}
				} catch (short: EOFException) {
					Loaded.Unreadable("it ends too soon")
				} catch (wrong: IllegalArgumentException) {
					Loaded.Unreadable("it holds a place that is not a path: ${wrong.message}")
				}
			}

			override fun written(value: Usage): ByteArray {
				val out = ByteArrayOutputStream()

				DataOutputStream(out).use { write ->
					write.write(MAGIC)
					write.writeShort(VERSION.toInt())
					write.writeInt(value.size)
					for ((where, used) in value.uses.uses.entries
						.sortedBy { it.key.key }) {
						write.writeShort(where.bytes.size)
						write.write(where.bytes)
						write.writeDouble(used.weight)
						write.writeLong(used.at.toEpochMilli())
						write.writeLong(value.pickedAt(where)?.toEpochMilli() ?: NEVER)
					}
				}

				val body = out.toByteArray()
				val crc = CRC32C().also { it.update(body) }.value.toInt()

				return body +
					java.nio.ByteBuffer
						.allocate(4)
						.putInt(crc)
						.array()
			}
		}
}
