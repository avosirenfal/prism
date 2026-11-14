package prism.app

import kotlin.io.path.Path
import prism.cli.Args
import prism.cli.Category
import prism.cli.Command
import prism.cli.Group
import prism.cli.InputError
import prism.cli.Outcome
import prism.cli.Param
import prism.cli.ParamType
import prism.cli.Parsed
import prism.ebpf.Btf
import prism.ebpf.Hook
import prism.ebpf.emittedOrThrow
import prism.ebpf.kernelCoverage
import prism.ebpfloader.Attachment
import prism.linux.Sys
import prism.linux.Wait
import prism.probe.FsWatch
import prism.watcher.Observed
import prism.watcher.Pinning
import prism.watcher.Readers
import prism.watcher.WatcherPins
import prism.watcher.Watching
import prism.watcher.openPinnedWatcher
import prism.watcher.pinWatcher
import prism.watcher.startWatching
import prism.watcher.unpinWatcher

/** `prism probe`: the watcher, as C, against this kernel, and running. */
val ProbeGroup =
	Group(
		"probe",
		"the watcher: the BPF program that sees the kernel make and remove directories' entries",
		listOf(
			Category("looking at it", listOf(ProbeC, ProbeCheck, ProbeWatch)),
			Category("leaving it in the kernel", listOf(ProbeLoad, ProbeUnload)),
		),
	)

/** `prism probe c`: the C the build compiles. */
object ProbeC : Command(
	"c",
	"print the watcher's C, as emitted from its Kotlin declaration",
) {
	override fun run(args: Args): Outcome = Outcome(FsWatch.program.emittedOrThrow())
}

/** `prism probe check`: each hook's declaration against the running kernel's BTF. */
object ProbeCheck : Command(
	"check",
	"check every hook of the watcher against this kernel's own description of its types",
) {
	override fun run(args: Args): Outcome {
		val btf =
			Btf.ofRunningKernel()
				?: return Outcome("this kernel publishes no BTF at ${Btf.RUNNING_KERNEL}", status = 1)

		// A function is declared in each shape the kernel has had it in, and is watched where one of them is this
		// kernel's: what is said is which, or what each declared shape and this kernel differ on.
		val coverage = kernelCoverage(FsWatch.program, btf)

		val lines =
			coverage.flatMap { place ->
				when (val fits = place.fits) {
					null ->
						listOf("  moved ${place.section}") +
							place.misfits.map { (hook, differs) ->
								"          as declared${hook.callable.shape?.let { " $it" }.orEmpty()}: " +
									differs.joinToString("; ")
							}
					else -> listOf("  ok    $fits")
				}
			}

		val moved = coverage.count { it.fits == null }
		val allMatch = moved == 0
		val verdict =
			if (allMatch)
				"every hook has a shape that matches this kernel"
			else
				"$moved of ${coverage.size} hooks would be left out: this kernel's shape of each is not declared yet"
		val said = listOf("${btf.typeCount} types in this kernel's BTF") + lines + "" + verdict

		return Outcome(said.joinToString("\n"), status = if (allMatch) 0 else 1)
	}
}

/**
 * `prism probe watch`: the watcher loaded and attached, and what it sees printed as it sees it. Loading BPF needs
 * privileges (CAP_BPF and CAP_PERFMON, or root); what refused it is printed instead. With `--pinned`, the watcher
 * `prism probe load` left in the kernel is read instead, which needs none.
 */
