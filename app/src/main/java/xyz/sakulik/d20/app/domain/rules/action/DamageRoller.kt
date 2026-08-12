package xyz.sakulik.d20.app.domain.rules.action

import xyz.sakulik.d20.app.engine.Die
import xyz.sakulik.d20.app.engine.roll

data class DamageRoll(
    val formula: String,
    val total: Int,
    val dice: List<Die>
)

object DamageRoller {
    fun rollFormula(formula: String, critical: Boolean = false): DamageRoll {
        val normalized = formula.trim().lowercase()
        require(normalized.isNotBlank()) { "伤害公式不能为空" }
        val result = roll(if (critical) criticalFormula(normalized) else normalized)
        require(result.allRolls.isNotEmpty()) { "伤害公式必须包含至少一枚骰子：$formula" }
        return DamageRoll(
            formula = formula,
            total = result.total,
            dice = result.allRolls
        )
    }

    fun criticalFormula(formula: String): String {
        val diceTerms = DICE_TERM.findAll(formula).toList()
        require(diceTerms.isNotEmpty()) { "伤害公式必须包含骰子：$formula" }
        val criticalFormula = buildString {
            var cursor = 0
            diceTerms.forEach { match ->
                append(formula.substring(cursor, match.range.first))
                val count = match.groupValues[1].ifBlank { "1" }.toInt()
                append(count * 2)
                append('d')
                append(match.groupValues[2])
                cursor = match.range.last + 1
            }
            append(formula.substring(cursor))
        }
        return criticalFormula
    }

    private val DICE_TERM = Regex("(\\d*)d(\\d+)", RegexOption.IGNORE_CASE)
}
