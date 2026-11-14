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
import prism.watcher.Done
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
		"manage the watcher, the BPF program that reports file changes from the kernel",
		listOf(
			Category("inspecting", listOf(ProbeC, ProbeCheck, ProbeWatch)),
			Category("installing", listOf(ProbeLoad, ProbeUnload)),
		),
	)

/** `prism probe c`: the C the build compiles. */
object ProbeC : Command(
	"c",
	"print the watcher's generated C source",
) {
	override fun run(args: Args): Outcome = Outcome(FsWatch.program.emittedOrThrow())
}

/** `prism probe check`: each hook's declaration against the running kernel's BTF. */
object ProbeCheck : Command(
	"check",
	"check the watcher's hooks against this kernel's type information (BTF)",
) {
	override fun run(args: Args): Outcome {
		val btf =
			Btf.ofRunningKernel()
				?: return Outcome("this kernel has no BTF at ${Btf.RUNNING_KERNEL}", status = 1)

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
				"every hook matches this kernel"
			else
				"$moved of ${coverage.size} hooks would be skipped: this kernel's signature for each isn't declared yet"
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
	"load the watcher and print the changes it sees (needs root), or read a pinned one (--pinned)",
) {
	private val seconds =
		option(
			"--for",
			type = ParamType.COUNT,
			help = "stop after N seconds; without it, run until killed",
		)
	private val pinned =
		flag(
			"--pinned",
			help =
				"read the watcher pinned by prism probe load instead of loading one; needs no " +
					"privileges, but can't be used while the daemon is running (a ring buffer has " +
					"one reader)",
		)
	private val at = pinsOption()

	override fun run(args: Args): Outcome {
		val watch =
			if (args[pinned])
				when (val opened = openPinnedWatcher(args[at])) {
					is Sys.Ok -> opened.value
					is Sys.Failed -> return Outcome(
						"no watcher readable by this user is pinned at ${args[at].folder}: $opened",
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
						"couldn't load the watcher: ${started.why.message}",
						1,
					)
					is Watching.RingUnreadable -> return Outcome(
						"couldn't map the watcher's ring buffer: ${started.failure}",
						1,
					)
				}

		watch.use {
			said(it.hooks).forEach(::println)

			val until = args[seconds]?.let { System.nanoTime() + it * 1_000_000_000L }

			while (until == null || System.nanoTime() < until)
				when (val read = it.await(Wait.AtMost(250), ::print)) {
					is Sys.Failed -> return Outcome("reading the ring buffer failed: $read", 1)
					is Sys.Ok -> System.out.flush()
				}

			return Outcome(
				"events lost to a full ring buffer: ${(it.lost() as? Sys.Ok)?.value ?: "unknown"}",
			)
		}
	}

	private fun print(seen: Observed) {
		val moved = (seen.what as? Done.Moved)?.let { " -> ${it.to.ino}" }.orEmpty()

		println(
			"%-16s %s %d%s/%s  pid %d".format(
				seen.change,
				seen.folder.dev,
				seen.folder.ino,
				moved,
				seen.name,
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
		help = "directory where the watcher is pinned (its own BPF file system)",
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
				"  skipped  $hook: ${attachment.mismatches.joinToString("; ")}".takeIf {
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
	"load the watcher and pin it in the kernel so the daemon can read it without privileges (needs root)",
) {
	private val user =
		option(
			"--user",
			type = ParamType.TEXT.named("NAME"),
			help = "user allowed to read it",
		)
	private val group =
		option(
			"--group",
			type = ParamType.TEXT.named("NAME"),
			help = "group allowed to read it",
		)
	private val at = pinsOption()

	override fun run(args: Args): Outcome {
		if (args[user] == null && args[group] == null)
			warn(this, "neither --user nor --group given, so only root can read it")

		return when (val pinning = pinWatcher(args[at], Readers(args[user], args[group]))) {
			is Pinning.Failed -> throw InputError(pinning.why)
			is Pinning.Pinned -> {
				val watching = pinning.hooks.count { it.value == Attachment.Attached }

				Outcome(
					(
						said(pinning.hooks) +
							"" +
							"$watching hooks attached and pinned in ${pinning.folder}. " +
							"A daemon started from now on uses them; prism probe unload removes them."
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
	"remove the watcher pinned by prism probe load (needs root)",
) {
	private val at = pinsOption()

	override fun run(args: Args): Outcome =
		when (val unpinned = unpinWatcher(args[at])) {
			is Sys.Ok -> Outcome("Nothing is pinned at ${args[at].mount} now.")
			is Sys.Failed -> throw InputError("couldn't clear ${args[at].mount}: $unpinned")
		}
}
