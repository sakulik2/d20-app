package xyz.sakulik.d20.app.engine

/**
 * 语法分析器：使用递归下降法构建 AST
 */
class Parser(private val tokens: List<Token>) {
    private var current = 0

    private fun peek(): Token = tokens[current]
    private fun advance(): Token = tokens[current++]
    private fun match(type: TokenType): Boolean {
        if (peek().type == type) {
            advance()
            return true
        }
        return false
    }

    private fun consume(type: TokenType, message: String): Token {
        if (peek().type == type) return advance()
        throw IllegalArgumentException(message + " at ${peek().value}")
    }

    fun parse(): ExpressionNode {
        return parseExpression()
    }

    // Expression = Term { ('+' | '-') Term }
    private fun parseExpression(): ExpressionNode {
        var node = parseTerm()
        while (match(TokenType.PLUS) || match(TokenType.MINUS)) {
            val operator = tokens[current - 1].value
            val right = parseTerm()
            node = BinaryOpNode(node, operator, right)
        }
        return node
    }

    // Term = Factor { ('*' | '/') Factor }
    private fun parseTerm(): ExpressionNode {
        var node = parseFactor()
        while (match(TokenType.MULTIPLY) || match(TokenType.DIVIDE)) {
            val operator = tokens[current - 1].value
            val right = parseFactor()
            node = BinaryOpNode(node, operator, right)
        }
        return node
    }

    // Factor = Primary [ 'd' Primary { Modifier } ]
    private fun parseFactor(): ExpressionNode {
        var node = parsePrimary()
        if (match(TokenType.DICE)) {
            val sides = parsePrimary()
            val modifiers = mutableListOf<Modifier>()
            while (peek().type in listOf(TokenType.KH, TokenType.KL, TokenType.DH, TokenType.DL)) {
                val modToken = advance()
                val n = if (peek().type == TokenType.NUMBER) advance().value.toInt() else 1
                when (modToken.type) {
                    TokenType.KH -> modifiers.add(Modifier.KeepHighest(n))
                    TokenType.KL -> modifiers.add(Modifier.KeepLowest(n))
                    TokenType.DH -> modifiers.add(Modifier.DropHighest(n))
                    TokenType.DL -> modifiers.add(Modifier.DropLowest(n))
                    else -> {}
                }
            }
            node = DiceNode(node, sides, modifiers)
        }
        return node
    }

    // Primary = '(' Expression ')' | NUMBER
    private fun parsePrimary(): ExpressionNode {
        if (match(TokenType.LPAREN)) {
            val node = parseExpression()
            consume(TokenType.RPAREN, "期望 ')'")
            return node
        }
        if (match(TokenType.NUMBER)) {
            return NumberNode(tokens[current - 1].value.toInt())
        }
        // 如果是直接 d20，补齐数量为 1
        if (peek().type == TokenType.DICE) {
            return NumberNode(1)
        }
        throw IllegalArgumentException("语法错误，位置: ${peek().value}")
    }
}
