package prism.browser

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import prism.desktop.freedesktop.XdgPlaces
import prism.preferences.Kind

/**
 * Every shape of thing brought out of every kind of archive, by every program that reads that kind, from archives made
 * with an entry for each folder and without (as a zip made on macOS has none): a file at the top, a folder at the top,
 * a file in a folder, a file and a folder several folders down, an empty folder, several things at once, and names
 * with what tools read as patterns in them (`[x]`, `*`, `?`). Each must arrive as itself, by its own name, with what
 * it holds and nothing else, and nothing must fail.
 */
class EntriesTest {
	@TempDir
	lateinit var root: Path

	private val real = SystemWorld(Path.of("/"), null)

	private val seven =
		listOf(
			"7zz",
			"7z",
			"7za",
			"7zr",
		).filter(real::installed).take(1).toSet()

	private fun has(programs: Collection<String>) =
		programs.isNotEmpty() && programs.all(real::installed)

	private fun sh(script: String, folder: Path) {
		val process =
			ProcessBuilder(
				"sh",
				"-c",
				script,
			).directory(folder.toFile()).redirectErrorStream(true).start()

		val said = process.inputStream.bufferedReader().readText()

		assertEquals(0, process.waitFor(), "$script\n$said")
	}

	/**
	 * A kind of archive: its name, how one is made of what is in the folder it is run in (`$files`, a file listing
	 * every file and nothing else, for one with no folder entries), the programs of each way of reading it, and whether
	 * its names can hold `*` and `?` (an ISO image's cannot).
	 */
	private class Kind(
		val file: String,
		val withFolders: String?,
		val withoutFolders: String?,
		val makers: Set<String>,
		val readers: List<Set<String>>,
		val wild: Boolean = true,
	)

	private val kinds by lazy {
		listOf(
			Kind(
				"a.tar.gz",
				"tar -czf \"$1\" .",
				"tar --no-recursion -czf \"$1\" -T \"$2\"",
				setOf("tar", "gzip"),
				listOf(setOf("bsdtar"), setOf("tar")),
			),
			Kind(
				"a.tar",
				"tar -cf \"$1\" .",
				"tar --no-recursion -cf \"$1\" -T \"$2\"",
				setOf("tar"),
				listOf(setOf("bsdtar"), setOf("tar")),
			),
			Kind(
				"a.zip",
				"zip -qr \"$1\" .",
				"zip -qr -D \"$1\" .",
				setOf("zip"),
				listOf(setOf("unzip", "zipinfo"), seven, setOf("bsdtar")),
			),
			Kind(
				"a.7z",
				"7z a -bso0 -bsp0 \"$1\" .",
				"7z a -bso0 -bsp0 \"$1\" @\"$2\"",
				setOf("7z"),
				listOf(seven, setOf("bsdtar")),
			),
			Kind(
				"a.rar",
				"rar a -r -idq \"$1\" .",
				"rar a -idq \"$1\" @\"$2\"",
				setOf("rar"),
				listOf(setOf("unrar"), seven, setOf("bsdtar")),
			),
			Kind(
				"a.iso",
				"genisoimage -quiet -R -J -o \"$1\" .",
				null,
				setOf("genisoimage"),
				listOf(seven, setOf("bsdtar")),
				wild = false,
			),
		)
	}

	/** The files an archive is made of, in [stage]; with [wild], a name with `*` and `?` in it too. */
	private fun stage(stage: Path, wild: Boolean, folders: Boolean) {
		val files =
			listOfNotNull(
				"top.txt" to "top",
				"iCloud Photos/IMG_1.JPEG" to "one",
				"iCloud Photos/deep/deeper/IMG_2.MOV" to "two",
				"w [x]/f [1].txt" to "bracketed",
				("w [x]/odd*?.txt" to "odd").takeIf { wild },
			)

		for ((name, text) in files)
			stage.resolve(name).also { it.parent.createDirectories() }.writeText(text)
		if (folders)
			stage.resolve("empty").createDirectories()
		Files.writeString(
			stage.resolve("../files"),
			files.joinToString("\n") { it.first } + "\n",
		)
	}

