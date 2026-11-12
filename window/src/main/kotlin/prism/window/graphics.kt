package prism.window

import java.nio.file.Path
import org.lwjgl.system.Platform

/*
 * The graphics devices a computer has, and why prism's windows are, or are not, drawn on each. Drawing on a GPU takes
 * three things: a driver in the kernel bound to the device, the device's nodes open to this user, and the library
 * through which OpenGL reaches the driver (its "vendor" library, which the display's OpenGL loader picks up), of the
 * same version as the driver where the two are versioned together. Each is looked at here as the system has it, and the
 * first that is missing is what keeps a device from drawing, said in the system's own words where it has them: a
 * library that cannot be loaded is said not to be with the loader's own error.
 *
 * Where devices are found, and which library each driver draws through, is each platform's: Linux's are read from sysfs
 * (sysfs.kt); the other platforms' are not looked for yet, and say so.
 */

/** A graphics device's maker: by its PCI id, and the name it goes by. */
data class Vendor(
	val id: Int,
	val name: String,
) {
	/** Whether [text] (what OpenGL says its vendor and renderer are) names this maker, as a word of it. */
	fun named(text: String): Boolean =
		wordsOf(id, name).any {
			Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
		}

	companion object {
		/** The makers most GPUs are by, by their PCI ids, as they are usually called. */
		private val KNOWN =
			mapOf(
				0x10de to "NVIDIA",
				0x1002 to "AMD",
				0x8086 to "Intel",
				0x1af4 to "Virtio",
				0x15ad to "VMware",
				0x1234 to "QEMU",
				0x1414 to "Microsoft",
				0x1a03 to "ASPEED",
				0x102b to "Matrox",
			)

		/** The maker [id], by its usual name, or as [listed] in the PCI ids' list, or by its id where neither knows it. */
		fun of(id: Int, listed: String?): Vendor =
			Vendor(
				id,
				KNOWN[id] ?: listed ?: "Vendor %04x".format(id),
			)

		/** The words OpenGL names a maker by: AMD's GPUs are Radeons, and were ATI's; a virtual machine's draws by virgl. */
		private fun wordsOf(id: Int, name: String): List<String> =
			when (id) {
				0x1002 -> listOf("AMD", "ATI", "Radeon")
				0x1af4 -> listOf("virgl", "Venus", "Virtio")
				else -> listOf(name)
			}
	}
}

/** The driver the kernel has bound to a device: its [name] (`nvidia`, `amdgpu`, `i915`), and its [version] if it says one. */
data class KernelDriver(
	val name: String,
	val version: String?,
)

/** Whether this user may open a device's node, or which group may, where they may not. */
sealed interface Access {
	data object Open : Access

	data class Denied(
		val group: String?,
	) : Access
}

/** A device's node, which drawing with it opens: `/dev/dri/renderD128`, `/dev/nvidiactl`. */
data class DeviceNode(
	val path: Path,
	val access: Access,
) {
	/** Whether a device is drawn with it, rather than its display set up by it: a render node, or NVIDIA's own. */
	val renders: Boolean get() =
		path.fileName.toString().let {
			it.startsWith("renderD") ||
				it.startsWith("nvidia")
		}
}

/**
 * A graphics device: what it is ([name], by [vendor]), where it is ([slot]: its PCI address, or its name among the
 * system's devices), the driver the kernel has bound to it (none, where null), and its nodes.
 */
data class GraphicsDevice(
	val name: String,
	val vendor: Vendor,
	val slot: String,
	val driver: KernelDriver?,
	val nodes: List<DeviceNode>,
	/** Whether [name] is the device's own, as the system lists it, rather than its maker's and a number. */
	val named: Boolean = true,
)

/** The graphics devices the system says are there; or why they are not known. */
sealed interface DevicesFound {
	data class Found(
		val devices: List<GraphicsDevice>,
	) : DevicesFound

	data class Unknown(
		val why: String,
	) : DevicesFound
}

/** Whether a library can be loaded, as the system's loader loads it; and if not, why, in the loader's own words. */
sealed interface Loading {
	/** It loads, from [from] where the system says where. */
	data class Loads(
		val from: Path?,
	) : Loading

