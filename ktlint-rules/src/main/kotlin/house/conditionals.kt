package house

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.IndentConfig
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.children20
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.EditorConfig
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.EditorConfigProperty
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.INDENT_SIZE_PROPERTY
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.INDENT_STYLE_PROPERTY
import com.pinterest.ktlint.rule.engine.core.api.ifAutocorrectAllowed
import com.pinterest.ktlint.rule.engine.core.api.indentWithoutNewlinePrefix
import com.pinterest.ktlint.rule.engine.core.api.isWhiteSpaceWithNewline20
import com.pinterest.ktlint.rule.engine.core.api.prevSibling20
import com.pinterest.ktlint.rule.engine.core.api.upsertWhitespaceBeforeMe
import org.ec4j.core.model.PropertyType
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

/**
 * A conditional or loop body that has no braces goes on its own indented line, and so does `else`:
 *
 * ```
 * if (input.isHeld(Action.Forward))
 *     wish += cam.forwardFlat
 *
 * return if (wish.length() > 1e-6f)
 *     wish.normalized()
 * else
 *     Vec3.ZERO
 * ```
 *
 * The one exception is a *simple ternary*: an `if`/`else` expression whose whole text is shorter than the configured
 * limit and whose branches are both [SimpleValue]s, such as `if (state.onGround) 1f else tuning.airControl`. Those may
 * stay on one line (and are left alone if already wrapped).
 */
class BracelessConditionalWrappingRule :
	Rule(
		ruleId = HouseRuleSetProvider.ruleId("braceless-conditional-wrapping"),
		about = HouseRuleSetProvider.ABOUT,
		usesEditorConfigProperties =
			setOf(
				INDENT_STYLE_PROPERTY,
				INDENT_SIZE_PROPERTY,
				SIMPLE_TERNARY_MAX_LENGTH,
			),
	),
	RuleAutocorrectApproveHandler {
	private var indent = IndentConfig.DEFAULT_INDENT_CONFIG
	private var simpleTernaryMaxLength = SIMPLE_TERNARY_MAX_LENGTH.defaultValue

	override fun beforeFirstNode(editorConfig: EditorConfig) {
		indent =
			IndentConfig(editorConfig[INDENT_STYLE_PROPERTY], editorConfig[INDENT_SIZE_PROPERTY])
		simpleTernaryMaxLength = editorConfig[SIMPLE_TERNARY_MAX_LENGTH]
	}

	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (
			offset: Int,
			errorMessage: String,
			canBeAutoCorrected: Boolean,
		) -> AutocorrectDecision,
	) {
		when (node.elementType) {
			ElementType.IF -> visitIf(node, emit)
			ElementType.WHILE, ElementType.FOR -> visitLoop(node, emit)
		}
	}

	private fun visitIf(node: ASTNode, emit: Emit) {
		if (SimpleTernary.matches(node, simpleTernaryMaxLength))
			return

		val then = node.findChildByType(ElementType.THEN)
		val elseKeyword = node.findChildByType(ElementType.ELSE_KEYWORD)
		val elseBranch = node.findChildByType(ElementType.ELSE)

		if (then != null && then.isBraceless)
			requireOwnLine(
				then,
				node,
				"The body of a braceless `if` must start on its own line",
				emit,
			)
		if (elseKeyword != null && then != null && then.isBraceless)
			requireNewlineBefore(
				elseKeyword,
				node.indentWithoutNewlinePrefix,
				"`else` after a braceless `if` body must start on its own line",
				emit,
			)
		if (elseBranch != null && elseBranch.isBraceless &&
			elseBranch.firstChildNode?.elementType != ElementType.IF
		)
			requireOwnLine(
				elseBranch,
				node,
				"The body of a braceless `else` must start on its own line",
				emit,
			)
	}

	private fun visitLoop(node: ASTNode, emit: Emit) {
		val body = node.findChildByType(ElementType.BODY) ?: return

		if (body.isBraceless)
			requireOwnLine(
				body,
				node,
				"The body of a braceless loop must start on its own line",
				emit,
			)
	}

	/** A THEN/ELSE/BODY wrapper whose content exists and is not a `{ ... }` block. */
	private val ASTNode.isBraceless: Boolean
		get() = firstChildNode?.let { it.elementType != ElementType.BLOCK } ?: false

	private fun requireOwnLine(
		branch: ASTNode,
		conditional: ASTNode,
		message: String,
		emit: Emit,
	) = requireNewlineBefore(
		branch,
		conditional.indentWithoutNewlinePrefix + indent.indent,
		message,
		emit,
	)

	private fun requireNewlineBefore(
		node: ASTNode,
		indentation: String,
		message: String,
		emit: Emit,
	) {
		if (node.prevSibling20.isWhiteSpaceWithNewline20)
			return

		emit(node.startOffset, message, true).ifAutocorrectAllowed {
			node.upsertWhitespaceBeforeMe("\n$indentation")
		}
	}

	companion object {
		/** An `if`/`else` expression shorter than this whose branches are simple values may stay on one line. */
		val SIMPLE_TERNARY_MAX_LENGTH: EditorConfigProperty<Int> =
			EditorConfigProperty(
				type =
					PropertyType.LowerCasingPropertyType(
						"ktlint_house_simple_ternary_max_length",
						"Maximum length of an if/else expression with simple-value branches that may stay on one line",
						PropertyType.PropertyValueParser.POSITIVE_INT_VALUE_PARSER,
						emptySet(),
					),
				defaultValue = 50,
			)
	}
}