	/** What is in [folder], as paths from it, a folder's with a `/` after it, and each file's text. */
	private fun contents(folder: Path): Map<String, String> =
		Files.walk(folder).use { all ->
			all
				.filter { it != folder }
				.toList()
				.associate {
					val name = folder.relativize(it).toString()

					if (Files.isDirectory(it))
						"$name/" to ""
					else
						name to it.readText()
				}
		}

	/** A shape of thing brought out: the folder of the archive it is in, the names chosen there, and what arrives. */
	private class Shape(
		val name: String,
		val within: List<String>,
		val chosen: List<String>,
		val arrives: Map<String, String>,
		val needsFolders: Boolean = false,
		/** Only for an archive whose names can hold `*` and `?`. */
		val wild: Boolean = false,
		/** Only for one whose names cannot. */
		val plain: Boolean = false,
	)

	private val shapes =
		listOf(
			Shape("a file at the top", emptyList(), listOf("top.txt"), mapOf("top.txt" to "top")),
			Shape(
				"a folder at the top",
				emptyList(),
				listOf("iCloud Photos"),
				mapOf(
					"iCloud Photos/" to "",
					"iCloud Photos/IMG_1.JPEG" to "one",
					"iCloud Photos/deep/" to "",
					"iCloud Photos/deep/deeper/" to "",
					"iCloud Photos/deep/deeper/IMG_2.MOV" to "two",
				),
			),
			Shape(
				"a file in a folder",
				listOf("iCloud Photos"),
				listOf("IMG_1.JPEG"),
				mapOf("IMG_1.JPEG" to "one"),
			),
			Shape(
				"a file several folders down",
				listOf("iCloud Photos", "deep", "deeper"),
				listOf("IMG_2.MOV"),
				mapOf("IMG_2.MOV" to "two"),
			),
			Shape(
				"a folder several folders down",
				listOf("iCloud Photos"),
				listOf("deep"),
				mapOf("deep/" to "", "deep/deeper/" to "", "deep/deeper/IMG_2.MOV" to "two"),
			),
			Shape(
				"a folder in a folder, holding only a file",
				listOf("iCloud Photos", "deep"),
				listOf("deeper"),
				mapOf("deeper/" to "", "deeper/IMG_2.MOV" to "two"),
			),
			Shape(
				"an empty folder",
				emptyList(),
				listOf("empty"),
				mapOf("empty/" to ""),
				needsFolders = true,
			),
			Shape(
				"a file and a folder at once",
				emptyList(),
				listOf("top.txt", "iCloud Photos"),
				mapOf(
					"top.txt" to "top",
					"iCloud Photos/" to "",
					"iCloud Photos/IMG_1.JPEG" to "one",
					"iCloud Photos/deep/" to "",
					"iCloud Photos/deep/deeper/" to "",
					"iCloud Photos/deep/deeper/IMG_2.MOV" to "two",
				),
			),
			Shape(
				"a file named with brackets",
				listOf("w [x]"),
				listOf("f [1].txt"),
				mapOf("f [1].txt" to "bracketed"),
			),
			Shape(
				"a file named with * and ?",
				listOf("w [x]"),
				listOf("odd*?.txt"),
				mapOf("odd*?.txt" to "odd"),
				wild = true,
			),
			Shape(
				"a folder named with brackets",
				emptyList(),
				listOf("w [x]"),
				mapOf("w [x]/" to "", "w [x]/f [1].txt" to "bracketed"),
				plain = true,
			),
			Shape(
				"a folder named with brackets, holding a name with * and ?",
				emptyList(),
				listOf("w [x]"),
				mapOf("w [x]/" to "", "w [x]/f [1].txt" to "bracketed", "w [x]/odd*?.txt" to "odd"),
				wild = true,
			),
		)

