package xyz.sakulik.d20.app.engine

import kotlin.random.Random

/**
 * 掷骰执行器：遍历 AST 并进行求值
 */
class Evaluator(private val formula: String) {

    fun evaluate(node: ExpressionNode): RollResult {
        return when (node) {
            is NumberNode -> RollResult(node.value.toString(), node.value, emptyList())
            is DiceNode -> rollDice(node)
            is BinaryOpNode -> evaluateBinaryOp(node)
        }
    }

    private fun rollDice(node: DiceNode): RollResult {
        val count = evaluate(node.count).total
        val sides = evaluate(node.sides).total
        
        if (count <= 0 || sides <= 0) return RollResult("0", 0, emptyList())

        // 执行掷骰
        val rawRolls = mutableListOf<Die>()
        repeat(count) {
            rawRolls.add(Die(Random.nextInt(1, sides + 1), sides))
        }

        // 处理 kh、kl、dh、dl 修饰符
        var processedRolls = rawRolls.toList()
        node.modifiers.forEach { modifier ->
            processedRolls = applyModifier(processedRolls, modifier)
        }

        val total = processedRolls.filter { !it.isDropped }.sumOf { it.value }
        
        return RollResult(
            formula = "", // 这里简单处理，外层会重新包装
            total = total,
            allRolls = processedRolls
        )
    }

    private fun applyModifier(rolls: List<Die>, modifier: Modifier): List<Die> {
        // 找出还没被丢弃的骰子
        val activeIndices = rolls.indices.filter { !rolls[it].isDropped }
        if (activeIndices.isEmpty()) return rolls

        val sortedIndices = activeIndices.sortedByDescending { rolls[it].value }
        
        val toDropIndices = mutableSetOf<Int>()

        when (modifier) {
            is Modifier.KeepHighest -> {
                // 将除了最高的 N 个以外的所有活跃骰子丢弃
                val n = modifier.n
                if (activeIndices.size > n) {
                    toDropIndices.addAll(sortedIndices.drop(n))
                }
            }
            is Modifier.KeepLowest -> {
                val n = modifier.n
                if (activeIndices.size > n) {
                    toDropIndices.addAll(sortedIndices.dropLast(n).reversed())
                }
            }
            is Modifier.DropHighest -> {
                val n = modifier.n
                toDropIndices.addAll(sortedIndices.take(n))
            }
            is Modifier.DropLowest -> {
                val n = modifier.n
                toDropIndices.addAll(sortedIndices.takeLast(n))
            }
        }

        return rolls.mapIndexed { index, die ->
            if (toDropIndices.contains(index)) die.copy(isDropped = true) else die
        }
    }

    private fun evaluateBinaryOp(node: BinaryOpNode): RollResult {
        val leftRes = evaluate(node.left)
        val rightRes = evaluate(node.right)

        val total = when (node.operator) {
            "+" -> leftRes.total + rightRes.total
            "-" -> leftRes.total - rightRes.total
            "*" -> leftRes.total * rightRes.total
            "/" -> if (rightRes.total != 0) leftRes.total / rightRes.total else 0
            else -> 0
        }

        return RollResult(
            formula = "",
            total = total,
            allRolls = leftRes.allRolls + rightRes.allRolls
        )
    }
}

/**
 * 方便调用的入口函数
 */
fun roll(formula: String): RollResult {
    val tokens = Lexer(formula).tokenize()
    val ast = Parser(tokens).parse()
    val evaluator = Evaluator(formula)
    val result = evaluator.evaluate(ast)
    return result.copy(formula = formula)
}
