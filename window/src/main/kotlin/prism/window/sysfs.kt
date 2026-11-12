package prism.window

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText

/*
 * Linux's graphics devices and libraries, as every distribution has them: the devices as the kernel lists them in sysfs,
 * their nodes in /dev, and the libraries by the names glvnd (every desktop's OpenGL loader now) and the drivers give
 * them. Nothing here is one distribution's: where a library is found is the system's loader's business, asked by name.
 */

/**
 * Linux's graphics devices, as sysfs under [sys] has them: each display controller on the PCI bus (bound to a driver or
 * not, which is the first thing to know of one that does not draw), and each DRM device on no PCI bus (a system on a
 * chip's). Its nodes are those in [dev], each as this user may or may not open it; NVIDIA's driver says which of its
 * own is a device's under [proc]. Each is named as the PCI ids' list ([names]) names it, where it is there.
 */
class SysfsDevices(
	private val sys: Path = Path.of("/sys"),
	private val dev: Path = Path.of("/dev"),
	private val proc: Path = Path.of("/proc"),
	private val names: PciNames = PciNames.system(),
) : GraphicsDevices {
	override fun found(): DevicesFound {
		val controllers =
			entries(sys.resolve("bus/pci/devices"))
				.filter { read(it.resolve("class"))?.startsWith("0x03") == true }
				.map(::pciDevice)
		val placed = controllers.map { it.slot }.toSet()

		// DRM devices on no PCI bus: a system on a chip's GPU.
		val others =
			entries(sys.resolve("class/drm"))
				.filter { CARD.matches(it.name) }
				.mapNotNull { card -> card.resolve("device").takeIf { it.exists() }?.toRealPath() }
				.distinct()
				.filter {
					it.name !in placed &&
						it.resolve("subsystem").let { link -> !linksTo(link, "pci") }
				}.map(::otherDevice)

		return DevicesFound.Found(controllers + others)
	}

	private fun pciDevice(device: Path): GraphicsDevice {
		val vendorId = hexOf(device.resolve("vendor")) ?: 0
		val modelId = hexOf(device.resolve("device"))
		val (vendorName, modelName) = names.of(vendorId, modelId)
		val vendor = Vendor.of(vendorId, vendorName)
		val driver = driverOf(device)

		return GraphicsDevice(
			listOfNotNull(
				vendor.name,
				modelName ?: modelId?.let {
					"device %04x".format(it)
				},
			).joinToString(" "),
			vendor,
			device.name,
			driver,
			nodesOf(device, driver),
			named = modelName != null,
		)
	}

	private fun otherDevice(device: Path): GraphicsDevice {
		val driver = driverOf(device)
		val name = driver?.name ?: device.name

		return GraphicsDevice(
			name,
			Vendor.of(0, name),
			device.name,
			driver,
			nodesOf(device, driver),
			// Its driver's name, which OpenGL's renderer says better where it runs on it.
			named = false,
		)
	}

	/** The driver bound to [device], by its link's name, and its module's version where the module says one. */
	private fun driverOf(device: Path): KernelDriver? =
		try {
			Files.readSymbolicLink(device.resolve("driver")).name.let {
				KernelDriver(it, read(sys.resolve("module/$it/version")))
			}
		} catch (unbound: IOException) {
			null
		}

	/**
	 * [device]'s DRM nodes in [dev] (`dri/card1`, `dri/renderD128`); and, for NVIDIA's driver, its control node and the
	 * device's own (`nvidia0`, by the minor number the driver gives it).
	 */
	private fun nodesOf(device: Path, driver: KernelDriver?): List<DeviceNode> {
		val drm =
			entries(device.resolve("drm"))
				.map { it.name }
				.filter { CARD.matches(it) || RENDER.matches(it) }
				.map { dev.resolve("dri").resolve(it) }

		val own =
			if (driver?.name == "nvidia")
				listOfNotNull(
					dev.resolve("nvidiactl"),
					nvidiaMinor(device.name)?.let {
						dev.resolve("nvidia$it")
					},
				)
			else
				emptyList()

		return (drm + own).filter { it.exists() }.map { DeviceNode(it, accessOf(it)) }
	}

	/** The minor number NVIDIA's driver gave the device at [slot], as it says in its own part of /proc. */
	private fun nvidiaMinor(slot: String): Int? =
		try {
			proc
				.resolve("driver/nvidia/gpus/$slot/information")
				.readLines()
				.firstOrNull { it.startsWith("Device Minor:") }
				?.substringAfter(':')
				?.trim()
				?.toIntOrNull()
		} catch (missing: IOException) {
			null
		}

	private fun accessOf(node: Path): Access =
		if (Files.isReadable(node) && Files.isWritable(node))
			Access.Open
		else
			Access.Denied(
				try {
					Files.readAttributes(node, PosixFileAttributes::class.java).group().name
				} catch (unknown: Exception) {
					null
				},
			)

	private fun linksTo(link: Path, name: String): Boolean =
		try {
			Files.readSymbolicLink(link).name == name
		} catch (unlinked: IOException) {
			false
		}

	private fun entries(folder: Path): List<Path> =
		try {
			folder.listDirectoryEntries().sortedBy { it.name }
		} catch (missing: IOException) {
			emptyList()
		}

	private fun read(file: Path): String? =
		try {
			file.readText().trim().takeIf { it.isNotEmpty() }
		} catch (missing: IOException) {
			null
		}

	private fun hexOf(file: Path): Int? = read(file)?.removePrefix("0x")?.toIntOrNull(16)

	private companion object {
		val CARD = Regex("card\\d+")
		val RENDER = Regex("renderD\\d+")
	}
}

