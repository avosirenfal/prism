package barion.app

import barion.cli.Args
import barion.cli.Category
import barion.cli.Command
import barion.cli.Group
import barion.cli.Outcome
import barion.cli.ParamType
import barion.ebpf.Btf
import barion.ebpf.emittedOrThrow
import barion.ebpf.kernelMismatches
import barion.ebpfloader.Attachment
import barion.linux.Sys
import barion.linux.Wait
import barion.probe.FsWatch
import barion.watcher.Observed
import barion.watcher.Watching
import barion.watcher.startWatching

/** `barion probe`: the watcher, as C, against this kernel, and running. */
val ProbeGroup =
	Group(
		"probe",
		"the watcher: the BPF program that sees the kernel make and remove directories' entries",
		listOf(Category("probe", listOf(ProbeC, ProbeCheck, ProbeWatch))),
	)

/** `barion probe c`: the C the build compiles. */
object ProbeC : Command(
	"c",
	"print the watcher's C, as emitted from its Kotlin declaration",
) {
	override fun run(args: Args): Outcome = Outcome(FsWatch.program.emittedOrThrow())
}

/** `barion probe check`: each hook's declaration against the running kernel's BTF. */
object ProbeCheck : Command(
	"check",
	"check every hook of the watcher against this kernel's own description of its types",
) {
	override fun run(args: Args): Outcome {
		val btf =
			Btf.ofRunningKernel()
				?: return Outcome("this kernel publishes no BTF at ${Btf.RUNNING_KERNEL}", status = 1)

		val lines =
			FsWatch.program.hooks.map { hook ->
				val mismatches = kernelMismatches(hook, btf)

				if (mismatches.isEmpty())
					"  ok    $hook"
				else
					"  moved $hook: ${mismatches.joinToString("; ")}"
			}

		val moved = FsWatch.program.hooks.count { kernelMismatches(it, btf).isNotEmpty() }
		val allMatch = moved == 0
		val verdict =
			if (allMatch)
				"every hook matches this kernel"
			else
				"$moved hooks would be left out"
		val said = listOf("${btf.typeCount} types in this kernel's BTF") + lines + "" + verdict

		return Outcome(said.joinToString("\n"), status = if (allMatch) 0 else 1)
	}
}

/**
 * `barion probe watch`: the watcher loaded and attached, and what it sees printed as it sees it. Loading BPF needs
 * privileges (CAP_BPF and CAP_PERFMON, or root); what refused it is printed instead.
 */
object ProbeWatch : Command(
	"watch",
	"load the watcher and print the changes it sees (needs CAP_BPF and CAP_PERFMON)",
) {
	private val seconds =
		option(
			"--for",
			type = ParamType.COUNT,
			help = "stop after this many seconds (run until killed without it)",
		)

	override fun run(args: Args): Outcome {
		val watch =
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
			for ((hook, attachment) in it.hooks)
				println(
					when (attachment) {
						Attachment.Attached -> "  watching $hook"
						is Attachment.Mismatched ->
							"  left out $hook: " +
								attachment.mismatches.joinToString("; ")
						is Attachment.Refused -> "  refused  $hook: ${attachment.failure}"
					},
				)

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
