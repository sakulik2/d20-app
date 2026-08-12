package xyz.sakulik.d20.app.engine

/**
 * 单个骰子的掷骰结果
 * @param value 掷出的数值
 * @param sides 骰子面数
 * @param isDropped 是否因规则（如 kh, kl）被丢弃
 */
data class Die(
    val value: Int,
    val sides: Int,
    val isDropped: Boolean = false
)

/**
 * 整个骰子表达式的最终计算结果
 * @param formula 原始公式字符串
 * @param total 计算后的最终总和
 * @param allRolls 本次表达式中产生的所有骰子记录
 */
data class RollResult(
    val formula: String,
    val total: Int,
    val allRolls: List<Die>
)
