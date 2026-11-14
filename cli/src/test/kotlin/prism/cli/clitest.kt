package prism.cli

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

	/** An option whose value may be left out, before an argument that its values are told apart from. */
	private object Index : Command("index", "an option given alone or with a value") {
		val file =
			optionalValue(
				"--file",
				type =
					ParamType("FILE") { word ->
						if (word.endsWith(".idx"))
							Parsed.Value(word)
						else
							Parsed.Invalid("'$word' is not an index")
					},
				help = "the index",
			)
		val folder = argument("FOLDER", ParamType.TEXT, default = { "home" }, shown = "home")

		override fun run(args: Args) = Outcome("")
	}

	private val root =
		Group("root", "the commands", listOf(Category("probing", listOf(Probe, Maybe, Index))))

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
	fun anOptionalValueIsTheNextWordOnlyWhereItReadsAsOne() {
		fun given(vararg words: String): Pair<Given<String>?, String> =
			assertInstanceOf(
				Invocation.Run::class.java,
				invocation(root, listOf("index") + words),
			).args.let {
				it[Index.file] to it[Index.folder]
			}

		assertEquals(null to "home", given())
		assertEquals(Given.Alone to "home", given("--file"))
		assertEquals(Given.With("a.idx") to "home", given("--file", "a.idx"))
		assertEquals(Given.With("a.idx") to "docs", given("--file=a.idx", "docs"))
		// The next word is not an index, so the option is given alone, and the word is the folder.
		assertEquals(Given.Alone to "docs", given("--file", "docs"))
		assertEquals(Given.Alone to "docs", given("docs", "--file"))
		// Given with `=`, it must be a value.
		assertTrue("not an index" in wrong("index", "--file=docs"))
		assertTrue("[FILE]" in help(Index, listOf("root", "index")))
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

	/** A command whose arguments are in a language of their own, in which a dash means something. */
	private object Seek : Command("seek", "arguments that may start with a dash") {
		val words = arguments("WORD", ParamType.TEXT)
		val best = flag("-b", "--best", help = "")
		val count = option("-n", "--count", type = ParamType.INT, default = 20, help = "")

		override val dashedArguments: Boolean get() = true

		override fun run(args: Args) = Outcome("")
	}

	@Test
	fun aDashedWordThatNamesNoOptionIsAnArgumentWhereACommandSaysSo() {
		fun read(vararg words: String): Args =
			assertInstanceOf(Invocation.Run::class.java, invocation(Seek, words.toList())).args

		// A word that is exactly an option is the option; any other is an argument, dash and all.
		val mixed = read("report", "-draft", "-b", "-ext:pdf", "--count", "3", "--bestest")

		assertEquals(listOf("report", "-draft", "-ext:pdf", "--bestest"), mixed[Seek.words])
		assertEquals(true, mixed[Seek.best])
		assertEquals(3, mixed[Seek.count])
		// After two dashes every word is an argument, the options' own words among them.
		assertEquals(listOf("-b", "-n"), read("--", "-b", "-n")[Seek.words])
		assertEquals(false, read("--", "-b")[Seek.best])
		// An option's value is still refused where it is not one, and help is still help.
		assertEquals(
			"--count: 'x' is not a whole number",
			assertInstanceOf(
				Invocation.Wrong::class.java,
				invocation(Seek, listOf("--count=x")),
			).message.substringBefore(" (").let { it },
		)
		assertInstanceOf(
			Invocation.Help::class.java,
			invocation(Seek, listOf("-draft", "--help")),
		)
		// A command that does not say so refuses them, as ever.
		assertEquals("unknown option -draft", wrong("probe", "-draft"))
	}

	/** A command with short options, as most have: what is run together is each of them. */
	private object Short : Command("short", "short options") {
		val words = arguments("WORD", ParamType.TEXT)
		val all = flag("-a", "--all", help = "")
		val best = flag("-b", "--best", help = "")
		val count = option("-n", "--count", type = ParamType.INT, default = 20, help = "")
		val nul = flag("-0", "--null", help = "")

		override fun run(args: Args) = Outcome("")
	}

	@Test
	fun shortOptionsAreRunTogetherAndOnlyTheLastTakesAValue() {
		fun read(command: Command, vararg words: String): Args =
			assertInstanceOf(Invocation.Run::class.java, invocation(command, words.toList())).args

		fun wrongly(command: Command, vararg words: String): String =
			assertInstanceOf(
				Invocation.Wrong::class.java,
				invocation(command, words.toList()),
			).message

		val together = read(Short, "-ab0", "x")

		assertEquals(
			listOf(true, true, true),
			listOf(Short.all, Short.best, Short.nul).map {
				together[it]
			},
		)
		assertEquals(listOf("x"), together[Short.words])

		// The last may take a value, which is the word after.
		val counted = read(Short, "-abn", "3", "x")

		assertEquals(3, counted[Short.count])
		assertEquals(listOf("x"), counted[Short.words])
		assertEquals(
			"-n takes a value, so it must come last in -nab",
			wrongly(Short, "-nab", "3"),
		)
		// A letter that is no option makes the whole word one that is not there; twice is twice, as written apart.
		assertEquals("unknown option -abx", wrongly(Short, "-abx"))
		assertEquals("-a given more than once", wrongly(Short, "-aa"))
		assertEquals("-a given more than once", wrongly(Short, "-a", "-ba"))
		// A long option is not letters run together, nor is one with a value written into it.
		assertEquals("unknown option --ab", wrongly(Short, "--ab"))
		assertEquals("unknown option -ab", wrongly(Short, "-ab=3"))

		// Where dashed words are arguments, they stay arguments, however like options run together they look;
		// until two dashes say where the arguments begin, and what is before them is options alone.
		assertEquals(listOf("-bn", "x"), read(Seek, "-bn", "x")[Seek.words])

		val dashed = read(Seek, "-bn", "3", "--", "-draft", "-b")

		assertEquals(true, dashed[Seek.best])
		assertEquals(3, dashed[Seek.count])
		assertEquals(listOf("-draft", "-b"), dashed[Seek.words])
		assertEquals("unknown option -draft", wrongly(Seek, "-draft", "--", "report"))
		assertEquals("unknown option -5", wrongly(Seek, "-5", "--", "report"))
		assertEquals(listOf("first", "report"), read(Seek, "first", "--", "report")[Seek.words])
		// A command says what a mistake in its words ends with: 1, unless it has a status of its own for that.
		assertEquals(1, execute(Seek, listOf("-draft", "--", "x")).status)
		assertEquals(
			2,
			execute(
				object : Command("picky", "its own status for a mistake") {
					override val mistaken: Int get() = 2

					override fun run(args: Args) = Outcome("")
				},
				listOf("--nope"),
			).status,
		)
	}

	@Test
	fun mistakesAreRefusedNotReadAnotherWay() {
		assertEquals("unknown option --nope", wrong("probe", "in", "--need", "1", "--nope"))
		assertEquals(
			"--json given more than once",
			wrong("probe", "in", "--need", "1", "--json", "--json"),
		)
		assertEquals(
			"--json does not take a value",
			wrong("probe", "in", "--need", "1", "--json=yes"),
		)
		assertEquals(
			"--top requires a value (N)",
			wrong("probe", "in", "--need", "1", "--top", "--json"),
		)
		assertEquals("--need is required (N)", wrong("probe", "in"))
		assertEquals("missing argument: INPUT", wrong("probe", "--need", "1"))
		assertEquals(
			"--top: 'many' is not a whole number",
			wrong("probe", "in", "--need", "1", "--top", "many"),
		)
		assertEquals("unexpected argument 'b'", wrong("maybe", "a", "b"))
		assertEquals(
			"--size: '3x' is not a size in the form WxH",
			wrong("maybe", "--size", "3x"),
		)
		assertEquals("unknown command 'nope'", wrong("nope"))
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
	fun wordsThatNameNoCommandAreTheDefaultCommands() {
		val defaulted =
			Group(
				"root",
				"the commands",
				listOf(Category("probing", listOf(Probe, Maybe, Index))),
				default = Maybe,
			)

		fun ran(vararg words: String): Invocation.Run =
			assertInstanceOf(Invocation.Run::class.java, invocation(defaulted, words.toList()))

		// No words at all, a word that is no command, and an option alone: each the default's.
		assertEquals(Maybe, ran().command)
		assertEquals("home", ran().args[Maybe.where])
		assertEquals("there", ran("there").args[Maybe.where])
		assertEquals(Size(3, 4), ran("--size", "3x4").args[Maybe.size])
		assertEquals(listOf("root", "maybe"), ran("there").path)
		// A command's name is the command, and help asked of the group is the group's.
		assertEquals(Index, ran("index").command)
		assertInstanceOf(Invocation.Help::class.java, invocation(defaulted, listOf("--help")))
		// What the default cannot read is its mistake.
		assertEquals(
			"unexpected argument 'two'",
			assertInstanceOf(
				Invocation.Wrong::class.java,
				invocation(defaulted, listOf("one", "two")),
			).message,
		)

		val help = execute(defaulted, listOf("--help")).out

		assertTrue("usage: root COMMAND ...\n       root [WHERE] [OPTIONS]" in help, help)
		assertTrue("With no COMMAND, root runs root maybe." in help, help)
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
