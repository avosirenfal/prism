package barion.index

import barion.index.query.Depth
import barion.index.query.Flag
import barion.index.query.Group
import barion.index.query.QueryBuilder
import barion.index.query.RegexOn
import barion.index.query.query
import barion.linux.Dev
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.random.Random
import kotlin.time.measureTimedValue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * How fast the index is at the size of a whole desktop: a made-up tree of `-Pindex.benchmark=N` entries (folders of
 * projects, photos and documents, named as such things are), searched with each kind of term, and written to its file
 * and read back. Skipped unless asked for; what it measured is printed.
 */
class SearchBenchmark {
	@TempDir
	lateinit var state: Path

	@Test
	fun searchAnIndexTheSizeOfADesktop() {
		val wanted = System.getProperty("index.benchmark")?.toIntOrNull()

		assumeTrue(wanted != null, "-Pindex.benchmark=N runs it")

		val (index, making) =
			measureTimedValue {
				Index.of(setOf(Where.ROOT), listOf(made(wanted!!)), emptyMap())
			}

		println("made ${"%,d".format(index.size)} entries in $making")

		val searches: List<Pair<String, QueryBuilder.() -> Unit>> =
			listOf(
				"report" to { +word("report") },
				"^img_2024" to { +word("img_2024", start = true) },
				"*.jpg" to { +word("*.jpg") },
				"img_[0-9]*" to { +word("img_[0-9]*") },
				"ext:pdf" to { +ext("pdf") },
				"type:image" to { +type(Group.Image) },
				"is:dir" to { +isA(Flag.Dir) },
				"dir:photos *.jpg" to {
					+dir("photos")
					+word("*.jpg")
				},
				"dir:project:1 =readme" to {
					+dir("project*", Depth.exactly(1))
					+exactly("readme")
				},
				"/home/*/Documents/" to { +path("/home/*/Documents/") },
				"work/2024/" to { +path("work/2024/") },
				"r/^img_\\d{4}/" to { +regex("^img_\\d{4}") },
				"r/2024/p" to { +regex("2024", RegexOn.Path) },
			)

		for ((text, build) in searches) {
			val query = query(build)
			val runs =
				(1..7).map {
					measureTimedValue { (index.search(query) as Searched.Found).results.size }
				}

			println(
				"%-24s %,10d found   first %8.1f ms   best %8.1f ms".format(
					text,
					runs.first().value,
					runs.first().duration.inWholeMicroseconds / 1000.0,
					runs.minOf { it.duration }.inWholeMicroseconds / 1000.0,
				),
			)
		}

		val file = state.resolve("index.barion")
		val (_, writing) = measureTimedValue { IndexFile.write(file, index) }
		val (read, reading) = measureTimedValue { IndexFile.read(file) }

		println(
			"wrote ${"%,d".format(file.fileSize())} bytes in $writing; read back in $reading",
		)
		check((read as IndexRead.Read).index.size == index.size)
	}

	/** A tree of about [size] entries under `/`: homes of projects, photos and documents, and a system's folders. */
	private fun made(size: Int): Block {
		val random = Random(2026)
		val builder = BlockBuilder(Where.ROOT)
		val disk = Device(Dev(259, 1), "ext4")
		var count = 0

		fun add(under: Int, name: String, kind: Kind): Int =
			builder.add(under, name.encodeToByteArray(), kind, disk).also {
				count++
			}

		fun files(under: Int, many: Int, name: (Int) -> String) =
			(0 until many)
				.map(name)
				.distinct()
				.sorted()
				.forEach { add(under, it, Kind.File) }

		fun folder(under: Int, name: String, fill: (Int) -> Unit) =
			fill(add(under, name, Kind.Directory))

		add(-1, "", Kind.Directory)

		val words =
			listOf(
				"report",
				"notes",
				"draft",
				"invoice",
				"summary",
				"plan",
				"budget",
				"letter",
				"slides",
				"data",
			)

		folder(0, "home") { home ->
			(0 until 1_000_000)
				.asSequence()
				.takeWhile {
					count < size
				}.map { "user%04d".format(it) }
				.forEach { user ->
					folder(home, user) { home ->
						folder(home, "Documents") { documents ->
							files(documents, 40) {
								"${words[
									random.nextInt(
										words.size,
									),
								]}-${random.nextInt(1000)}.${listOf("pdf", "docx", "txt", "md")[it % 4]}"
							}
							folder(documents, "work") { work ->
								for (year in 2020..2026)
									folder(work, "$year") {
										files(
											it,
											15,
										) { "${words[random.nextInt(words.size)]}_${random.nextInt(100)}.pdf" }
									}
							}
						}
						folder(home, "Pictures") { pictures ->
							folder(pictures, "photos") { photos ->
								for (year in 2019..2026)
									folder(photos, "$year") {
										files(it, 60) { "IMG_$year%04d.jpg".format(random.nextInt(10000)) }
									}
							}
						}
						folder(home, "projects") { projects ->
							for (project in 0 until 6)
								folder(projects, "project-$project") { root ->
									files(root, 3) { listOf("README.md", "build.gradle.kts", "LICENSE")[it] }
									folder(root, "src") { src ->
										for (module in 0 until 4)
											folder(
												src,
												"module$module",
											) { files(it, 25) { "Class${random.nextInt(10000)}.kt" } }
									}
								}
						}
					}
				}
		}

		return builder.built()
	}
}
