package barion.index

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.zip.CRC32C
import kotlin.math.pow

/*
 * What the user has done with the picker, which a search ranks by and `recent:` asks about. It is not what is on the
 * disk, so it is not the index's: the index can be thrown away and built again from the disk, and this cannot. It is
 * kept in a file of its own beside the index's ([UsageFile]), and read with it.
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

/** What reading a usage file gave. */
sealed interface UsageRead {
	val usage: Usage

	data class Read(
		override val usage: Usage,
	) : UsageRead

	/** No file yet: nothing used. */
	data object Missing : UsageRead {
		override val usage: Usage get() = Usage.NONE
	}

	/** A file that is not a usage file, or is damaged: nothing used, and why. */
	data class Unreadable(
		val why: String,
	) : UsageRead {
		override val usage: Usage get() = Usage.NONE
	}
}

/**
 * The file a [Usage] is kept in: a header, each place's path and use, and a CRC-32C of everything before it. It is
 * small (a place for each thing picked lately and each folder picked from), so it is written whole each time, beside
 * itself and renamed over, so it is the old file or the new one and never half of either.
 */
object UsageFile {
	/** "BARIONUS": the first eight bytes of every usage file. */
	private val MAGIC = "BARIONUS".encodeToByteArray()
	private const val VERSION: Short = 1

	/** A pick never made, as the file writes it. */
	private const val NEVER = Long.MIN_VALUE

	/** Where the usage of the index kept in [index] is kept: beside it. */
	fun beside(index: Path): Path = index.resolveSibling("usage.barion")

	fun read(file: Path): UsageRead {
		val bytes =
			try {
				Files.readAllBytes(file)
			} catch (missing: NoSuchFileException) {
				return UsageRead.Missing
			} catch (failed: IOException) {
				return UsageRead.Unreadable(failed.message ?: failed.toString())
			}

		if (bytes.size < MAGIC.size + 6 || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC))
			return UsageRead.Unreadable("$file is not a usage file")

		val body = bytes.size - 4
		val crc = CRC32C().also { it.update(bytes, 0, body) }.value.toInt()
		val stored = DataInputStream(bytes.inputStream(body, 4)).readInt()

		if (crc != stored)
			return UsageRead.Unreadable("$file is damaged")

		return try {
			DataInputStream(bytes.inputStream(MAGIC.size, body - MAGIC.size)).use { read ->
				val version = read.readShort()

				if (version != VERSION)
					return UsageRead.Unreadable(
						"$file is of version $version; this reads version $VERSION",
					)

				val used =
					List(read.readInt()) {
						val path = ByteArray(read.readUnsignedShort()).also(read::readFully)
						val weight = read.readDouble()
						val at = Instant.ofEpochMilli(read.readLong())
						val picked = read.readLong().takeIf { it != NEVER }?.let(Instant::ofEpochMilli)

						Where(path) to Usage.Used(weight, at, picked)
					}

				UsageRead.Read(Usage.of(used.toMap()))
			}
		} catch (short: EOFException) {
			UsageRead.Unreadable("$file ends too soon")
		} catch (wrong: IllegalArgumentException) {
			UsageRead.Unreadable("$file holds a place that is not a path: ${wrong.message}")
		}
	}

	/** [usage] written to [file], whole. */
	fun write(file: Path, usage: Usage) {
		val out = ByteArrayOutputStream()

		DataOutputStream(out).use { write ->
			write.write(MAGIC)
			write.writeShort(VERSION.toInt())
			write.writeInt(usage.size)
			for ((where, used) in usage.places.entries.sortedBy { it.key.key }) {
				write.writeShort(where.bytes.size)
				write.write(where.bytes)
				write.writeDouble(used.weight)
				write.writeLong(used.at.toEpochMilli())
				write.writeLong(used.picked?.toEpochMilli() ?: NEVER)
			}
		}

		val body = out.toByteArray()
		val crc = CRC32C().also { it.update(body) }.value.toInt()
		val whole =
			body +
				java.nio.ByteBuffer
					.allocate(4)
					.putInt(crc)
					.array()
		val beside = file.resolveSibling("${file.fileName}.new")

		file.parent?.let(Files::createDirectories)
		Files.write(beside, whole)
		try {
			Files.move(beside, file, StandardCopyOption.ATOMIC_MOVE)
		} catch (unsupported: AtomicMoveNotSupportedException) {
			Files.move(beside, file, StandardCopyOption.REPLACE_EXISTING)
		}
	}
}
