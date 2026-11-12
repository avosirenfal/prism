package prism.window

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lwjgl.system.Platform

class GraphicsTest {
	@TempDir
	lateinit var root: Path

	private val wayland =
		Rendering(Video.Wayland, Drawing.Auto, OpenGl.Running(LLVMPIPE, "Mesa", "4.5"))

	@Test
	fun sysfsListsEachDisplayControllerWithItsDriverNodesAndName() {
		val found = laptop().found()

		assertEquals(
			DevicesFound.Found(
				listOf(
					GraphicsDevice(
						"Intel device 46a6",
						Vendor(0x8086, "Intel"),
						"0000:00:02.0",
						null,
						emptyList(),
					),
					GraphicsDevice(
						"NVIDIA GeForce RTX 4070",
						Vendor(0x10de, "NVIDIA"),
						"0000:01:00.0",
						KernelDriver("nvidia", "550.78"),
						listOf("dri/card1", "dri/renderD128", "nvidiactl", "nvidia0").map {
							DeviceNode(root.resolve("dev/$it"), Access.Open)
						},
					),
				),
			),
			found,
		)
	}

	@Test
	fun aDriverWhoseOpenGlLibraryCannotBeLoadedSaysWhyInTheLoadersWords() {
		val report = examine(wayland, fails = mapOf("libEGL_nvidia.so.0" to NOT_FOUND))
		val nvidia = report.examined.single { it.device.vendor.id == 0x10de }

		assertEquals(Verdict.NoLibrary("libEGL_nvidia.so.0", NOT_FOUND), nvidia.verdict)
		assertEquals(Summary.HeldBack(nvidia), report.summary)
		// The Intel GPU has no driver bound, which is said of it, but the NVIDIA one's library is why.
		assertEquals(
			Verdict.NoDriver,
			report.examined
				.single {
					it.device.vendor.id == 0x8086
				}.verdict,
		)
	}

	@Test
	fun theLibrariesForXAreGlxs() {
		val x11 = wayland.copy(video = Video.X11)
		val report = examine(x11, fails = mapOf("libGLX_nvidia.so.0" to NOT_FOUND))

		assertEquals("libGL.so.1", report.loader?.name)
		assertEquals(
			Verdict.NoLibrary("libGLX_nvidia.so.0", NOT_FOUND),
			report.examined.single { it.device.vendor.id == 0x10de }.verdict,
		)
	}

	@Test
	fun nvidiasLibrariesOfAnotherVersionThanItsDriverAreSaidToBe() {
		val report =
			examine(
				wayland,
				from =
					mapOf("libEGL_nvidia.so.0" to Path.of("/opt/nvidia/lib/libEGL_nvidia.so.545.29.06")),
			)

		val nvidia = report.examined.single { it.device.vendor.id == 0x10de }

		assertEquals("545.29.06", nvidia.libraries.single().version)
		assertEquals(
			Verdict.Mismatched("libEGL_nvidia.so.0", "545.29.06", "550.78"),
			nvidia.verdict,
		)
	}

	@Test
	fun theDeviceOpenGlRunsOnDraws() {
		val nvidia =
			wayland.copy(
				openGl =
					OpenGl.Running(
						"NVIDIA GeForce RTX 4070/PCIe/SSE2",
						"NVIDIA Corporation",
						"4.6",
					),
			)

		val report =
			examine(
				nvidia,
				from =
					mapOf("libEGL_nvidia.so.0" to Path.of("/usr/lib/libEGL_nvidia.so.550.78")),
			)

		val drawing = report.examined.single { it.device.vendor.id == 0x10de }

		assertEquals(Verdict.Drawing, drawing.verdict)
		assertEquals(
			Summary.OnGpu("NVIDIA GeForce RTX 4070/PCIe/SSE2", drawing.device),
			report.summary,
		)
	}