	data class Fails(
		val why: String,
	) : Loading
}

/** A library, whether it can be loaded, and the version of its driver it is for, where its file says. */
data class Library(
	val name: String,
	val loading: Loading,
	val version: String? = null,
)

/**
 * Why a device draws prism's windows, or does not: what OpenGL made of it, or the first of what drawing on it takes
 * that is missing.
 */
sealed interface Verdict {
	/** OpenGL draws prism's windows with it. */
	data object Drawing : Verdict

	/** The CPU was asked for, so no GPU was asked about. */
	data object CpuAsked : Verdict

	/** No driver is bound to it: the kernel has none loaded for it. */
	data object NoDriver : Verdict

	/** Its [driver] hands it to something else (a virtual machine, by `vfio-pci`), which it then draws for. */
	data class SetAside(
		val driver: String,
	) : Verdict

	/** Its [node] cannot be opened by this user. */
	data class NoAccess(
		val node: DeviceNode,
	) : Verdict

	/** The [library] its driver is drawn through cannot be loaded, for [why]. */
	data class NoLibrary(
		val library: String,
		val why: String,
	) : Verdict

	/** Its driver's [library] is of [version], and the driver in the kernel of [driver]: they only work as one. */
	data class Mismatched(
		val library: String,
		val version: String,
		val driver: String,
	) : Verdict

	/** OpenGL runs on it, but could not draw this window, for [why], which is drawn on the CPU instead. */
	data class Refused(
		val why: String,
	) : Verdict

	/** OpenGL could not be started at all, for [why]. */
	data class NoOpenGl(
		val why: String,
	) : Verdict

	/** What drawing on it takes is all there, but OpenGL draws with [renderer] instead. */
	data class Passed(
		val renderer: String,
	) : Verdict
}

/**
 * How far a device a verdict holds back got toward drawing: the further, the better it says why none draws, since what
 * stops it is all that is left in the way. A device set aside, on purpose, says least. Null where nothing holds it back:
 * it draws, or OpenGL or the user chose otherwise.
 */
val Verdict.reach: Int?
	get() =
		when (this) {
			Verdict.Drawing, Verdict.CpuAsked, is Verdict.Passed -> null
			is Verdict.SetAside -> 0
			Verdict.NoDriver -> 1
			is Verdict.NoAccess -> 2
			is Verdict.NoLibrary -> 3
			is Verdict.Mismatched -> 4
			is Verdict.NoOpenGl -> 5
			is Verdict.Refused -> 6
		}

/** Whether a verdict is something missing, which keeps a device from drawing. */
val Verdict.holdsBack: Boolean get() = reach != null

/** A device, examined: the libraries its driver draws through, as they load or do not, and its verdict. */
data class Examined(
	val device: GraphicsDevice,
	val libraries: List<Library>,
	val verdict: Verdict,
)

/** Why prism's windows are drawn as they are, in one: the first of what a report says that answers it. */
sealed interface Summary {
	/** The GPU draws them: OpenGL runs as [renderer], on [device] where it is one of those found. */
	data class OnGpu(
		val renderer: String,
		val device: GraphicsDevice?,
	) : Summary

	/** The CPU was asked for. */
	data object CpuAsked : Summary

	/** OpenGL's own loader, [library], cannot be loaded, for [why]: no GPU can be drawn with. */
	data class NoLoader(
		val library: String,
		val why: String,
	) : Summary

	/** OpenGL runs as [renderer], on the GPU, but could not draw this window, for [why]: it is drawn on the CPU. */
	data class FellBack(
		val renderer: String,
		val why: String,
	) : Summary

	/** A device that could draw them does not, for what its [examined] verdict says. */
	data class HeldBack(
		val examined: Examined,
	) : Summary

	/** OpenGL could not be started, for [why], and no device found says why. */
	data class NoOpenGl(
		val why: String,
	) : Summary

	/** OpenGL runs on the CPU, as [renderer], and no device found says why. */
	data class Software(
		val renderer: String,
	) : Summary
}

