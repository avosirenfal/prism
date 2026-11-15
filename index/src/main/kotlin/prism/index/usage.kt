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
import kotlin.math.pow
import prism.shared.Format
import prism.shared.Loaded
import prism.shared.OnDisk
import prism.shared.Parsed
import prism.shared.Stored

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

/** How much each place has been used lately, and when each was last picked. A value: a use makes another. */
class Usage private constructor(
	private val used: Map<Where, Used>,
) {
	/** One place's use: its [weight] as of [at] (when it was last used), and when it was last picked itself. */
	data class Used(
		val weight: Double,
		val at: Instant,
		val picked: Instant?,
	) {
		/** The weight as of [now]: halved for every [HALF_LIFE] since [at]. */
		fun weightAt(now: Instant): Double {
			val since = Duration.between(at, now).toMillis().coerceAtLeast(0)

			return weight * 0.5.pow(since.toDouble() / HALF_LIFE.toMillis())
		}
	}

	/** Every place used, and how. */
	val places: Map<Where, Used> get() = used

	val size: Int get() = used.size

	/** How much [where] has been used, as of [now]: about how many times lately; 0 for a place never used. */
	fun weightOf(where: Where, now: Instant): Double = used[where]?.weightAt(now) ?: 0.0

	/** When [where] was last picked, if it was. */
	fun pickedAt(where: Where): Instant? = used[where]?.picked

	/** The places picked since [since]. */
	fun pickedSince(since: Instant): List<Where> =
		used.filterValues { it.picked != null && it.picked >= since }.keys.toList()

	/**
	 * Usage once [picked] were given to an application at [at]: a use, and a pick, of each, and a use of each folder
	 * they were picked from (once, however many were picked from it).
	 */
	fun picked(picked: List<Where>, at: Instant): Usage {
		val folders = picked.mapNotNull { it.parent }.distinct() - picked.toSet()
		val uses =
			picked.associateWith { used(it, at, picked = true) } +
				folders.associateWith { used(it, at, picked = false) }

		return Usage(used + uses)
	}

	/** [where]'s use once it is used again at [at]. */
	private fun used(where: Where, at: Instant, picked: Boolean): Used {
		val was = used[where]
		val weight = (was?.weightAt(at) ?: 0.0) + 1.0
		val latest = maxOf(at, was?.at ?: at)

		return Used(
			weight,
			latest,
			if (picked)
				maxOf(at, was?.picked ?: at)
			else
				was?.picked,
		)
	}

	/**
	 * This usage without what is too little and too old to count, as of [now]: a place used once some months ago,
	 * and not picked since [keepingPicks] ago. What is written is kept small by it.
	 */
	fun pruned(now: Instant, keepingPicks: Duration = Duration.ofDays(90)): Usage {
		val cutoff = now.minus(keepingPicks)

		return Usage(
			used.filterValues {
				it.weightAt(now) >= LEAST || (it.picked != null && it.picked >= cutoff)
			},
		)
	}

	override fun equals(other: Any?): Boolean = other is Usage && used == other.used

	override fun hashCode(): Int = used.hashCode()

	override fun toString(): String = "Usage(${used.size} places)"

	companion object {
		/** How long a use takes to count half as much. */
		val HALF_LIFE: Duration = Duration.ofDays(14)

		/** The least weight worth keeping: one use, about six half-lives ago. */
		private const val LEAST = 0.02

		/** Nothing used yet. */
		val NONE = Usage(emptyMap())

		fun of(used: Map<Where, Used>): Usage = Usage(used)
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

								Where(path) to Usage.Used(weight, at, picked)
							}

						Loaded.Read(Usage.of(used.toMap()))
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
					for ((where, used) in value.places.entries.sortedBy { it.key.key }) {
						write.writeShort(where.bytes.size)
						write.write(where.bytes)
						write.writeDouble(used.weight)
						write.writeLong(used.at.toEpochMilli())
						write.writeLong(used.picked?.toEpochMilli() ?: NEVER)
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
