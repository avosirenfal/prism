package prism.platform

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FoldersTest {
	private val home = Path.of("/home/a")

	@Test
	fun linuxsAreTheXdgFoldersOrTheirDefaults() {
		val defaults = OwnFolders.of(Os.Linux, emptyMap(), home)

		assertEquals(
			OwnFolder(Path.of("/home/a/.config/prism"), "\$XDG_CONFIG_HOME/prism"),
			defaults.config,
		)
		assertEquals(Path.of("/home/a/.cache/prism"), defaults.cache.path)
		assertEquals(Path.of("/home/a/.local/state/prism"), defaults.state.path)
		assertEquals(Runtime.Missing("XDG_RUNTIME_DIR is not set"), defaults.runtime)

		val said =
			OwnFolders.of(
				Os.Linux,
				mapOf(
					"XDG_CACHE_HOME" to "/c",
					"XDG_STATE_HOME" to "relative",
					"XDG_RUNTIME_DIR" to "/run/user/1",
				),
				home,
			)

		assertEquals(Path.of("/c/prism"), said.cache.path)
		assertEquals(
			Path.of("/home/a/.local/state/prism"),
			said.state.path,
			"a relative folder is no folder",
		)
		assertEquals(
			Runtime.At(OwnFolder(Path.of("/run/user/1/prism"), "\$XDG_RUNTIME_DIR/prism")),
			said.runtime,
		)
	}

	@Test
	fun macOSsAreTheLibrarysAndTheUsersTemporaryFolder() {
		val folders =
			OwnFolders.of(Os.MacOS, mapOf("TMPDIR" to "/var/folders/xy/abc/T/"), home, null)

		assertEquals(
			OwnFolder(
				Path.of("/home/a/Library/Application Support/prism"),
				"~/Library/Application Support/prism",
			),
			folders.config,
		)
		assertEquals(folders.config, folders.state)
		assertEquals(
			OwnFolder(Path.of("/home/a/Library/Caches/prism"), "~/Library/Caches/prism"),
			folders.cache,
		)
		assertEquals(
			Runtime.At(OwnFolder(Path.of("/var/folders/xy/abc/T/prism"), "\$TMPDIR/prism")),
			folders.runtime,
		)
		assertEquals(
			Runtime.Missing("TMPDIR is not set"),
			OwnFolders.of(Os.MacOS, emptyMap(), home, null).runtime,
		)
		// The system's own word for it comes before the variable: a daemon launchd starts is given no variable,
		// and a shell may be given another's.
		assertEquals(
			Runtime.At(OwnFolder(Path.of("/var/folders/xy/abc/T/prism"), "\$TMPDIR/prism")),
			OwnFolders
				.of(
					Os.MacOS,
					mapOf("TMPDIR" to "/tmp/nix-shell"),
					home,
					Path.of("/var/folders/xy/abc/T"),
				).runtime,
		)
	}

	@Test
	fun theJvmsNamesAreThePlatforms() {
		assertEquals(Os.MacOS, Os.named("Mac OS X"))
		assertEquals(Os.Windows, Os.named("Windows 11"))
		assertEquals(Os.Linux, Os.named("Linux"))
	}
}
