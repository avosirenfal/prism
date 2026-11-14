package prism.index

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteRecursively
import kotlin.io.path.moveTo
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import prism.index.query.Depth
import prism.index.query.query
import prism.linux.Dev
import prism.linux.KernelDev
import prism.linux.Mount

/**
 * What keeping the index current rests on: a folder made to agree with the disk one level deep, whatever happened in
 * it; each device known by the kernel's number for it as well as a stat's, by the mount it is under; the rules that
 * leave a place out; and the places a query looks under.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ReconcileTest {
	@TempDir
	lateinit var root: Path

	private val indexer = Indexer(mounts = emptyList())

	private fun made(vararg files: String) =
		files.forEach {
			root
				.resolve(it)
				.also { path ->
					path.parent.createDirectories()
				}.writeText(it)
		}

	private fun where(path: String) = Where.of(root.resolve(path))

	private fun Index.shape(): List<String> =
		entries().map {
			"${it.where} ${it.kind} ${it.inode}"
		}

	@Test
	fun aFolderReconciledHoldsWhatTheDiskHoldsWhateverHappenedInIt() {
		made("docs/a.txt", "docs/b.txt", "docs/same.txt", "docs/sub/c.txt")

		val before = Index.EMPTY.with(indexer.index(root))

		// One of everything a listing shows: a file gone, one made, one renamed, one renamed over another (a new inode
		// under an old name), a folder renamed (gone under its old name, with what it holds under its new one), and a
		// folder made with something in it.
		root.resolve("docs/a.txt").deleteExisting()
		made("docs/d.txt", "docs/replacement", "docs/fresh/y.txt")
		root.resolve("docs/b.txt").moveTo(root.resolve("docs/e.txt"))
		Files.move(
			root.resolve("docs/replacement"),
			root.resolve("docs/same.txt"),
			StandardCopyOption.REPLACE_EXISTING,
		)
		root.resolve("docs/sub").moveTo(root.resolve("docs/renamed"))

		val changes = indexer.reconcile(where("docs"), before)
		val after = before.with(changes)

		assertEquals(Index.EMPTY.with(indexer.index(root)).shape(), after.shape())
		// And reconciled again, there is nothing to change.
		assertEquals(emptyList<Change>(), indexer.reconcile(where("docs"), after))
	}

	@Test
	fun aFolderGoneIsAbsentAndOneNotHeldIsReadWhole() {
		made("docs/a.txt", "music/b.mp3")

		val index = Index.EMPTY.with(indexer.index(root))

		root.resolve("music").deleteRecursively()
		assertEquals(
			listOf(Change.Absent(where("music"))),
			indexer.reconcile(where("music"), index),
		)

		made("new/c.txt")

		val read = index.with(indexer.reconcile(where("new"), index))

		assertEquals(
			listOf(where("new/c.txt")),
			read.childrenOf(where("new"))!!.map { it.where },
		)
	}

	@Test
	fun theChildrenOfAFolderAreThoseInItsBlockAndThoseGrafted() {
		made("docs/a.txt")

		val index = Index.EMPTY.with(indexer.index(root))

		made("docs/b.txt")

		val grafted = index.with(indexer.reread(where("docs/b.txt"))!!)

		assertEquals(
			listOf(
				where("docs/a.txt"),
				where("docs/b.txt"),
			),
			grafted.childrenOf(where("docs"))!!.map {
				it.where
			},
		)
		assertEquals(null, grafted.childrenOf(where("docs/a.txt")))
		assertEquals(null, grafted.childrenOf(where("nowhere")))
	}

	@Test
	fun aDeviceIsKnownByTheKernelsNumberForItByTheMountItIsUnder() {
		val disk = Dev(8, 1)
		val pool = Dev(0, 50)

		val indexer =
			Indexer(
				mounts =
					listOf(
						Mount(1, KernelDev(disk), "/", Path("/"), "ext4", "/dev/vda"),
						Mount(2, KernelDev(pool), "/", Path("/home"), "btrfs", "/dev/vdb"),
					),
			)

		val top = indexer.deviceOf(disk, Where.of(Path("/srv")), null)

		// The top of a tree: under the deepest mount above it.
		assertEquals(Device(disk, KernelDev(disk), "ext4"), top)
		// The same device below: the same.
		assertEquals(top, indexer.deviceOf(disk, Where.of(Path("/srv/data")), top))

		// A mount point: mountinfo says the kernel's number, which for a btrfs subvolume is not a stat's.
		val home = indexer.deviceOf(Dev(0, 51), Where.of(Path("/home")), top)

		assertEquals(Device(Dev(0, 51), KernelDev(pool), "btrfs"), home)
		// A subvolume within it, mounted nowhere of its own: the file system above it, as the kernel sees it.
		assertEquals(
			Device(Dev(0, 52), KernelDev(pool), "btrfs"),
			indexer.deviceOf(Dev(0, 52), Where.of(Path("/home/user")), home),
		)
	}

	@Test
	fun theRulesThatLeaveOutAPlaceAreThoseOfItOrAnythingAboveIt() {
		val rules = IgnoreRules.DEFAULT

		assertEquals(
			setOf(Ignored.of(".cache")),
			rules.matching(Where.of(Path("/home/u/.cache/thumbnails"))),
		)
		assertEquals(
			setOf(Ignored.of("/nix/store")),
			rules.matching(Where.of(Path("/nix/store/abc-hello"))),
		)
		assertEquals(
			setOf(Ignored.of(".local/share/containers")),
			rules.matching(Where.of(Path("/home/u/.local/share/containers/x"))),
		)
		assertEquals(emptySet<Ignored>(), rules.matching(Where.of(Path("/home/u/docs"))))
	}

	@Test
	fun theFoldersAQueryLooksUnderAreWhereItsPathsLeadBeforeAGlob() {
		val context =
			SearchContext(
				home = Path("/home/u"),
				aliases =
					mapOf("proj" to listOf(Path("/srv/a"), Path("/srv/b"))),
			)

		assertEquals(
			listOf(Where.of(Path("/nix/store"))),
			query {
				+path("/nix/store/")
			}.places(context),
		)
		assertEquals(
			listOf(Where.of(Path("/nix/store"))),
			query {
				+path("/nix/store/*-hello/bin/")
			}.places(context),
		)
		assertEquals(
			listOf(Where.of(Path("/home/u/.cache/thumbnails"))),
			query {
				+path("~/.cache/thumbnails/")
			}.places(context),
		)
		assertEquals(
			listOf(Where.of(Path("/home/u/.cache"))),
			query {
				+path("~/.cache/thumb")
			}.places(context),
		)
		assertEquals(
			listOf(Where.of(Path("/srv/a")), Where.of(Path("/srv/b"))),
			query {
				+dir("~proj", Depth.ANY)
				+word("x")
			}.places(context),
		)
		// A relative path is anywhere, and a negated one says where not to look.
		assertEquals(emptyList<Where>(), query { +path("work/2024/") }.places(context))
		assertEquals(emptyList<Where>(), query { -path("/nix/store/") }.places(context))
	}
}
