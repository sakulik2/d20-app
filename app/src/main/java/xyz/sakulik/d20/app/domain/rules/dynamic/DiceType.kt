package xyz.sakulik.d20.app.domain.rules.dynamic

enum class DiceShape {
    TRIANGLE,          // D4 正四面体
    CUBE,              // D6 立方体
    OCTAHEDRON,        // D8 正八面体
    D10_DELTOID,       // D10 鸢形十面体
    D12_DODECAHEDRON,  // D12 正十二面体 (正五边形)
    ICOSAHEDRON,       // D20 正二十面体
    D100_PERCENTILE    // D100 百分骰
}

enum class DiceType(
    val sides: Int,
    val label: String,
    val shape: DiceShape
) {
    D4(4, "d4", DiceShape.TRIANGLE),
    D6(6, "d6", DiceShape.CUBE),
    D8(8, "d8", DiceShape.OCTAHEDRON),
    D10(10, "d10", DiceShape.D10_DELTOID),
    D12(12, "d12", DiceShape.D12_DODECAHEDRON),
    D20(20, "d20", DiceShape.ICOSAHEDRON),
    D100(100, "d100", DiceShape.D100_PERCENTILE);

    companion object {
        fun fromSides(sides: Int): DiceType = values().find { it.sides == sides } ?: D20

        /**
         * 解析掷骰表达式 (如 "3d6+2" / "1d20" / "1d100") 提取主骰子类型与数量
         */
        fun parseFormula(formula: String): Pair<DiceType, Int> {
            val lower = formula.lowercase().trim()
            val regex = Regex("(\\d+)?d(\\d+)")
            val match = regex.find(lower)

            if (match != null) {
                val count = match.groupValues[1].toIntOrNull()?.coerceIn(1, 6) ?: 1
                val sides = match.groupValues[2].toIntOrNull() ?: 20
                val type = fromSides(sides)
                return Pair(type, count)
            }

            return Pair(D20, 1)
        }
    }
}
