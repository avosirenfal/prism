package prism.index

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.index.query.QueryText

/**
 * What the index leaves out, as the user says it: rules that name a folder by its path or entries by a query, each
 * not indexed or hidden from searches; read from what the preferences keep; applied as the disk is read, and as a
 * search walks what is held.
 */
class ExclusionsTest {
	@TempDir
	lateinit var root: Path

	private val home: Path get() = root.resolve("home")

	private fun made(vararg files: String) =
		files.forEach {
			root
				.resolve(it)
				.also { path -> path.parent.createDirectories() }
				.writeText(it)
		}

	private fun where(path: String) = Where.of(root.resolve(path))

	/** The rules [written], each of the user's own, and none of prism's (the test's folder is under one of those). */
	private fun rules(vararg written: Pair<String, Exclusion>): Exclusions =
		Exclusions.of(off = Exclusions.DEFAULTS.toSet(), own = written.toMap(), home = home)

	private fun indexer(rules: Exclusions) =
		Indexer(rules.ignore, mounts = emptyList(), asked = setOf(Where.of(root)))

	private fun indexed(rules: Exclusions): Index =
		Index.EMPTY.with(indexer(rules).index(root))

	private fun Index.names(): Set<String> =
		entries()
			.map { root.relativize(it.where.path!!).toString() }
			.filter { it.isNotEmpty() }
			.toSet()

	private fun Index.found(text: String, rules: Exclusions): Set<String> {
		val query = QueryText.read(text).query
		val context = SearchContext(home = home, hiding = rules.hiding)

		return (search(query, context) as Searched.Found)
			.results
			.map { root.relativize(it.where.path!!).toString() }
			.toSet()
	}

	private fun fit(text: String): Naming =
		(Naming.read(text, home) as Naming.Read.Fit).naming

	private fun unfit(text: String): String =
		(Naming.read(text, home) as Naming.Read.Unfit).why

	@Test
	fun aRuleIsAFolderAtAPathOrAQueryAndSaysWhyItIsNeither() {
		assertEquals(Naming.Folder(Path.of("/nix")), fit("/nix"))
		assertEquals(Naming.Folder(Path.of("/nix")), fit(" /nix/ "))
		assertEquals(Naming.Folder(home.resolve("Games")), fit("~/Games"))
		assertEquals(Naming.Folder(home), fit("~"))
		assertEquals(Naming.Folder(Path.of("/a/c")), fit("/a/b/../c"))
		// Anything else is a query: a name, a glob, a path with a glob in it.
		assertTrue(fit("build/") is Naming.Sought)
		assertTrue(fit(".o") is Naming.Sought)
		assertTrue(fit("/srv/*/cache/") is Naming.Sought)
		assertTrue(fit("is:dir ^tmp_") is Naming.Sought)
		assertEquals(fit("build/"), fit(" build/ "))

		assertEquals("Enter a path or a search", unfit("  "))
		assertEquals("The root folder can't be left out", unfit("/"))
		assertEquals("A rule can't use recent:", unfit("recent:1w"))
		assertEquals("A rule can't use new:", unfit("report new:2d"))
		assertEquals("A rule can't use mount:", unfit("mount:nfs"))
		assertEquals("A rule can't use ~work", unfit("~work/build/"))
		// An alias may come to mean something else, and the rule with it: there is none a rule knows.
		assertTrue(unfit("raws@").contains("raws@"))
		// What does not read as a query says what the language says of it.
		assertTrue(unfit("ext:").isNotEmpty())
	}

	@Test
	fun everyDefaultReadsAndNixIsLeftOutWhole() {
		val rules = Exclusions.of(home = home)

		assertEquals(Exclusions.DEFAULTS, rules.rules.map { it.written })
		assertTrue(rules.rules.all { it.builtIn && it.on && it.exclusion == Exclusion.Ignored })
		// Every one of them is a folder a scan does not go into: none is looked for after the scan.
		assertTrue(rules.ignore.sought.none)
		assertEquals(Exclusions.DEFAULTS.size, rules.ignore.directories.size)

		assertTrue(rules.ignore.ignores(Where.of(Path.of("/nix"))))
		assertTrue(Ignored.of("/nix/store") !in rules.ignore.directories)
		assertTrue(rules.ignore.ignores(Where.of(Path.of("/home/u/project/.git"))))
		assertTrue(rules.ignore.ignores(Where.of(Path.of("/home/u/.config/app/Code Cache"))))
		assertTrue(rules.ignore.ignores(Where.of(Path.of("/home/u/.yarn/cache"))))
		assertFalse(rules.ignore.ignores(Where.of(Path.of("/home/u/cache"))))
		assertEquals(Exclusions.DEFAULTS.size, rules.count(Exclusion.Ignored))
	}