private typealias Emit = (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision

/** The `if (thing) 5 else asdf()` exception: short, and each branch is a value rather than computation. */
object SimpleTernary {
	fun matches(ifNode: ASTNode, maxLength: Int): Boolean {
		val then = ifNode.findChildByType(ElementType.THEN)?.firstChildNode ?: return false
		val elseBranch =
			ifNode.findChildByType(ElementType.ELSE)?.firstChildNode ?: return false

		return collapsedLength(ifNode) < maxLength && SimpleValue.matches(then) &&
			SimpleValue.matches(elseBranch)
	}

	/** Length of the expression as it would read on a single line. */
	private fun collapsedLength(node: ASTNode): Int =
		node.text.replace(WHITESPACE_RUN, " ").length

	private val WHITESPACE_RUN = Regex("\\s+")
}

/**
 * "Constant values or single function expressions without any fluent chaining, and at most one parameter which itself
 * must be a constant value or single function call with no parameters." Plain names (`it`, `Vec3.ZERO`, `tuning.speed`)
 * count as constants: they are read, not computed.
 */
object SimpleValue {
	private val constantTypes =
		setOf(
			ElementType.INTEGER_CONSTANT,
			ElementType.FLOAT_CONSTANT,
			ElementType.BOOLEAN_CONSTANT,
			ElementType.CHARACTER_CONSTANT,
			ElementType.NULL,
			ElementType.THIS_EXPRESSION,
		)

	fun matches(expression: ASTNode): Boolean =
		isConstant(expression) || isSingleCall(expression)

	/** A literal, a (possibly qualified) name, a plain string, or a `-`/`!` prefix on one of those. */
	private fun isConstant(node: ASTNode): Boolean =
		when (node.elementType) {
			in constantTypes -> true
			ElementType.REFERENCE_EXPRESSION -> true
			ElementType.STRING_TEMPLATE ->
				node.children20.none {
					it.elementType in
						templateEntryTypes
				}
			ElementType.PREFIX_EXPRESSION -> node.lastChildNode?.let { isConstant(it) } ?: false
			ElementType.DOT_QUALIFIED_EXPRESSION, ElementType.SAFE_ACCESS_EXPRESSION ->
				node.receiver?.let { isConstant(it) } ==
					true &&
					node.selector?.elementType == ElementType.REFERENCE_EXPRESSION
			else -> false
		}

	/** `f()`, `f(1)`, `f(g())`, or the same on a plain-name receiver such as `wish.normalized()`; never `a().b()`. */
	private fun isSingleCall(node: ASTNode): Boolean =
		when (node.elementType) {
			ElementType.CALL_EXPRESSION -> isSimpleCall(node)
			ElementType.DOT_QUALIFIED_EXPRESSION, ElementType.SAFE_ACCESS_EXPRESSION ->
				node.receiver?.let { isConstant(it) } ==
					true &&
					node.selector?.let { isSimpleCall(it) } == true
			else -> false
		}

	private fun isSimpleCall(call: ASTNode): Boolean {
		if (call.elementType != ElementType.CALL_EXPRESSION ||
			call.findChildByType(ElementType.LAMBDA_ARGUMENT) != null
		)
			return false

		val arguments =
			call
				.findChildByType(ElementType.VALUE_ARGUMENT_LIST)
				?.children20
				?.filter { it.elementType == ElementType.VALUE_ARGUMENT }
				?.toList()
				?: emptyList()

		return arguments.size <= 1 && arguments.all { isSimpleArgument(it) }
	}

	private fun isSimpleArgument(argument: ASTNode): Boolean {
		val value = argument.lastChildNode ?: return false

		return isConstant(value) || isZeroArgumentCall(value)
	}

	private fun isZeroArgumentCall(node: ASTNode): Boolean =
		node.elementType == ElementType.CALL_EXPRESSION &&
			node.findChildByType(ElementType.LAMBDA_ARGUMENT) == null &&
			node
				.findChildByType(
					ElementType.VALUE_ARGUMENT_LIST,
				)?.findChildByType(ElementType.VALUE_ARGUMENT) ==
			null

	private val templateEntryTypes =
		setOf(ElementType.SHORT_STRING_TEMPLATE_ENTRY, ElementType.LONG_STRING_TEMPLATE_ENTRY)

	private val ASTNode.receiver: ASTNode?
		get() = firstChildNode

	private val ASTNode.selector: ASTNode?
		get() = lastChildNode
}
