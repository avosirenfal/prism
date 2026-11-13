package prism.app

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import prism.portal.FileChooser
import prism.portal.FileManager

/**
 * The Nix package and the NixOS module name things prism serves and answers to: the portal backend's bus name and
 * its interface, the file manager's name, the words the daemon takes, what its windows are called. Nix cannot read
 * them from the Kotlin, so they are written there a second time; this fails where the second writing is not the first.
 */
class PackagingTest {
	/** A file of the flake's, read from the module's folder, where the tests run. */
	private fun flakeFile(name: String): String {
		val file = Path.of("..", "nix", name)

		assumeTrue(Files.exists(file), "run from a checkout: $file is not there")

		return Files.readString(file)
	}

	private fun assertNames(text: String, vararg names: String) {
		for (name in names)
			assertTrue(name in text, "“$name” is not in it")
	}

	@Test
	fun thePackageNamesTheBackendAsPrismServesIt() {
		assertNames(
			flakeFile("package.nix"),
			"backendName = \"${FileChooser.BACKEND}\";",
			"chooserInterface = \"${FileChooser.INTERFACE}\";",
			"startupWMClass = \"$APP_ID\";",
			"Exec=\$out/bin/prism ${DaemonCommand.name} --foreground",
			// What gives back to a program prism starts what the wrapper changed for prism.
			"PRISM_RESTORE_LD_LIBRARY_PATH",
			"-Dprism.launcher=",
		)
	}

	@Test
	fun theModuleNamesTheFileManagerAndTheDaemonsWordsAsPrismReadsThem() {
		val module = flakeFile("module.nix")

		assertNames(
			module,
			"Name=${FileManager.NAME}",
			"\"${FileChooser.INTERFACE}\" = \"$BACKEND_NAME\";",
			"\"$FOLDER_MIME\" = \"prism.desktop\";",
		)

		// Every word the module gives the daemon is one the daemon reads.
		val words = Regex("\"(--[a-z-]+)\"").findAll(module).map { it.groupValues[1] }.toSet()
		val read =
			DaemonCommand.params
				.filterIsInstance<prism.cli.Opt<*>>()
				.flatMap {
					it.names
				}.toSet()

		assertTrue(words.isNotEmpty())
		assertTrue(read.containsAll(words), "the daemon does not read ${words - read}")
	}

	private companion object {
		/** What a folder is, as a desktop asks what opens one. */
		const val FOLDER_MIME = "inode/directory"
	}
}