	@Test
	fun withTwoGpusTheOneNotDrawingStillSaysWhatItLacks() {
		val intel =
			wayland.copy(
				openGl = OpenGl.Running("Mesa Intel(R) Graphics (ADL GT2)", "Intel", "4.6"),
			)

		val devices =
			GraphicsDevices {
				DevicesFound.Found(
					listOf(
						device(Vendor(0x8086, "Intel"), KernelDriver("i915", null)),
						device(Vendor(0x10de, "NVIDIA"), KernelDriver("nvidia", "550.78")),
					),
				)
			}

		val report = examine(intel, devices, fails = mapOf("libEGL_nvidia.so.0" to NOT_FOUND))

		assertEquals(
			listOf(
				Verdict.Drawing,
				Verdict.NoLibrary("libEGL_nvidia.so.0", NOT_FOUND),
			),
			report.examined.map {
				it.verdict
			},
		)
		assertEquals(
			Summary.OnGpu("Mesa Intel(R) Graphics (ADL GT2)", report.examined.first().device),
			report.summary,
		)
	}

	@Test
	fun eachMissingThingIsSaidInTheOrderDrawingNeedsIt() {
		val driver = KernelDriver("amdgpu", null)
		val denied = DeviceNode(Path.of("/dev/dri/renderD128"), Access.Denied("render"))
		val amd = Vendor(0x1002, "AMD")

		assertEquals(Verdict.NoDriver, verdictOf(device(amd, null), emptyList(), wayland))
		assertEquals(
			Verdict.SetAside("vfio-pci"),
			verdictOf(device(amd, KernelDriver("vfio-pci", null)), emptyList(), wayland),
		)
		assertEquals(
			Verdict.NoAccess(denied),
			verdictOf(
				device(amd, driver, listOf(denied)),
				listOf(Library("libEGL_mesa.so.0", Loading.Fails("no"))),
				wayland,
			),
		)
		// A display node denied matters only where the device has no render node, which it is drawn through.
		assertEquals(
			Verdict.Passed(LLVMPIPE),
			verdictOf(
				device(
					amd,
					driver,
					listOf(
						DeviceNode(Path.of("/dev/dri/card0"), Access.Denied("video")),
						DeviceNode(Path.of("/dev/dri/renderD128"), Access.Open),
					),
				),
				emptyList(),
				wayland,
			),
		)
		assertEquals(
			Verdict.NoOpenGl("EGL not initialized"),
			verdictOf(
				device(amd, driver),
				emptyList(),
				wayland.copy(openGl = OpenGl.Unavailable("EGL not initialized")),
			),
		)
		assertEquals(
			Verdict.CpuAsked,
			verdictOf(device(amd, driver), emptyList(), wayland.copy(asked = Drawing.Cpu)),
		)
	}

	@Test
	fun theLoaderMissingIsWhyBeforeAnyDevice() {
		val report =
			examine(
				wayland,
				fails =
					mapOf("libEGL.so.1" to "libEGL.so.1: cannot open shared object file"),
			)

		assertEquals(
			Summary.NoLoader("libEGL.so.1", "libEGL.so.1: cannot open shared object file"),
			report.summary,
		)
	}

	@Test
	fun withNothingMissingOpenGlOnTheCpuIsWhy() {
		val report = examine(wayland, GraphicsDevices { DevicesFound.Found(emptyList()) })

		assertEquals(Summary.Software(LLVMPIPE), report.summary)
		assertEquals(
			Summary.CpuAsked,
			examine(wayland.copy(asked = Drawing.Cpu, openGl = OpenGl.NotAsked)).summary,
		)
	}

	@Test
	fun makersAreNamedByWholeWords() {
		val amd = Vendor(0x1002, "AMD")

		assertTrue(amd.named("AMD Radeon RX 7800 XT (radeonsi, navi32, LLVM 19.1.7, DRM 3.61)"))
		assertTrue(amd.named("ATI Technologies Inc. Radeon"))
		assertFalse(amd.named("Mesa llvmpipe compatibility profile"))
		assertTrue(Vendor(0x1af4, "Virtio").named("Mesa virgl (AMD Radeon RX 7800 XT)"))
	}

	@Test
	fun pciNamesGiveAMakersAndADevicesNames() {
		val names = PciNames(listOf(root.resolve("missing.ids"), pciIds()))

		assertEquals("NVIDIA Corporation" to "GeForce RTX 4070", names.of(0x10de, 0x2786))
		assertEquals("Intel Corporation" to null, names.of(0x8086, 0x46a6))
		assertEquals(null to null, names.of(0x1234, 0x1111))
	}