	@Test
	fun theRulesAreTheDefaultsButForWhatTheUserChangedAndTheirOwn() {
		val rules =
			Exclusions.of(
				off = setOf(".git/", "no-such-default/"),
				changed = mapOf("/nix" to Exclusion.Hidden, "gone/" to Exclusion.Hidden),
				own =
					linkedMapOf(
						"~/Games" to Exclusion.Hidden,
						"build/" to Exclusion.Ignored,
						// Said already, by one of prism's own and by one above: neither is a rule twice.
						"node_modules/" to Exclusion.Hidden,
						" build/" to Exclusion.Hidden,
						// No rule at all.
						"recent:1w" to Exclusion.Ignored,
					),
				home = home,
			)

		fun rule(written: String) = rules.rules.single { it.written == written }

		assertFalse(rule(".git/").on)
		assertEquals(Exclusion.Hidden, rule("/nix").exclusion)
		assertEquals(
			listOf("~/Games" to Exclusion.Hidden, "build/" to Exclusion.Ignored),
			rules.rules.filterNot { it.builtIn }.map { it.written to it.exclusion },
		)
		assertEquals(Exclusions.DEFAULTS.size + 2, rules.rules.size)

		// What is off leaves nothing out; what is hidden is read; what the user ignores is not.
		assertFalse(rules.ignore.ignores(Where.of(Path.of("/home/u/project/.git"))))
		assertFalse(rules.ignore.ignores(Where.of(Path.of("/nix"))))
		assertTrue(rules.ignore.ignores(Where.of(Path.of("/srv/app/build"))))
		assertFalse(rules.ignore.ignores(Where.of(home.resolve("Games"))))
		assertEquals(2, rules.count(Exclusion.Hidden))

		// Adding one: refused where it does not read, or is said already.
		val named = rules.rules.map { it.names }

		assertNull(Exclusions.unfit("~/Videos", named, home))
		assertEquals(
			"There is already a rule for this",
			Exclusions.unfit("build/", named, home),
		)
		assertEquals(
			"There is already a rule for this",
			Exclusions.unfit(home.resolve("Games").toString(), named, home),
		)
		assertEquals("A rule can't use new:", Exclusions.unfit("new:1d", named, home))
	}

	@Test
	fun aQueryRuleLeavesOutWhatItFindsAndAllUnderItTheFolderItselfToo() {
		made(
			"src/main.c",
			"src/build.c",
			"build/out.o",
			"build/deep/down/x.txt",
			"app/build/app.bin",
			"app/notes/build",
			"app/keep.txt",
		)

		// The user's example: any folder named build, anywhere, and the folder itself; a file named build is not one.
		val build = indexed(rules("build/" to Exclusion.Ignored))

		assertEquals(
			setOf(
				"src",
				"src/main.c",
				"src/build.c",
				"app",
				"app/notes",
				"app/notes/build",
				"app/keep.txt",
			),
			build.names(),
		)

		// A query that is no folder's name is looked for in what was read: every `.c`, and every folder whose name
		// starts with `de`, with what is in it.
		val sought = rules(".c" to Exclusion.Ignored, "is:dir ^de" to Exclusion.Ignored)

		assertFalse(sought.ignore.sought.none)
		assertEquals(
			setOf(
				"src",
				"build",
				"build/out.o",
				"app",
				"app/build",
				"app/build/app.bin",
				"app/notes",
				"app/notes/build",
				"app/keep.txt",
			),
			indexed(sought).names(),
		)

		// A path with a glob in it: the folders it names, each with what is in it.
		assertEquals(
			setOf(
				"src",
				"src/main.c",
				"src/build.c",
				"build",
				"build/out.o",
				"build/deep",
				"build/deep/down",
				"build/deep/down/x.txt",
				"app",
				"app/keep.txt",
			),
			indexed(rules("$root/a*/*/" to Exclusion.Ignored)).names(),
		)
	}

	@Test
	fun whatAScanLeavesOutAChangeSeenLaterLeavesOutToo() {
		made("src/main.c", "src/notes.txt")

		val rules = rules(".c" to Exclusion.Ignored, "tmp_*/" to Exclusion.Ignored)
		val indexer = indexer(rules)
		val before = Index.EMPTY.with(indexer.index(root))

		assertEquals(setOf("src", "src/notes.txt"), before.names())

		// Made after the scan, as the watcher says it by name, and as a folder is reconciled.
		made("src/more.c", "src/more.txt", "src/tmp_1/x.txt")

		val byName =
			before.with(
				listOf("more.c", "more.txt", "tmp_1").mapNotNull {
					indexer.reread(where("src/$it"))
				},
			)

		assertEquals(setOf("src", "src/notes.txt", "src/more.txt"), byName.names())
		assertEquals(
			byName.names(),
			before.with(indexer.reconcile(where("src"), before)).names(),
		)
		// What a search has to ask to be read for it is what the rules leave out, by a query as by a path.
		assertTrue(indexer.leavesOut(where("src/tmp_1")))
		assertTrue(indexer.leavesOut(where("src/tmp_1/deeper")))
		assertFalse(indexer.leavesOut(where("src")))
		assertFalse(
			indexer
				.ruled(rules.ignore.settingAside(where("src/tmp_1/deeper")))
				.leavesOut(where("src/tmp_1")),
		)
	}

