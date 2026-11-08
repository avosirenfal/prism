package house

import com.pinterest.ktlint.cli.ruleset.core.api.RuleSetProviderV3
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import com.pinterest.ktlint.rule.engine.core.api.RuleSetId

/**
 * The `house` rule set: formatting rules that make "each line a logical unit and each visual block a logical group".
 *
 * Loaded by the ktlint CLI through `META-INF/services` (see the `-R` flag) and by the Gradle `ktlintCheck`/`ktlintFormat`
 * tasks in the root build. Every rule is autocorrectable and configurable from `.editorconfig`.
 */
class HouseRuleSetProvider : RuleSetProviderV3(ID) {
	override fun getRuleProviders(): Set<RuleProvider> =
		setOf(
			RuleProvider { LocalDeclarationSpacingRule() },
			RuleProvider { ReturnSpacingRule() },
			RuleProvider { BracelessConditionalWrappingRule() },
		)

	companion object {
		val ID = RuleSetId("house")

		val ABOUT =
			Rule.About(
				maintainer = "barion",
				repositoryUrl = "https://github.com/avosirenfal/barion",
				issueTrackerUrl = "https://github.com/avosirenfal/barion/issues",
			)

		fun ruleId(name: String) = RuleId("${ID.value}:$name")
	}
}
