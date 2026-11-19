package prism.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The user's session's environment: on a Mac, what their login shell says over what launchd gave prism, a shell's
 * own variables left out; and a program found on its `PATH`, as a shell finds one.
 */
class SessionTest {
	private val mark = "PRISM-ENVIRONMENT-7f3a"

	@Test
	fun whatTheShellPrintsBetweenItsMarksIsItsEnvironment_WhateverItGreetsWith() {
		val printed =
			"\u001B]0;zsh\u0007Last login: today\n$mark" +
				"PATH=/run/current-system/sw/bin:/usr/bin\u0000EDITOR=hx\u0000EMPTY=\u0000WITH=a=b\u0000$mark"

		assertEquals(
			mapOf(
				"PATH" to "/run/current-system/sw/bin:/usr/bin",
				"EDITOR" to "hx",
				"EMPTY" to "",
				"WITH" to "a=b",
			),
			environmentPrinted(printed),
		)
		assertNull(environmentPrinted("a shell that never got as far as printing it"))
		assertNull(environmentPrinted("${mark}PATH=/usr/bin\u0000 cut short"))
	}

	@Test
	fun theShellsSayIsTakenOverLaunchds_ButForWhatAShellHasOfItsOwn() {
		val launchd =
			mapOf(
				"PATH" to "/usr/bin:/bin:/usr/sbin:/sbin",
				"HOME" to "/Users/a",
				"SSH_AUTH_SOCK" to "/tmp/s",
			)

		val shell =
			mapOf(
				"PATH" to "/run/current-system/sw/bin:/usr/bin:/bin",
				"HOME" to "/Users/a",
				"EDITOR" to "hx",
				"SHLVL" to "1",
				"PWD" to "/Users/a",
				"_" to "/usr/bin/env",
				"PRISM_RESOLVING_ENVIRONMENT" to "1",
			)

		assertEquals(
			mapOf(
				"PATH" to "/run/current-system/sw/bin:/usr/bin:/bin",
				"HOME" to "/Users/a",
				"SSH_AUTH_SOCK" to "/tmp/s",
				"EDITOR" to "hx",
			),
			sessionOf(launchd, shell),
		)
		// A shell that said nothing leaves prism's own.
		assertEquals(launchd, sessionOf(launchd, null))
	}

	@Test
	fun aProgramIsFoundInTheFirstFolderOfThePathThatHasIt_AsAShellFindsIt(
		@TempDir root: Path,
	) {
		fun program(at: String): Path =
			root.resolve(at).also {
				Files.createDirectories(it.parent)
				Files.writeString(it, "#!/bin/sh\n")
				Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxr-xr-x"))
			}

		val nix = program("run/current-system/sw/bin/rg")

		program("usr/local/bin/rg")
		Files.writeString(root.resolve("usr/local/bin/notes"), "not a program")
		Files.createDirectories(root.resolve("usr/local/bin/folder"))

		val path =
			listOf(
				"nowhere",
				"run/current-system/sw/bin",
				"usr/local/bin",
			).joinToString(":") {
				"$root/$it"
			}

		assertEquals(nix, whichOn(path, "rg"))
		assertNull(whichOn(path, "notes"))
		assertNull(whichOn(path, "folder"))
		assertNull(whichOn("/usr/bin:/bin", "rg-nowhere"))
		assertEquals(nix, whichOn("", nix.toString()))
	}

	@Test
	fun aLoginShellIsAskedForWhatItsOwnFilesSet_AndPrismsOwnIsWhatItStartsFrom(
		@TempDir home: Path,
	) {
		val bash = whichOn("/bin:/usr/bin", "bash")

		org.junit.jupiter.api.Assumptions
			.assumeTrue(bash != null, "needs bash")
		// A login shell's file that puts a folder on the path, and greets, as a shell's files may.
		Files.writeString(
			home.resolve(".bash_profile"),
			"echo hello there\nexport PATH=/opt/nix/bin:\$PATH\n",
		)

		val said =
			loginShellEnvironment(
				mapOf(
					"SHELL" to bash.toString(),
					"HOME" to home.toString(),
					"PATH" to "/usr/bin:/bin",
				),
				10,
			)!!

		// What the user's own file put first, before whatever the system's own files add.
		assertEquals("/opt/nix/bin", said["PATH"]?.substringBefore(':'))
		assertEquals("1", said["PRISM_RESOLVING_ENVIRONMENT"])
	}
}
