package xyz.sakulik.d20.app.domain.rules.dynamic

import xyz.sakulik.d20.app.engine.RollResult

enum class DiceSubmissionSource {
    VIRTUAL,
    MANUAL,
    LEGACY
}

data class RuleError(
    val code: String,
    val message: String,
    val nodeId: String? = null
)

data class DiceTermResult(
    val value: Int,
    val sides: Int,
    val isDropped: Boolean = false
)

data class DiceSubmission(
    val expression: String,
    val terms: List<DiceTermResult>,
    val total: Int,
    val source: DiceSubmissionSource
) {
    val keptTerms: List<DiceTermResult>
        get() = terms.filterNot { it.isDropped }

    fun validate(): RuleError? {
        if (expression.isBlank()) {
            return RuleError("INVALID_DICE_EXPRESSION", "骰子表达式不能为空")
        }
        if (source != DiceSubmissionSource.VIRTUAL) return validateFinalResult(expression)
        if (terms.isEmpty()) return RuleError("MISSING_DICE_TERMS", "虚拟骰结果缺少逐骰明细")
        val invalidTerm = terms.firstOrNull { it.sides <= 0 || it.value !in 1..it.sides }
        if (invalidTerm != null) {
            return RuleError(
                "DICE_VALUE_OUT_OF_RANGE",
                "骰点 ${invalidTerm.value} 不在 d${invalidTerm.sides} 的有效范围内"
            )
        }
        if (keptTerms.isEmpty()) {
            return RuleError("NO_KEPT_DICE", "骰子结果没有保留任何骰子")
        }
        return null
    }

    fun validateAgainst(expectedExpression: String): RuleError? {
        validate()?.let { return it }
        if (expression.normalized() != expectedExpression.normalized()) {
            return RuleError(
                "DICE_EXPRESSION_MISMATCH",
                "提交的骰式 $expression 与要求的骰式 $expectedExpression 不一致"
            )
        }
        val parsed = parseExpression(expectedExpression) ?: return RuleError(
            "UNSUPPORTED_DICE_SUBMISSION",
            "当前检定暂不支持校验复杂骰式 $expectedExpression"
        )
        if (source != DiceSubmissionSource.VIRTUAL) {
            return validateFinalResult(expectedExpression)
        }
        val expectedTermCount = parsed.groups.sumOf { it.count }
        if (terms.size != expectedTermCount) {
            return RuleError(
                "DICE_TERM_COUNT_MISMATCH",
                "骰式 $expectedExpression 需要 $expectedTermCount 枚骰子，实际提交 ${terms.size} 枚"
            )
        }
        var cursor = 0
        var expectedTotal = parsed.constant
        for (group in parsed.groups) {
            val groupTerms = terms.subList(cursor, cursor + group.count)
            if (groupTerms.any { it.sides != group.sides }) {
                return RuleError(
                    "DICE_SIDES_MISMATCH",
                    "骰式 $expectedExpression 的第 ${cursor + 1} 枚起必须提交 d${group.sides} 结果"
                )
            }
            val keptValues = groupTerms.filterNot { it.isDropped }.map { it.value }
            val keepIsValid = when (group.keep) {
                KeepMode.ALL -> keptValues.size == groupTerms.size
                KeepMode.HIGHEST -> keptValues.size == 1 && keptValues.single() == groupTerms.maxOf { it.value }
                KeepMode.LOWEST -> keptValues.size == 1 && keptValues.single() == groupTerms.minOf { it.value }
            }
            if (!keepIsValid) {
                return RuleError(
                    "INVALID_KEPT_DICE",
                    "骰式 $expectedExpression 的保留骰不符合规则"
                )
            }
            expectedTotal += group.sign * keptValues.sum()
            cursor += group.count
        }
        if (total != expectedTotal) {
            return RuleError(
                "DICE_TOTAL_MISMATCH",
                "骰子总值 $total 与骰式计算结果 $expectedTotal 不一致"
            )
        }
        return null
    }

    private fun validateFinalResult(expectedExpression: String): RuleError? {
        val parsed = parseExpression(expectedExpression) ?: return RuleError(
            "UNSUPPORTED_DICE_SUBMISSION",
            "当前检定暂不支持校验复杂骰式 $expectedExpression"
        )
        var minimum = parsed.constant
        var maximum = parsed.constant
        parsed.groups.forEach { group ->
            val groupMinimum = if (group.keep == KeepMode.ALL) group.count else 1
            val groupMaximum = if (group.keep == KeepMode.ALL) {
                group.count * group.sides
            } else {
                group.sides
            }
            if (group.sign > 0) {
                minimum += groupMinimum
                maximum += groupMaximum
            } else {
                minimum -= groupMaximum
                maximum -= groupMinimum
            }
        }
        val validRange = minimum..maximum
        if (total !in validRange) {
            return RuleError(
                "DICE_TOTAL_OUT_OF_RANGE",
                "线下骰最终结果 $total 不在骰式 $expectedExpression 的有效范围 ${validRange.first}..${validRange.last} 内"
            )
        }
        return null
    }

    companion object {
        fun fromRollResult(result: RollResult): DiceSubmission {
            return DiceSubmission(
                expression = result.formula,
                terms = result.allRolls.map { die ->
                    DiceTermResult(
                        value = die.value,
                        sides = die.sides,
                        isDropped = die.isDropped
                    )
                },
                total = result.total,
                source = DiceSubmissionSource.VIRTUAL
            )
        }

        fun manual(expression: String, finalResult: Int): DiceSubmission {
            return DiceSubmission(
                expression = expression,
                terms = emptyList(),
                total = finalResult,
                source = DiceSubmissionSource.MANUAL
            )
        }

        fun virtual(expression: String, values: List<Int>): DiceSubmission {
            return fromValues(expression, values, DiceSubmissionSource.VIRTUAL)
        }

        private fun fromValues(
            expression: String,
            values: List<Int>,
            source: DiceSubmissionSource
        ): DiceSubmission {
            val parsed = parseSimpleExpression(expression)
            val terms = if (parsed == null) {
                values.map { value -> DiceTermResult(value = value, sides = 0) }
            } else {
                val droppedIndexes = when (parsed.keep) {
                    KeepMode.HIGHEST -> values.indices
                        .sortedByDescending { values[it] }
                        .drop(1)
                        .toSet()
                    KeepMode.LOWEST -> values.indices
                        .sortedBy { values[it] }
                        .drop(1)
                        .toSet()
                    KeepMode.ALL -> emptySet()
                }
                values.mapIndexed { index, value ->
                    DiceTermResult(
                        value = value,
                        sides = parsed.sides,
                        isDropped = index in droppedIndexes
                    )
                }
            }
            return DiceSubmission(
                expression = expression,
                terms = terms,
                total = terms.filterNot { it.isDropped }.sumOf { it.value },
                source = source
            )
        }

        fun legacy(expression: String, value: Int): DiceSubmission {
            return DiceSubmission(
                expression = expression,
                terms = emptyList(),
                total = value,
                source = DiceSubmissionSource.LEGACY
            )
        }

        private fun parseSimpleExpression(expression: String): SimpleDiceExpression? {
            val match = SIMPLE_DICE_REGEX.matchEntire(expression.trim().lowercase()) ?: return null
            val count = match.groupValues[1].ifBlank { "1" }.toIntOrNull() ?: return null
            val sides = match.groupValues[2].toIntOrNull() ?: return null
            val keep = when (match.groupValues[3]) {
                "kh1" -> KeepMode.HIGHEST
                "kl1" -> KeepMode.LOWEST
                else -> KeepMode.ALL
            }
            val modifier = match.groupValues[4].toIntOrNull() ?: 0
            return SimpleDiceExpression(
                count = count,
                sides = sides,
                keep = keep,
                modifier = modifier
            )
        }

        private val SIMPLE_DICE_REGEX = Regex("^(\\d*)d(\\d+)(kh1|kl1)?([+-]\\d+)?$")

        private fun parseExpression(expression: String): ParsedDiceExpression? {
            val normalized = expression.normalized()
            if (normalized.isBlank()) return null
            val tokenMatches = ADDITIVE_TOKEN_REGEX.findAll(normalized).toList()
            if (tokenMatches.isEmpty() || tokenMatches.joinToString("") { it.value } != normalized) {
                return null
            }
            val groups = mutableListOf<DiceGroup>()
            var constant = 0
            tokenMatches.forEach { tokenMatch ->
                val token = tokenMatch.value
                val sign = if (token.startsWith('-')) -1 else 1
                val unsigned = token.removePrefix("+").removePrefix("-")
                val diceMatch = DICE_GROUP_REGEX.matchEntire(unsigned)
                if (diceMatch == null) {
                    constant += sign * (unsigned.toIntOrNull() ?: return null)
                } else {
                    val count = diceMatch.groupValues[1].ifBlank { "1" }.toIntOrNull() ?: return null
                    val sides = diceMatch.groupValues[2].toIntOrNull() ?: return null
                    if (count <= 0 || sides <= 0) return null
                    val keep = when (diceMatch.groupValues[3]) {
                        "kh1" -> KeepMode.HIGHEST
                        "kl1" -> KeepMode.LOWEST
                        else -> KeepMode.ALL
                    }
                    groups += DiceGroup(count, sides, keep, sign)
                }
            }
            return ParsedDiceExpression(groups.takeIf { it.isNotEmpty() } ?: return null, constant)
        }

        private val ADDITIVE_TOKEN_REGEX = Regex("[+-]?[^+-]+")
        private val DICE_GROUP_REGEX = Regex("(\\d*)d(\\d+)(kh1|kl1)?")
    }
}

private fun String.normalized(): String = lowercase().filterNot(Char::isWhitespace)

private data class SimpleDiceExpression(
    val count: Int,
    val sides: Int,
    val keep: KeepMode,
    val modifier: Int
)

private data class ParsedDiceExpression(
    val groups: List<DiceGroup>,
    val constant: Int
)

private data class DiceGroup(
    val count: Int,
    val sides: Int,
    val keep: KeepMode,
    val sign: Int
)

private enum class KeepMode {
    ALL,
    HIGHEST,
    LOWEST
}