object ProbeWatch : Command(
	"watch",
	"load the watcher and print the changes it sees (as root), or print what a pinned one sees (--pinned)",
) {
	private val seconds =
		option(
			"--for",
			type = ParamType.COUNT,
			help = "stop after this many seconds (run until killed without it)",
		)
	private val pinned =
		flag(
			"--pinned",
			help =
				"read the watcher prism probe load left pinned, which needs no privileges, rather " +
					"than load one: what the daemon reads, read here instead (a ring has one reader, " +
					"so not while the daemon runs)",
		)
	private val at = pinsOption()

	override fun run(args: Args): Outcome {
		val watch =
			if (args[pinned])
				when (val opened = openPinnedWatcher(args[at])) {
					is Sys.Ok -> opened.value
					is Sys.Failed -> return Outcome(
						"no watcher this user may read is pinned at ${args[at].folder}: $opened",
						1,
					)
				}
			else
				when (val started = startWatching()) {
					is Watching.Started -> started.watch
					is Watching.NotBuilt -> return Outcome(
						"this build has no compiled watcher (it was built without clang)",
						1,
					)
					is Watching.Refused -> return Outcome(
						"the watcher could not be loaded: ${started.why.message}",
						1,
					)
					is Watching.RingUnreadable -> return Outcome(
						"the watcher's ring could not be mapped: ${started.failure}",
						1,
					)
				}

		watch.use {
			said(it.hooks).forEach(::println)

			val until = args[seconds]?.let { System.nanoTime() + it * 1_000_000_000L }

			while (until == null || System.nanoTime() < until)
				when (val read = it.await(Wait.AtMost(250), ::print)) {
					is Sys.Failed -> return Outcome("reading the ring failed: $read", 1)
					is Sys.Ok -> System.out.flush()
				}

			return Outcome("lost to a full ring: ${(it.lost() as? Sys.Ok)?.value ?: "unknown"}")
		}
	}

	private fun print(seen: Observed) {
		val moved = seen.movedTo?.let { " -> $it" }.orEmpty()

		println(
			"%-16s %s %d%s/%s  pid %d".format(
				seen.change,
				seen.dev,
				seen.parent,
				moved,
				seen.name().decodeToString(),
				seen.tgid,
			),
		)
	}
}

/** `--at DIR`: where the watcher is pinned. */
private fun Command.pinsOption(): Param<WatcherPins> =
	option(
		"--at",
		type =
			ParamType("DIR") {
				Parsed.Value(WatcherPins(Path(it).toAbsolutePath().normalize()))
			},
		default = WatcherPins.default(),
		help = "where the watcher is pinned, in a BPF file system of its own",
		shown = "/run/prism-watcher, or \$PRISM_WATCHER",
	)

/**
 * Each hook as the kernel took it, a line each. A shape left out beside one that is watching is another kernel's,
 * and nothing to say.
 */
private fun said(hooks: Map<Hook, Attachment>): List<String> {
	val watched =
		hooks
			.filterValues {
				it == Attachment.Attached
			}.keys
			.map { it.callable.place }

	return hooks.mapNotNull { (hook, attachment) ->
		when (attachment) {
			Attachment.Attached -> "  watching $hook"
			is Attachment.Mismatched ->
				"  left out $hook: ${attachment.mismatches.joinToString("; ")}".takeIf {
					hook.callable.place !in watched
				}
			is Attachment.Refused -> "  refused  $hook: ${attachment.failure}"
		}
	}
}

/**
 * `prism probe load`: the watcher loaded, attached and left in the kernel, pinned, for a daemon without privileges to
 * read (watcher/pinned.kt, docs/running.md). It is root's to run, once: at boot by a service, or by hand before a
 * daemon is run from a terminal or a debugger. It ends at once; the watcher stays until `prism probe unload`, or
 * the machine is shut down.
 */
object ProbeLoad : Command(
	"load",
	"load the watcher and leave it in the kernel for the daemon to read without privileges (as root)",
) {
	private val user =
		option(
			"--user",
			type = ParamType.TEXT.named("NAME"),
			help = "a user who may read it: yours, to try it by hand",
		)
	private val group =
		option(
			"--group",
			type = ParamType.TEXT.named("NAME"),
			help = "a group whose members may read it: what a service gives it",
		)
	private val at = pinsOption()

	override fun run(args: Args): Outcome {
		if (args[user] == null && args[group] == null)
			warn(this, "neither --user nor --group says who may read it, so only root may")

		return when (val pinning = pinWatcher(args[at], Readers(args[user], args[group]))) {
			is Pinning.Failed -> throw InputError(pinning.why)
			is Pinning.Pinned -> {
				val watching = pinning.hooks.count { it.value == Attachment.Attached }

				Outcome(
					(
						said(pinning.hooks) +
							"" +
							"$watching hooks left watching, pinned in ${pinning.folder}. " +
							"prism probe unload takes them out; a daemon started from now reads them."
					).joinToString("\n"),
					status = if (watching > 0) 0 else 1,
				)
			}
		}
	}
}

/** `prism probe unload`: what `prism probe load` left in the kernel taken out of it. */
object ProbeUnload : Command(
	"unload",
	"take the watcher prism probe load left in the kernel out of it (as root)",
) {
	private val at = pinsOption()

	override fun run(args: Args): Outcome =
		when (val unpinned = unpinWatcher(args[at])) {
			is Sys.Ok -> Outcome("Nothing is pinned at ${args[at].mount} now.")
			is Sys.Failed -> throw InputError("${args[at].mount} could not be cleared: $unpinned")
		}
}
