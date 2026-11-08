package house

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.EditorConfig
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.EditorConfigProperty
import org.ec4j.core.model.PropertyType
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

/**
 * Local declarations form their own visual block: a blank line separates them from statements that are not declarations.
 * Consecutive "light" declarations may sit together (with at most one multi-line declaration in a row); a "heavy" one
 * (long, or with an argument list broken over several lines, or a local function/class) gets a blank line on both sides.
 *
 * ```
 * val caps = SurfaceCapabilities.new()
 *
 * vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.physical.raw, surface.handle, caps.raw).ensure("...")
 *
 * val extent = chooseExtent(caps, framebufferSize) ?: return@withStack null
 * val format = chooseFormat(device, surface)
 *
 * val info =
 *     SwapchainCreateInfo(
 *         surface = surface,
 *         ...
 *     )
 *
 * val swapchain = VkSwapchain(Vk.create(::vkCreateSwapchainKHR, info.raw))
 * ```
 */
class LocalDeclarationSpacingRule :
	Rule(
		ruleId = HouseRuleSetProvider.ruleId("local-declaration-spacing"),
		about = HouseRuleSetProvider.ABOUT,
		usesEditorConfigProperties = setOf(MAX_LIGHT_DECLARATION_LINES),
	),
	RuleAutocorrectApproveHandler {
	private var maxLightDeclarationLines = MAX_LIGHT_DECLARATION_LINES.defaultValue

	override fun beforeFirstNode(editorConfig: EditorConfig) {
		maxLightDeclarationLines = editorConfig[MAX_LIGHT_DECLARATION_LINES]
	}

	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (
			offset: Int,
			errorMessage: String,
			canBeAutoCorrected: Boolean,
		) -> AutocorrectDecision,
	) {
		if (node.elementType != ElementType.BLOCK)
			return

		for (pair in Statements.adjacentPairs(node))
			check(pair, emit)
	}

	private fun check(
		pair: AdjacentStatements,
		emit: (
			offset: Int,
			errorMessage: String,
			canBeAutoCorrected: Boolean,
		) -> AutocorrectDecision,
	) {
		val first = Statements.kindOf(pair.first, maxLightDeclarationLines)
		val second = Statements.kindOf(pair.second, maxLightDeclarationLines)

		val message =
			when {
				first == StatementKind.HeavyDeclaration -> "A blank line is required after a multi-line local declaration"
				second == StatementKind.HeavyDeclaration -> "A blank line is required before a multi-line local declaration"
				first.isDeclaration && !second.isDeclaration ->
					"A blank line is required between local declarations and the statement that follows them"
				second.isDeclaration && !first.isDeclaration ->
					"A blank line is required between a statement and the local declarations that follow it"
				first.isDeclaration &&
					!first.groupsWith(
						second,
					)
				-> "A blank line is required between two multi-line local declarations"
				else -> return
			}

		pair.requireBlankLine(message, emit)
	}

	companion object {
		/** Declarations spanning more lines than this are "heavy" and get blank lines on both sides. */
		val MAX_LIGHT_DECLARATION_LINES: EditorConfigProperty<Int> =
			EditorConfigProperty(
				type =
					PropertyType.LowerCasingPropertyType(
						"ktlint_house_max_light_declaration_lines",
						"Maximum number of lines a local declaration may span and still be grouped with its neighbours",
						PropertyType.PropertyValueParser.POSITIVE_INT_VALUE_PARSER,
						emptySet(),
					),
				defaultValue = 8,
			)
	}
}

/**
 * A `return` closes a function's story, so it stands apart from the statements before it:
 *
 * ```
 * if (input.isHeld(Action.Left))
 *     wish -= cam.right
 *
 * return if (wish.length() > 1e-6f)
 *     wish.normalized()
 * else
 *     Vec3.ZERO
 * ```
 */
class ReturnSpacingRule :
	Rule(
		ruleId = HouseRuleSetProvider.ruleId("return-spacing"),
		about = HouseRuleSetProvider.ABOUT,
	),
	RuleAutocorrectApproveHandler {
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (
			offset: Int,
			errorMessage: String,
			canBeAutoCorrected: Boolean,
		) -> AutocorrectDecision,
	) {
		if (node.elementType != ElementType.BLOCK)
			return

		Statements
			.adjacentPairs(node)
			.filter { it.second.elementType == ElementType.RETURN }
			.forEach {
				it.requireBlankLine("A blank line is required before a return statement", emit)
			}
	}
}