	@TestFactory
	fun everyShapeIsBroughtOutOfEveryKindByEveryProgramAsItself(): List<DynamicNode> =
		kinds
			.filter { has(it.makers) }
			.flatMap { kind ->
				listOfNotNull(
					true to kind.withFolders,
					(false to kind.withoutFolders).takeIf {
						kind.withoutFolders !=
							null
					},
				).flatMap { (folders, make) ->
					kind.readers
						.filter(::has)
						.map { programs -> Triple(folders, make!!, programs) }
				}.map { (folders, make, programs) -> kind to Triple(folders, make, programs) }
			}.map { (kind, case) ->
				val (folders, make, programs) = case
				val title = "${kind.file}, ${if (folders) "with" else "without"} folder entries, by ${programs.joinToString()}"

				dynamicContainer(
					title,
					shapes
						.filter {
							(folders || !it.needsFolders) && (kind.wild || !it.wild) &&
								(!kind.wild || !it.plain)
						}.map { shape ->
							dynamicTest(shape.name) {
								val work = Files.createTempDirectory(root, "case")
								val stage = work.resolve("stage").createDirectories()

								stage(stage, kind.wild, folders)
								sh(
									make
										.replace(
											"\"\$1\"",
											"\"../${kind.file}\"",
										).replace("\"\$2\"", "\"../files\""),
									stage,
								)

								val services =
									Services(JvmDisk(), home = work, temps = Temps(work.resolve("tmp")))
								val actions = Actions.read(listOf(Actions.SHIPPED)) { it in programs }

								fun opened(folder: Path) =
									step(
										opened(Choosing.Exploring(), folder, work, sidebarOf(XdgPlaces(), work))
											.settled(services),
										Answer.Configured(actions),
									).state

								fun BrowserState.then(input: Input) = step(this, input).settled(services)

								// The place the archive is opened in: what its own "Open" shows.
								val open =
									actions.actions.single { action ->
										action.default &&
											action.conditions.names
												.orEmpty()
												.any { it.matches(kind.file) }
									}

								val type =
									actions.places.getValue(
										((open.deed as Deed.Show).what as Showing.Place).place,
									)

								val placed = Placed(type, mapOf("archive" to work.resolve(kind.file).toString()))

								val inside =
									opened(work)
										.then(BrowserEvent.Requested(Request.Preferences))
										.let { step(it, BrowserEvent.Opened(work)).settled(services) }
										.let {
											step(
												it.copy(at = Location.Place(placed)),
												BrowserEvent.Refreshed,
											).settled(services)
										}.let { top ->
											shape.within.fold(top) { at, folder ->
												at.then(
													BrowserEvent.Activated(
														at.rows.list
															.single { it.name == folder }
															.id,
													),
												)
											}
										}

								assertNull((inside.listing as? Listing.Unreadable)?.why)

								val chosen =
									shape.chosen.map { name ->
										inside.rows.list.single { it.name == name }
									}
								val carried = inside.carrying(chosen)!!
								val out = work.resolve("out").createDirectories()

								val dropped =
									opened(
										out,
									).then(BrowserEvent.DroppedEntries(EntriesOut.of(carried)!!, DropSpot.Here))

								assertNull((dropped.notice as? Notice.Failed)?.let { "${it.doing}: ${it.why}" })
								assertEquals(shape.arrives, contents(out))
								// What the commands worked in is gone.
								assertEquals(
									false,
									work.resolve("tmp").exists() &&
										Files.list(work.resolve("tmp")).use { it.count() } > 0,
								)
							}
						},
				)
			}

	/** A kind that can be changed where it is, and the programs that change it. */
	private class Changing(
		val file: String,
		val withFolders: String,
		val withoutFolders: String,
		val makers: Set<String>,
		val programs: Set<String>,
	)

