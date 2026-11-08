package house

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.EditorConfigOverride
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.CODE_STYLE_PROPERTY
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.CodeStyleValue
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.INDENT_SIZE_PROPERTY
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.INDENT_STYLE_PROPERTY
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.RuleExecution
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.createRuleExecutionEditorConfigProperty
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import org.ec4j.core.model.PropertyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Runs the house rules together with the standard rule set, configured like the project's `.editorconfig`. */
object Ktlint {
	private val engine =
		KtLintRuleEngine(
			ruleProviders =
				HouseRuleSetProvider().getRuleProviders() +
					StandardRuleSetProvider().getRuleProviders(),
			editorConfigOverride =
				EditorConfigOverride.from(
					CODE_STYLE_PROPERTY to CodeStyleValue.ktlint_official,
					INDENT_STYLE_PROPERTY to PropertyType.IndentStyleValue.tab,
					INDENT_SIZE_PROPERTY to 4,
					RuleId("standard:multiline-if-else").createRuleExecutionEditorConfigProperty() to
						RuleExecution.disabled,
					RuleId("standard:if-else-wrapping").createRuleExecutionEditorConfigProperty() to
						RuleExecution.disabled,
					RuleId("standard:multiline-loop").createRuleExecutionEditorConfigProperty() to
						RuleExecution.disabled,
					RuleId("standard:filename").createRuleExecutionEditorConfigProperty() to
						RuleExecution.disabled,
				),
		)

	fun format(source: String): String =
		engine.format(
			Code.fromSnippet(source.trimIndent() + "\n"),
		) { _, _ -> }

	fun lint(source: String): List<LintError> =
		buildList {
			engine.lint(Code.fromSnippet(source.trimIndent() + "\n")) { add(it) }
		}

	fun houseErrors(source: String): List<String> =
		lint(source)
			.filter {
				it.ruleId.ruleSetId == HouseRuleSetProvider.ID
			}.map { it.detail }
}

class LocalDeclarationSpacingTest {
	@Test
	fun `declarations are separated from the statements around them`() {
		val formatted =
			Ktlint.format(
				"""
				fun f(a: Int): Int {
					println(a)
					val x = a + 1
					val y = x * 2
					println(y)
					return y
				}
				""",
			)

		assertEquals(
			"""
			fun f(a: Int): Int {
				println(a)

				val x = a + 1
				val y = x * 2

				println(y)

				return y
			}
			""".trimIndent() + "\n",
			formatted,
		)
	}

	@Test
	fun `a declaration with a multi-line argument list stands alone`() {
		val formatted =
			Ktlint.format(
				"""
				fun f(): Int {
					val extent = choose()
					val info =
						Info(
							a = 1,
							b = 2,
						)
					val swapchain = create(info)
					val images = list(swapchain)

					return images.size
				}
				""",
			)

		assertEquals(
			"""
			fun f(): Int {
				val extent = choose()

				val info =
					Info(
						a = 1,
						b = 2,
					)

				val swapchain = create(info)
				val images = list(swapchain)

				return images.size
			}
			""".trimIndent() + "\n",
			formatted,
		)
	}

	@Test
	fun `a short trailing-lambda declaration may stay grouped`() {
		val source =
			"""
			fun f(images: List<Int>): Int {
				val a = images.size
				val semaphores =
					images.map {
						it + 1
					}

				return semaphores.size
			}
			"""

		assertEquals(emptyList<String>(), Ktlint.houseErrors(source))
	}

	@Test
	fun `two multi-line declarations in a row are separated`() {
		val formatted =
			Ktlint.format(
				"""
				fun f(a: Boolean): Int {
					val speed =
						if (a)
							1
						else
							2
					val velocity =
						if (a)
							3
						else
							4
					val result = speed + velocity

					return result
				}
				""",
			)

		assertEquals(
			"""
			fun f(a: Boolean): Int {
				val speed =
					if (a)
						1
					else
						2

				val velocity =
					if (a)
						3
					else
						4
				val result = speed + velocity

				return result
			}
			""".trimIndent() + "\n",
			formatted,
		)
	}

