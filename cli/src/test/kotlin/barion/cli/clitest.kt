package barion.cli

import kotlin.io.path.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** How the command line reads its words: each kind of parameter, and the mistakes it refuses rather than reads another way. */
class CliTest {
	private object Probe : Command("probe", "a command with one parameter of each kind") {
		val input = argument("INPUT", ParamType.PATH)
		val rest = arguments("MORE", ParamType.TEXT)
		val json = flag("--json", help = "")
		val top = option("--top", type = ParamType.INT, default = 25, help = "")
		val at = option("-a", "--at", type = ParamType.TEXT, help = "")
		val need = required("--need", type = ParamType.INT, help = "")
		val tag = repeated("--tag", type = ParamType.TEXT, help = "")

		override fun run(args: Args) = Outcome("")
	}

	private object Maybe : Command("maybe", "an optional argument") {
		val where = argument("WHERE", ParamType.TEXT, default = { "home" }, shown = "home")
		val size =
			option("--size", type = Size.TYPE, default = Size(10, 20), help = "", shown = "10x20")

		override fun run(args: Args) = Outcome("${args[where]} ${args[size]}")
	}

	private val root =
		Group("root", "the commands", listOf(Category("probing", listOf(Probe, Maybe))))

	private fun run(vararg words: String): Args =
		assertInstanceOf(
			Invocation.Run::class.java,
			invocation(root, listOf("probe") + words),
		).args

	private fun wrong(vararg words: String): String =
		assertInstanceOf(Invocation.Wrong::class.java, invocation(root, words.toList())).message

	@Test
	fun eachKindOfParameterHasItsValue() {
		val args =
			run("in", "--need", "15", "x", "--tag", "s1", "--json", "-a", "0x40", "--tag=s2", "y")

		assertEquals(Path("in"), args[Probe.input])
		assertEquals(listOf("x", "y"), args[Probe.rest])
		assertEquals(true, args[Probe.json])
		assertEquals(25, args[Probe.top])
		assertEquals("0x40", args[Probe.at])
		assertEquals(15, args[Probe.need])
		assertEquals(listOf("s1", "s2"), args[Probe.tag])
	}

	@Test
	fun whatIsLeftOutHasItsDefault() {
		val args = run("in", "--need=2", "--top", "-3")

		assertEquals(false, args[Probe.json])
		assertEquals(-3, args[Probe.top])
		assertEquals(null, args[Probe.at])
		assertEquals(emptyList<String>(), args[Probe.tag])
	}

	@Test
	fun anOptionalArgumentTakesItsDefaultWhenLeftOut() {
		assertEquals("home Size(width=10, height=20)", execute(root, listOf("maybe")).out)
		assertEquals(
			"docs Size(width=3, height=4)",
			execute(root, listOf("maybe", "docs", "--size", "3x4")).out,
		)
	}

	@Test
	fun afterTwoDashesEveryWordIsAnArgument() {
		assertEquals(
			listOf("--json", "-a"),
			run("in", "--need", "1", "--", "--json", "-a")[Probe.rest],
		)
	}

	@Test
	fun mistakesAreRefusedNotReadAnotherWay() {
		assertEquals("unknown option --nope", wrong("probe", "in", "--need", "1", "--nope"))
		assertEquals(
			"--json given twice",
			wrong("probe", "in", "--need", "1", "--json", "--json"),
		)
		assertEquals("--json takes no value", wrong("probe", "in", "--need", "1", "--json=yes"))
		assertEquals(
			"--top needs a value (N)",
			wrong("probe", "in", "--need", "1", "--top", "--json"),
		)
		assertEquals("--need is needed (N)", wrong("probe", "in"))
		assertEquals("missing INPUT", wrong("probe", "--need", "1"))
		assertEquals(
			"--top: 'many' is not a whole number",
			wrong("probe", "in", "--need", "1", "--top", "many"),
		)
		assertEquals("unexpected word 'b'", wrong("maybe", "a", "b"))
		assertEquals("--size: '3x' is not a size WxH", wrong("maybe", "--size", "3x"))
		assertEquals("no command 'nope'", wrong("nope"))
	}

	@Test
	fun helpIsMadeFromTheDeclarations() {
		val help = execute(root, listOf("probe", "--help")).out

		assertTrue("usage: root probe INPUT MORE... [OPTIONS]" in help, help)
		assertTrue("--top N" in help && "(default 25)" in help, help)
		assertTrue("--tag TEXT" in help && "(repeatable)" in help, help)
		assertTrue("[WHERE]" in execute(root, listOf("--help")).out)
	}

	@Test
	fun aValueOfAnotherCommandIsRefused() {
		val args = run("in", "--need", "1")

		assertThrows<IllegalArgumentException> { args[Maybe.where] }
	}

	@Test
	fun anInputErrorIsTheStatusOneAndItsMessage() {
		val failing =
			object : Command("fail", "") {
				override fun run(args: Args): Outcome = throw InputError("no such thing")
			}

		assertEquals(Ran("", "fail: no such thing", 1), execute(failing, emptyList()))
	}
}