/**
 * The names of PCI devices, as the PCI ids' list (`pci.ids`, from pciutils' hwdata) gives them, where one of [files] is
 * there: a maker's name, and a device's, its marketing name where the list has one (`GeForce RTX 4090` of
 * `AD102 [GeForce RTX 4090]`).
 */
class PciNames(
	private val files: List<Path>,
) {
	/** [vendor]'s name and [model]'s, each where the list has it. */
	fun of(vendor: Int, model: Int?): Pair<String?, String?> {
		val file = files.firstOrNull { it.isRegularFile() } ?: return null to null
		val vendorKey = "%04x ".format(vendor)
		val modelKey = model?.let { "\t%04x ".format(it) }

		return try {
			Files.newBufferedReader(file).useLines { lines ->
				val rest = lines.dropWhile { !it.startsWith(vendorKey) }.iterator()

				if (!rest.hasNext())
					return null to null

				val vendorName = rest.next().removePrefix(vendorKey).trim()

				// A maker's devices are the lines indented under it, up to the next maker's.
				val modelName =
					modelKey?.let { key ->
						rest
							.asSequence()
							.takeWhile { it.startsWith("\t") || it.startsWith("#") || it.isBlank() }
							.firstOrNull { it.startsWith(key) }
							?.removePrefix(key)
							?.trim()
							?.let { BRACKETED.find(it)?.groupValues?.get(1) ?: it }
					}

				vendorName to modelName
			}
		} catch (unreadable: IOException) {
			null to null
		}
	}

	companion object {
		private val BRACKETED = Regex("\\[(.+)]")

		/** The list where systems keep it: hwdata's, pciutils', and under each of the desktop's data folders. */
		fun system(): PciNames =
			PciNames(
				listOf(
					"/usr/share/hwdata/pci.ids",
					"/usr/share/misc/pci.ids",
					"/usr/share/pci.ids",
				).map(Path::of) +
					System
						.getenv("XDG_DATA_DIRS")
						.orEmpty()
						.split(':')
						.filter { it.isNotBlank() }
						.map { Path.of(it, "hwdata", "pci.ids") },
			)
	}
}

/**
 * Linux's libraries, as glvnd and the drivers name them: EGL's loader `libEGL.so.1` and GLX's `libGL.so.1`; NVIDIA's
 * driver drawn through `libEGL_nvidia.so.0` or `libGLX_nvidia.so.0`, and every other (Mesa's: amdgpu, i915, xe,
 * nouveau, radeon, virtio-gpu, and the rest) through `libEGL_mesa.so.0` or `libGLX_mesa.so.0`. NVIDIA's libraries are
 * of one version with its driver, and their files say which (`libEGL_nvidia.so.550.78`); Mesa's are not.
 */
object LinuxLibraries : DriverLibraries {
	override fun loader(api: GlApi): String? =
		when (api) {
			GlApi.EGL -> "libEGL.so.1"
			GlApi.GLX -> "libGL.so.1"
			else -> null
		}

	override fun of(driver: KernelDriver, api: GlApi): List<String> {
		val vendor = if (driver.name == NVIDIA) NVIDIA else "mesa"

		return when (api) {
			GlApi.EGL -> listOf("libEGL_$vendor.so.0")
			GlApi.GLX -> listOf("libGLX_$vendor.so.0")
			else -> emptyList()
		}
	}

	override fun versionOf(driver: KernelDriver, file: Path): String? =
		if (driver.name == NVIDIA)
			VERSIONED.find(file.name)?.groupValues?.get(1)
		else
			null

	private const val NVIDIA = "nvidia"
	private val VERSIONED = Regex("\\.so\\.(\\d+\\.\\d+(?:\\.\\d+)*)$")
}
