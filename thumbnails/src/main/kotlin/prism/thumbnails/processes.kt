package prism.thumbnails

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/*
 * The programs that make thumbnails, run. Each is a process of its own, so that whatever a file does to the program
 * reading it (a crash, a loop, a picture of a billion pixels) happens there and not in prism: it is given so long,
 * and may print so much, and past either it is killed, with whatever it started. A program no longer wanted (the
 * window that asked was closed) is killed the same way.
 */

/** What running a program came to. */
sealed interface Ran {
	/** It ended, with [exit], having printed [out] and said [err]. */
	class Ended(
		val exit: Int,
		val out: ByteArray,
		val err: String,
	) : Ran

	/** There is no such program to run, and [why]. */
	data class Missing(
		val why: String,
	) : Ran

	/** It was killed, and [why]: it took too long, or printed more than anything it could rightly print. */
	data class Killed(
		val why: String,
	) : Ran
}

/** What runs programs: the system, or a test's own. */
fun interface Processes {
	/**
	 * [words] run, waited for no longer than [limit], and read for no more than [most] bytes of what it prints. Where
	 * whoever waits is cancelled, the program is killed.
	 */
	suspend fun run(words: List<String>, limit: Duration, most: Int): Ran

	companion object {
		/** The most a program is let print, unless whoever runs it says: a PNG of the largest thumbnail, many times over. */
		const val MOST = 64 * 1024 * 1024

		/** The system's processes, run in this process's own environment. */
		val SYSTEM: Processes = system { null }

		/**
		 * The system's processes, run in [environment] where it gives one (the user's session's, which on a Mac is
		 * their login shell's and not what an application is started with): a program named without a folder is
		 * looked for on that environment's `PATH`, and is given that environment.
		 */
		fun system(environment: () -> Map<String, String>?): Processes =
			Processes { words, limit, most ->
				runInterruptible(Dispatchers.IO) { ran(words, limit, most, environment()) }
			}

		private fun ran(
			words: List<String>,
			limit: Duration,
			most: Int,
			environment: Map<String, String>?,
		): Ran {
			val program = words.first()

			val found =
				environment
					?.takeIf { File.separatorChar !in program }
					?.let { variables ->
						Ffmpeg
							.pathOf(variables)
							.map { it.resolve(program) }
							.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
							?: return Ran.Missing("$program is not on the PATH")
					}

			val process =
				try {
					ProcessBuilder(listOf(found?.toString() ?: program) + words.drop(1))
						.apply {
							environment?.let {
								environment().clear()
								environment().putAll(it)
							}
						}.start()
				} catch (failed: IOException) {
					return Ran.Missing(failed.message ?: failed.toString())
				}

			try {
				// It is given nothing to read: a program that asks a question gets no answer, and ends.
				process.outputStream.close()

				val out = Reading(process.inputStream, most)
				val err = Reading(process.errorStream, SAID)

				if (!process.waitFor(limit.inWholeMilliseconds, TimeUnit.MILLISECONDS))
					return Ran.Killed("it took longer than $limit")

				out.join()
				err.join()

				return if (out.over)
					Ran.Killed("it printed more than $most bytes")
				else
					Ran.Ended(process.exitValue(), out.bytes(), err.bytes().toString(Charsets.UTF_8))
			} finally {
				// Ended, there is nothing to kill; otherwise it is no longer waited for, and neither is what it started.
				process.descendants().forEach { it.destroyForcibly() }
				process.destroyForcibly()
			}
		}

		/** How much of what a program says of its errors is kept. */
		private const val SAID = 16 * 1024
	}

	/** One of a process's streams read to its end on a thread of its own, the first [most] bytes of it kept. */
	private class Reading(
		private val stream: InputStream,
		private val most: Int,
	) {
		private val kept = ByteArrayOutputStream()

		@Volatile
		var over = false
			private set

		private val thread =
			Thread.ofVirtual().start {
				val buffer = ByteArray(64 * 1024)

				try {
					while (true) {
						val count = stream.read(buffer)

						if (count < 0)
							break
						if (kept.size() + count > most)
							over = true
						else
							kept.write(buffer, 0, count)
					}
				} catch (closed: IOException) {
					// The process was killed: what was read is all there is.
				}
			}

		fun join() = thread.join()

		fun bytes(): ByteArray = kept.toByteArray()
	}
}
