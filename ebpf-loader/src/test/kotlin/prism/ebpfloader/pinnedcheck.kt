package prism.ebpfloader

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readBytes
import kotlin.system.exitProcess
import prism.ebpf.Clang
import prism.linux.Fd
import prism.linux.Wait
import prism.linux.close
import prism.linux.mount
import prism.linux.orThrow
import prism.linux.uid
import prism.linux.unmount

/*
 * A check, by hand, of the one thing the tests cannot do as one process: that a program one user (root) loaded and
 * left pinned is read by another with no privileges at all, given leave to open its maps and nothing more. It is
 * what a daemon's reading of the pinned watcher rests on (watcher/pinned.kt), tried with the tests' own program,
 * whose hook (a tracepoint) loads where the watcher's (function exits) may not.
 *
 *   pin DIR USER    as root: a BPF file system mounted at DIR, the program loaded and pinned in it, USER given its maps
 *   read DIR        as USER: its maps opened by name, this process put in its table, and what it then sends read
 *   unpin DIR       as root: the names and the mount taken away
 *
 * Run through Gradle's test classpath; docs/testing.md has the lines.
 */
fun main(args: Array<String>) {
	val entered = entered()
	val pins = Path.of(args.getOrNull(1) ?: error("pin DIR USER | read DIR | unpin DIR"))

	when (args[0]) {
		"pin" -> {
			val user = pins.fileSystem.userPrincipalLookupService.lookupPrincipalByName(args[2])

			val compiled =
				Clang.compiled(
					entered.program,
					Files.createTempDirectory("pinnedcheck"),
				)

			Files.createDirectories(pins)
			mount("prism-check", pins, "bpf", "mode=0755").orThrow()

			val loaded = (load(entered.program, compiled.readBytes()) as Loading.Loaded).loaded

			loaded.use { it.pinned(pins).orThrow() }
			for (map in entered.program.maps) {
				val view =
					Files.getFileAttributeView(
						pins.resolve(map.cName),
						PosixFileAttributeView::class.java,
					)

				view.setOwner(user)
				view.setPermissions(PosixFilePermissions.fromString("rw-------"))
			}
			println("pinned in $pins, its maps ${args[2]}'s")
		}
		"read" ->
			PinnedObject.open(entered.program, pins).orThrow().use { pinned ->
				val tgid = ProcessHandle.current().pid()

				pinned
					.put(
						entered.maps.watched,
						key = { it[Watched.tgid] = tgid },
						value = { it[Watching.since] = System.nanoTime() },
					).orThrow()

				pinned.reader(entered.maps.entries).orThrow().use { reader ->
					var seen = 0
					val deadline = System.nanoTime() + 3_000_000_000L

					repeat(3) { close(Fd(-77)) }
					while (seen == 0 && System.nanoTime() < deadline)
						reader
							.await(Wait.AtMost(100)) { record ->
								if (record.number(Entered.first).toInt() == -77)
									seen++
							}.orThrow()

					println(
						"as user ${uid()}: read $seen of this process's calls from the pinned ring; " +
							"lost ${pinned.counted(entered.maps.lost).orThrow()}",
					)
					exitProcess(if (seen > 0) 0 else 1)
				}
			}
		"unpin" -> {
			PinnedObject.unpin(entered.program, pins)
			unmount(pins).orThrow()
			println("nothing is pinned at $pins")
		}
	}
}
