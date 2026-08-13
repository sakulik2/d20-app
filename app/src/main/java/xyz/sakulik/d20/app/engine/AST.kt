package xyz.sakulik.d20.app.engine

/**
 * 抽象语法树节点基类
 */
sealed class ExpressionNode

/**
 * 纯数字常量节点，例如 "5"
 */
data class NumberNode(val value: Int) : ExpressionNode()

/**
 * 骰子节点，例如 "2d20kh1"
 * @param count 骰子数量表达式节点，支持 (1+1)d20 这样的语法
 * @param sides 骰子面数表达式节点，支持 1d(10+10) 这样的语法
 * @param modifiers 骰子修饰符列表，如 kh1, kl1, dh1, dl1
 */
data class DiceNode(
    val count: ExpressionNode,
    val sides: ExpressionNode,
    val modifiers: List<Modifier> = emptyList()
) : ExpressionNode()

/**
 * 二元运算节点 (+, -, *, /)
 */
data class BinaryOpNode(
    val left: ExpressionNode,
    val operator: String,
    val right: ExpressionNode
) : ExpressionNode()

/**
 * 骰子修饰符声明
 */
sealed class Modifier {
    /** 保持最高 N 个: khN */
    data class KeepHighest(val n: Int) : Modifier()
    /** 保持最低 N 个: klN */
    data class KeepLowest(val n: Int) : Modifier()
    /** 丢弃最高 N 个: dhN */
    data class DropHighest(val n: Int) : Modifier()
    /** 丢弃最低 N 个: dlN */
    data class DropLowest(val n: Int) : Modifier()
}