	@Test
	fun `a comment before a declaration keeps the blank line above the comment`() {
		val formatted =
			Ktlint.format(
				"""
				fun f(): Int {
					println("a")
					// the answer
					val x = 42
					return x
				}
				""",
			)

		assertEquals(
			"""
			fun f(): Int {
				println("a")

				// the answer
				val x = 42

				return x
			}
			""".trimIndent() + "\n",
			formatted,
		)
	}

	@Test
	fun `statements sharing a line are left alone`() {
		assertEquals(
			emptyList<String>(),
			Ktlint.houseErrors("fun f() { val x = 1; println(x) }"),
		)
	}
}

class BracelessConditionalWrappingTest {
	@Test
	fun `braceless bodies move to their own line`() {
		val formatted =
			Ktlint.format(
				"""
				fun f(a: Boolean, b: Boolean): Int {
					var wish = 0
					if (a) wish += 1
					if (b) wish -= 1
					while (wish > 10) wish -= 10
					for (i in 0 until 3) wish += i

					return wish
				}
				""",
			)

		assertEquals(
			"""
			fun f(a: Boolean, b: Boolean): Int {
				var wish = 0

				if (a)
					wish += 1
				if (b)
					wish -= 1
				while (wish > 10)
					wish -= 10
				for (i in 0 until 3)
					wish += i

				return wish
			}
			""".trimIndent() + "\n",
			formatted,
		)
	}

	@Test
	fun `a long if-else expression is wrapped with else on its own line`() {
		val formatted =
			Ktlint.format(
				"""
				fun f(wish: Vec3): Vec3 {
					println(wish)

					return if (wish.length() > 1e-6f) wish.normalized() else Vec3.ZERO
				}
				""",
			)

		assertEquals(
			"""
			fun f(wish: Vec3): Vec3 {
				println(wish)

				return if (wish.length() > 1e-6f)
					wish.normalized()
				else
					Vec3.ZERO
			}
			""".trimIndent() + "\n",
			formatted,
		)
	}

	@Test
	fun `simple ternaries may stay on one line`() {
		val source =
			"""
			fun f(a: Boolean, tuning: Tuning): Float {
				val control = if (a) 1f else tuning.airControl
				val count = if (a) size() else minOf(4)
				val mode = if (a) mode.toggled() else mode
				val neg = if (a) -1f else "no"

				return control
			}
			"""

		assertEquals(emptyList<String>(), Ktlint.houseErrors(source))
	}

	@Test
	fun `computation in a branch is not simple`() {
		val errors = Ktlint.houseErrors("fun f(a: Boolean, b: Int) = if (a) b * 2 else 0")

		// The then-body, the `else` keyword and the else-body each need their own line.
		assertEquals(3, errors.size)
	}

	@Test
	fun `a chained call in a branch is not simple`() {
		val errors =
			Ktlint.houseErrors(
				"fun f(a: Boolean, b: Int) = if (a) b.foo().bar() else 0",
			)

		assertEquals(3, errors.size)
	}

	@Test
	fun `else-if chains keep if on the else line`() {
		val source =
			"""
			fun f(a: Boolean, b: Boolean): Int {
				return if (a)
					1
				else if (b)
					2
				else
					3
			}
			"""

		assertEquals(emptyList<String>(), Ktlint.houseErrors(source))
	}

	@Test
	fun `blocks are untouched`() {
		val source =
			"""
			fun f(a: Boolean) {
				if (a) {
					println(a)
				} else {
					println(!a)
				}
			}
			"""

		assertEquals(emptyList<String>(), Ktlint.houseErrors(source))
	}
}

class ReturnSpacingTest {
	@Test
	fun `return inside a braceless if body is not a block statement`() {
		val source =
			"""
			fun f(a: Boolean) {
				if (a)
					return
				println(a)
			}
			"""

		assertEquals(emptyList<String>(), Ktlint.houseErrors(source))
	}

	@Test
	fun `a lone return needs no blank line`() {
		assertTrue(Ktlint.houseErrors("fun f(): Int {\n\treturn 1\n}").isEmpty())
	}
}