/**
 * Why prism's windows are drawn as they are: the [rendering]; OpenGL's own loader, as it loads or does not ([loader],
 * where the platform has one to look for); each device found ([devices]), examined; and where the system's loader looks
 * for libraries beyond its own folders ([searchPath]: its variable, `LD_LIBRARY_PATH` on Linux, and what it is set to,
 * where it is).
 */
data class GraphicsReport(
	val rendering: Rendering,
	val drawn: Drawn,
	val loader: Library?,
	val devices: DevicesFound,
	val examined: List<Examined>,
	val searchPath: SearchPath,
) {
	/** Why prism's windows are drawn as they are, in one. */
	val summary: Summary
		get() {
			val opengl = rendering.openGl
			// Of the devices held back, the one that got furthest toward drawing.
			val held =
				examined
					.filter {
						it.verdict.holdsBack
					}.maxByOrNull { it.verdict.reach ?: 0 }
			val loaderFails = loader?.loading as? Loading.Fails

			return when {
				rendering.asked == Drawing.Cpu -> Summary.CpuAsked
				drawn is Drawn.FellBack ->
					examined.firstOrNull { it.verdict is Verdict.Refused }?.let(Summary::HeldBack)
						?: Summary.FellBack((opengl as? OpenGl.Running)?.renderer.orEmpty(), drawn.why)
				opengl is OpenGl.Running && !opengl.software ->
					Summary.OnGpu(
						opengl.renderer,
						examined
							.firstOrNull {
								it.verdict == Verdict.Drawing
							}?.device,
					)
				loaderFails != null -> Summary.NoLoader(loader.name, loaderFails.why)
				held != null -> Summary.HeldBack(held)
				else ->
					when (opengl) {
						is OpenGl.Unavailable -> Summary.NoOpenGl(opengl.why)
						is OpenGl.Running -> Summary.Software(opengl.renderer)
						OpenGl.NotAsked -> Summary.CpuAsked
					}
			}
		}
}

/** Where the system's loader looks for libraries beyond its own folders: by [variable], set to [value] (unset, where null). */
data class SearchPath(
	val variable: String,
	val value: String?,
)

/** Where a platform's graphics devices are found. */
fun interface GraphicsDevices {
	fun found(): DevicesFound
}

/** What libraries are loaded through, on a platform. */
fun interface Libraries {
	fun load(name: String): Loading
}

/** The libraries a driver's OpenGL is reached through, by a platform's conventions: OpenGL's own loader, and each driver's. */
interface DriverLibraries {
	/** OpenGL's own loader for [api]: `libEGL.so.1`, `libGL.so.1`. */
	fun loader(api: GlApi): String?

	/** The libraries [driver]'s OpenGL is drawn through, by [api]. */
	fun of(driver: KernelDriver, api: GlApi): List<String>

	/** The version of [driver] that its library, loaded from [file], is for, where the two are versioned as one. */
	fun versionOf(driver: KernelDriver, file: Path): String?

	/** A platform whose drivers' libraries are not known: none is looked for. */
	object None : DriverLibraries {
		override fun loader(api: GlApi): String? = null

		override fun of(driver: KernelDriver, api: GlApi): List<String> = emptyList()

		override fun versionOf(driver: KernelDriver, file: Path): String? = null
	}
}

/** The platform this runs on: where its devices are found, how its libraries load, and which a driver needs. */
object Graphics {
	/** Every device, examined for [rendering]. Reads the disk and loads libraries: not for the windows' thread. */
	fun examine(
		rendering: Rendering,
		drawn: Drawn,
		devices: GraphicsDevices = platformDevices(),
		libraries: Libraries = SystemLibraries,
		needs: DriverLibraries = platformNeeds(),
		searchPath: SearchPath = platformSearchPath(),
	): GraphicsReport {
		val found = devices.found()

		val examined =
			(found as? DevicesFound.Found)?.devices.orEmpty().map { device ->
				val drawnThrough =
					device.driver
						?.let { driver ->
							needs.of(driver, rendering.api).map { name ->
								val loading = libraries.load(name)

								Library(
									name,
									loading,
									(loading as? Loading.Loads)?.from?.let {
										needs.versionOf(driver, it)
									},
								)
							}
						}.orEmpty()

				val verdict = verdictOf(device, drawnThrough, rendering, drawn)
				// A device the system does not name is named as OpenGL names it, where OpenGL runs on it.
				val renderer = (rendering.openGl as? OpenGl.Running)?.renderer
				val shown =
					if (!device.named && renderer != null &&
						(verdict == Verdict.Drawing || verdict is Verdict.Refused)
					)
						device.copy(name = modelOf(renderer), named = true)
					else
						device

				Examined(shown, drawnThrough, verdict)
			}

		return GraphicsReport(
			rendering,
			drawn,
			needs.loader(rendering.api)?.let { Library(it, libraries.load(it)) },
			found,
			examined,
			searchPath,
		)
	}