	@Test
	fun theSystemsLoaderSaysWhereALibraryIsAndWhyOneIsNot() {
		assumeTrue(Platform.get() == Platform.LINUX)

		val libc = SystemLibraries.load("libc.so.6")
		val absent = SystemLibraries.load("libEGL_absent.so.0")

		assertTrue((libc as Loading.Loads).from.toString().contains("libc.so.6"), "$libc")
		assertTrue(
			(absent as Loading.Fails).why.startsWith(
				"libEGL_absent.so.0: cannot open shared object file",
			),
			"$absent",
		)
	}

	/** A laptop's: an Intel GPU no driver is bound to, and an NVIDIA one with its driver, nodes and version. */
	private fun laptop(): SysfsDevices {
		val sys = root.resolve("sys")
		val dev = root.resolve("dev")
		val proc = root.resolve("proc")

		pci(sys, "0000:00:02.0", "0x030000", "0x8086", "0x46a6")
		pci(sys, "0000:00:1f.0", "0x060100", "0x8086", "0x7a84")

		val nvidia = pci(sys, "0000:01:00.0", "0x030000", "0x10de", "0x2786")

		Files.createSymbolicLink(
			nvidia.resolve("driver"),
			Path.of("../../../bus/pci/drivers/nvidia"),
		)
		listOf("card1", "renderD128").forEach { nvidia.resolve("drm/$it").createDirectories() }
		sys
			.resolve(
				"module/nvidia",
			).createDirectories()
			.resolve("version")
			.writeText("550.78\n")
		listOf("dri/card1", "dri/renderD128", "nvidiactl", "nvidia0").forEach {
			dev.resolve(it).also { node -> node.parent.createDirectories() }.writeText("")
		}
		proc
			.resolve("driver/nvidia/gpus/0000:01:00.0")
			.createDirectories()
			.resolve("information")
			.writeText(
				"Model: \t\t NVIDIA GeForce RTX 4070\nIRQ:   \t\t 180\nDevice Minor: \t 0\n",
			)

		return SysfsDevices(sys, dev, proc, PciNames(listOf(pciIds())))
	}

	private fun pci(
		sys: Path,
		slot: String,
		kind: String,
		vendor: String,
		model: String,
	): Path =
		sys.resolve("bus/pci/devices/$slot").createDirectories().also { device ->
			device.resolve("class").writeText("$kind\n")
			device.resolve("vendor").writeText("$vendor\n")
			device.resolve("device").writeText("$model\n")
		}

	private fun pciIds(): Path =
		root.resolve("pci.ids").also {
			it.writeText(
				"""
				|# The PCI ids' list
				|10de  NVIDIA Corporation
				|	2684  AD102 [GeForce RTX 4090]
				|	2786  AD104 [GeForce RTX 4070]
				|		1043 8880  Some board
				|8086  Intel Corporation
				|	7a84  Z690 LPC
				|
				""".trimMargin(),
			)
		}

	/** [rendering], examined on [devices], each library loading but those [fails] names, from where [from] says. */
	private fun examine(
		rendering: Rendering,
		devices: GraphicsDevices = laptop(),
		fails: Map<String, String> = emptyMap(),
		from: Map<String, Path> = emptyMap(),
	): GraphicsReport =
		Graphics.examine(
			rendering,
			devices,
			{ name -> fails[name]?.let(Loading::Fails) ?: Loading.Loads(from[name]) },
			LinuxLibraries,
			SearchPath("LD_LIBRARY_PATH", null),
		)

	private fun device(
		vendor: Vendor,
		driver: KernelDriver?,
		nodes: List<DeviceNode> = emptyList(),
	) = GraphicsDevice(vendor.name, vendor, "0000:00:00.0", driver, nodes)

	private companion object {
		const val LLVMPIPE = "llvmpipe (LLVM 20.1.8, 256 bits)"
		const val NOT_FOUND =
			"libEGL_nvidia.so.0: cannot open shared object file: No such file or directory"
	}
}