	@TestFactory
	fun everyShapeIsRemovedFromEveryKindThatCanBeChangedAndNothingElseIs(): List<DynamicNode> =
		listOf(
			Changing(
				"a.tar",
				"tar -cf a.tar .",
				"tar --no-recursion -cf a.tar -T ../files",
				setOf("tar"),
				setOf("tar"),
			),
			Changing(
				"a.zip",
				"zip -qr a.zip .",
				"zip -qr -D a.zip .",
				setOf("zip"),
				setOf("zip", "zipinfo"),
			),
			Changing("a.zip", "zip -qr a.zip .", "zip -qr -D a.zip .", setOf("zip"), seven),
			Changing(
				"a.7z",
				"7z a -bso0 -bsp0 a.7z .",
				"7z a -bso0 -bsp0 a.7z @../files",
				setOf("7z"),
				seven,
			),
			Changing(
				"a.rar",
				"rar a -r -idq a.rar .",
				"rar a -idq a.rar @../files",
				setOf("rar"),
				setOf("rar", "unrar"),
			),
		).filter { has(it.makers) && has(it.programs) }
			.flatMap { kind -> listOf(true, false).map { kind to it } }
			.map { (kind, folders) ->
				dynamicContainer(
					"${kind.file}, ${if (folders) "with" else "without"} folder entries, by ${kind.programs.joinToString()}",
					listOf(
						Triple("a folder at the top", emptyList<String>(), "iCloud Photos"),
						Triple("a folder several folders down", listOf("iCloud Photos"), "deep"),
						Triple(
							"a file several folders down",
							listOf("iCloud Photos", "deep", "deeper"),
							"IMG_2.MOV",
						),
						Triple("a file named with brackets", listOf("w [x]"), "f [1].txt"),
						Triple("a file named with * and ?", listOf("w [x]"), "odd*?.txt"),
					).map { (name, within, chosen) ->
						dynamicTest(name) {
							val work = Files.createTempDirectory(root, "case")
							val stage = work.resolve("stage").createDirectories()

							stage(stage, wild = true, folders = folders)
							sh(
								if (folders)
									kind.withFolders
								else
									kind.withoutFolders,
								stage,
							)
							Files.move(stage.resolve(kind.file), work.resolve(kind.file))

							val services = Services(JvmDisk(), home = work, temps = Temps(work.resolve("tmp")))
							val actions = Actions.read(listOf(Actions.SHIPPED)) { it in kind.programs }
							val type = actions.places.getValue("archive." + kind.file.substringAfter('.'))
							val placed = Placed(type, mapOf("archive" to work.resolve(kind.file).toString()))

							fun BrowserState.then(input: Input) = step(this, input).settled(services)

							val top =
								step(
									opened(
										Choosing.Exploring(),
										work,
										work,
										sidebarOf(XdgPlaces(), work),
									).settled(services),
									Answer.Configured(actions),
								).state.let {
									step(
										it.copy(at = Location.Place(placed)),
										BrowserEvent.Refreshed,
									).settled(services)
								}

							/** Every file the archive holds, as its place lists it. */
							fun files(state: BrowserState): Set<String> =
								(state.at as Location.Place).tree!!.entries.filterNot { it.folder }.mapTo(
									HashSet(),
								) {
									it.at.joinToString("/")
								}

							val before = files(top)

							val inside =
								within.fold(top) { at, folder ->
									at.then(
										BrowserEvent.Activated(
											at.rows.list
												.single { it.name == folder }
												.id,
										),
									)
								}

							val row = inside.rows.list.single { it.name == chosen }
							val removing = actions.actions.single { it.id == type.id + ".remove" }
							val removed =
								inside
									.then(BrowserEvent.Did(removing, inside.subjectOf(Target.Rows(listOf(row)))))
									.then(BrowserEvent.Confirmed)
							val gone = (within + chosen).joinToString("/")
							val expected = before.filterNot { it == gone || it.startsWith("$gone/") }.toSet()

							// rar takes `*` and `?` as patterns whatever is done, so it refuses, says why, and removes nothing.
							if (kind.file == "a.rar" && ('*' in chosen || '?' in chosen)) {
								val failed = removed.notice as Notice.Failed

								assert("not removed" in failed.why) { failed.why }
								assertEquals(before, files(removed.then(BrowserEvent.Dismissed)))
							} else {
								assertNull((removed.notice as? Notice.Failed)?.let { "${it.doing}: ${it.why}" })
								assertEquals(expected, files(removed))
							}
						}
					},
				)
			}
}