	@Test
	fun whatIsHiddenIsHeldAndFoundOnlyByASearchThatNamesWhereItIs() {
		made(
			"games/foo.sav",
			"games/whatever/foo.cfg",
			"games/whatever/bar.cfg",
			"games/other/foo.txt",
			"docs/foo.txt",
		)

		val rules = rules("$root/games" to Exclusion.Hidden)
		val index = indexed(rules)

		// Held, all of it.
		assertTrue("games/whatever/foo.cfg" in index.names())
		// The user's example. Nothing under it for a search that does not say where; the folder is the rule's too.
		assertEquals(setOf("docs/foo.txt"), index.found("foo", rules))
		assertEquals(emptySet<String>(), index.found("games", rules))
		// Named, everything under it can be found.
		assertEquals(
			setOf("games/foo.sav", "games/whatever/foo.cfg", "games/other/foo.txt"),
			index.found("$root/games/ foo", rules),
		)
		// A folder under it named, only what is under that.
		assertEquals(
			setOf("games/whatever/foo.cfg"),
			index.found("$root/games/whatever/ foo", rules),
		)
		// The folder itself, asked for by its path.
		assertEquals(setOf("games"), index.found("$root/games", rules))
		// What is above it does not show it: a search of `/` is not a search of what is hidden.
		assertEquals(setOf("docs/foo.txt"), index.found("$root/ foo", rules))
		assertEquals(setOf("docs/foo.txt"), index.found("/ foo", rules))
		// A path that is somewhere else does not either, and one negated says only where not to look.
		assertEquals(setOf("docs/foo.txt"), index.found("$root/docs/ foo", rules))
		assertEquals(setOf("docs/foo.txt"), index.found("foo -$root/games/whatever/", rules))
		// With no rules, as ever.
		assertEquals(4, index.found("foo", Exclusions.none(home)).size)
		// What was lately made is not what is hidden either.
		assertEquals(emptyList<Sighting>(), index.newestFiles(10, rules.hiding))
	}

	@Test
	fun hiddenByAQueryAndOneHiddenInsideAnother() {
		made(
			"a/build/x.txt",
			"a/build/cache/x.bin",
			"b/build/x.txt",
			"b/src/x.txt",
			"vault/x.txt",
			"vault/inner/x.txt",
			"vault/inner/deep/x.txt",
		)

		val rules =
			rules(
				"build/" to Exclusion.Hidden,
				"$root/vault" to Exclusion.Hidden,
				"$root/vault/inner" to Exclusion.Hidden,
				// Hidden inside what is not indexed at all: there is nothing of it to hide.
				"cache/" to Exclusion.Ignored,
				"$root/a/build/cache/deeper" to Exclusion.Hidden,
			)

		val index = indexed(rules)

		assertFalse("a/build/cache" in index.names())
		assertEquals(setOf("b/src/x.txt"), index.found("x", rules))
		// One build folder named: that one, and not the other.
		assertEquals(setOf("a/build/x.txt"), index.found("$root/a/build/ x", rules))
		// The outer named, the inner is hidden still; the inner named, what is in it, and under it.
		assertEquals(setOf("vault/x.txt"), index.found("$root/vault/ x", rules))
		assertEquals(
			setOf("vault/inner/x.txt", "vault/inner/deep/x.txt"),
			index.found("$root/vault/inner/ x", rules),
		)
		assertEquals(
			setOf("vault/inner/deep/x.txt"),
			index.found("$root/vault/inner/deep/ x", rules),
		)
	}

	@Test
	fun aTreePlacedUnderWhatIsHiddenIsHiddenWithIt() {
		made("games/a/x.txt", "docs/x.txt")

		val rules = rules("$root/games" to Exclusion.Hidden)
		val indexer = indexer(rules)
		val before = Index.EMPTY.with(indexer.index(root))

		// A folder made later is a block of its own, placed under the hidden one.
		made("games/b/x.txt")

		val index = before.with(listOfNotNull(indexer.reread(where("games/b"))))

		assertTrue("games/b/x.txt" in index.names())
		assertEquals(setOf("docs/x.txt"), index.found("x", rules))
		assertEquals(
			setOf("games/a/x.txt", "games/b/x.txt"),
			index.found("$root/games/ x", rules),
		)
		assertEquals(setOf("games/b/x.txt"), index.found("$root/games/b/ x", rules))
	}
}