	private fun platformDevices(): GraphicsDevices =
		when (Platform.get()) {
			Platform.LINUX -> SysfsDevices()
			else ->
				GraphicsDevices {
					DevicesFound.Unknown(
						"prism does not look for graphics devices on ${Platform.get().getName()} yet",
					)
				}
		}

	private fun platformNeeds(): DriverLibraries =
		when (Platform.get()) {
			Platform.LINUX -> LinuxLibraries
			else -> DriverLibraries.None
		}

	private fun platformSearchPath(): SearchPath {
		val variable =
			when (Platform.get()) {
				Platform.MACOSX -> "DYLD_LIBRARY_PATH"
				Platform.WINDOWS -> "PATH"
				else -> "LD_LIBRARY_PATH"
			}

		return SearchPath(variable, System.getenv(variable)?.takeIf { it.isNotBlank() })
	}
}

/**
 * Why [device] does or does not draw prism's windows, its driver's [libraries] loading or not, for [rendering]: drawing,
 * where OpenGL draws on it; or the first of what it takes that is missing, in the order drawing needs them; or, all
 * being there, what OpenGL made of it.
 */
fun verdictOf(
	device: GraphicsDevice,
	libraries: List<Library>,
	rendering: Rendering,
	drawn: Drawn,
): Verdict {
	val driver = device.driver
	val opengl = rendering.openGl
	// A device is drawn with through its render nodes; one with none (an older driver's) through its others.
	val denied =
		device.nodes
			.filter { it.renders }
			.ifEmpty { device.nodes }
			.firstOrNull { it.access is Access.Denied }
			?.let(Verdict::NoAccess)

	val missing =
		libraries.firstNotNullOfOrNull { library ->
			(library.loading as? Loading.Fails)?.let { Verdict.NoLibrary(library.name, it.why) }
		}

	val mismatched =
		driver?.version?.let { running ->
			libraries.firstNotNullOfOrNull { library ->
				library.version
					?.takeIf {
						it != running
					}?.let { Verdict.Mismatched(library.name, it, running) }
			}
		}

	return when {
		rendering.asked == Drawing.Cpu -> Verdict.CpuAsked
		opengl is OpenGl.Running &&
			!opengl.software &&
			device.vendor.named("${opengl.vendor} ${opengl.renderer}") ->
			(drawn as? Drawn.FellBack)?.let { Verdict.Refused(it.why) } ?: Verdict.Drawing
		driver == null -> Verdict.NoDriver
		driver.name in SET_ASIDE -> Verdict.SetAside(driver.name)
		else ->
			denied
				?: missing
				?: mismatched
				?: (opengl as? OpenGl.Unavailable)?.let { Verdict.NoOpenGl(it.why) }
				?: Verdict.Passed((opengl as? OpenGl.Running)?.renderer.orEmpty())
	}
}

/**
 * A GPU's name, as OpenGL's renderer says it: up to what it adds after a slash or in brackets (`NVIDIA GeForce RTX 4070`
 * of `NVIDIA GeForce RTX 4070/PCIe/SSE2`, `AMD Radeon RX 7800 XT` of `AMD Radeon RX 7800 XT (radeonsi, navi32, …)`).
 */
internal fun modelOf(renderer: String): String =
	renderer
		.substringBefore('/')
		.substringBefore(" (")
		.trim()
		.ifEmpty { renderer }

/** The drivers that hand a device to something else to draw with: a virtual machine, or no one. */
private val SET_ASIDE = setOf("vfio-pci", "pci-stub", "pciback", "xen-pciback")
