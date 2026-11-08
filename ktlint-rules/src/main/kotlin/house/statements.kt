package house

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.children20
import com.pinterest.ktlint.rule.engine.core.api.ifAutocorrectAllowed
import com.pinterest.ktlint.rule.engine.core.api.isWhiteSpace20
import com.pinterest.ktlint.rule.engine.core.api.isWhiteSpaceWithNewline20
import com.pinterest.ktlint.rule.engine.core.api.nextSibling20
import com.pinterest.ktlint.rule.engine.core.api.replaceTextWith
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

/** What a statement inside a `{ ... }` block is, as far as vertical spacing is concerned. */
sealed interface StatementKind {
	/** A `val`/`var`/destructuring declaration small enough to sit directly against neighbouring declarations. */
	data class LightDeclaration(
		val isMultiLine: Boolean,
	) : StatementKind

	/** A declaration that earns a blank line on both sides: multi-line argument lists, long initialisers, local functions. */
	data object HeavyDeclaration : StatementKind

	/** A `return`. */
	data object Return : StatementKind

	/** Anything else: calls, assignments, conditionals, loops. */
	data object Other : StatementKind

	val isDeclaration: Boolean
		get() = this is LightDeclaration || this == HeavyDeclaration

	/** Two multi-line declarations next to each other read as one lump; a blank line lets each be its own block. */
	fun groupsWith(other: StatementKind): Boolean =
		this is LightDeclaration && other is LightDeclaration &&
			!(isMultiLine && other.isMultiLine)
}

/** Two statements that follow each other in the same block, plus the whitespace that separates them. */
class AdjacentStatements(
	val first: ASTNode,
	val second: ASTNode,
	/** The first whitespace containing a newline between the two; null when they share a line (`a(); b()`). */
	val separator: ASTNode?,
) {
	val hasBlankLine: Boolean
		get() = separator?.text?.count { it == '\n' }?.let { it >= 2 } ?: false

	/** Turns the separator into exactly one blank line while keeping the indentation of the second statement. */
	fun insertBlankLine() {
		val whitespace = separator ?: return
		val indent = whitespace.text.substringAfterLast('\n')

		whitespace.replaceTextWith("\n\n$indent")
	}
}

/** Sees the statements of a block as a list, ignoring braces, whitespace and comments. */
object Statements {
	private val declarationTypes =
		setOf(ElementType.PROPERTY, ElementType.DESTRUCTURING_DECLARATION)

	private val localMemberTypes =
		setOf(
			ElementType.FUN,
			ElementType.CLASS,
			ElementType.OBJECT_DECLARATION,
			ElementType.TYPEALIAS,
		)

	private val ignoredTypes =
		setOf(ElementType.LBRACE, ElementType.RBRACE, ElementType.SEMICOLON)

	fun of(block: ASTNode): List<ASTNode> =
		block.children20
			.filter { !it.isWhiteSpace20 && !it.isComment && it.elementType !in ignoredTypes }
			.toList()

	fun adjacentPairs(block: ASTNode): List<AdjacentStatements> =
		of(block).zipWithNext {
			first,
			second,
			->
			AdjacentStatements(first, second, separatorBetween(first, second))
		}

	fun kindOf(statement: ASTNode, maxLightDeclarationLines: Int): StatementKind =
		when (statement.elementType) {
			in localMemberTypes -> StatementKind.HeavyDeclaration
			in declarationTypes ->
				if (isHeavy(statement, maxLightDeclarationLines))
					StatementKind.HeavyDeclaration
				else
					StatementKind.LightDeclaration(isMultiLine = '\n' in statement.text)
			ElementType.RETURN -> StatementKind.Return
			else -> StatementKind.Other
		}

	/** A declaration is heavy when it spans many lines or breaks an argument list across lines. */
	private fun isHeavy(declaration: ASTNode, maxLightDeclarationLines: Int): Boolean {
		val lines = declaration.text.count { it == '\n' } + 1

		return lines > maxLightDeclarationLines || declaration.hasMultilineArgumentList
	}

	private val ASTNode.hasMultilineArgumentList: Boolean
		get() =
			generateSequence(firstChildNode) { it.treeNext }
				.flatMap { it.selfAndDescendants }
				.any { it.elementType == ElementType.VALUE_ARGUMENT_LIST && '\n' in it.text }

	private val ASTNode.selfAndDescendants: Sequence<ASTNode>
		get() = sequenceOf(this) + children20.flatMap { it.selfAndDescendants }

	private fun separatorBetween(first: ASTNode, second: ASTNode): ASTNode? =
		generateSequence(first.nextSibling20) { it.nextSibling20 }
			.takeWhile { it != second }
			.firstOrNull { it.isWhiteSpaceWithNewline20 }

	private val ASTNode.isComment: Boolean
		get() =
			elementType == ElementType.EOL_COMMENT || elementType == ElementType.BLOCK_COMMENT ||
				elementType == ElementType.KDOC
}

/** Reports a missing blank line before [pair].second and inserts it when autocorrect is allowed. */
fun AdjacentStatements.requireBlankLine(
	message: String,
	emit: (
		offset: Int,
		errorMessage: String,
		canBeAutoCorrected: Boolean,
	) -> AutocorrectDecision,
) {
	if (separator == null || hasBlankLine)
		return

	emit(second.startOffset, message, true).ifAutocorrectAllowed { insertBlankLine() }
}
