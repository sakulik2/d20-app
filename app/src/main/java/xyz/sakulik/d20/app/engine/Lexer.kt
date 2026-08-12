package xyz.sakulik.d20.app.engine

/**
 * 词法标记类型
 */
enum class TokenType {
    NUMBER,   // 数字
    DICE,     // 'd' 或 'D'
    PLUS,     // '+'
    MINUS,    // '-'
    MULTIPLY, // '*'
    DIVIDE,   // '/'
    LPAREN,   // '('
    RPAREN,   // ')'
    KH,       // 'kh'
    KL,       // 'kl'
    DH,       // 'dh'
    DL,       // 'dl'
    EOF       // 结束符
}

data class Token(val type: TokenType, val value: String)

/**
 * 词法分析器：将公式字符串转换成 Token 流
 */
class Lexer(private val input: String) {
    private var pos = 0

    private fun peek(): Char? = if (pos < input.length) input[pos] else null
    private fun advance(): Char? = if (pos < input.length) input[pos++] else null

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (pos < input.length) {
            val char = peek()!!
            when {
                char.isWhitespace() -> { advance() }
                char.isDigit() -> tokens.add(readNumber())
                char == 'd' || char == 'D' -> {
                    // 需要区分是 'd' (掷骰) 还是 'dh'/'dl' (修饰符)
                    tokens.add(readDiceOrModifier())
                }
                char == 'k' || char == 'K' -> tokens.add(readModifier('k'))
                char == '+' -> { advance(); tokens.add(Token(TokenType.PLUS, "+")) }
                char == '-' -> { advance(); tokens.add(Token(TokenType.MINUS, "-")) }
                char == '*' -> { advance(); tokens.add(Token(TokenType.MULTIPLY, "*")) }
                char == '/' -> { advance(); tokens.add(Token(TokenType.DIVIDE, "/")) }
                char == '(' -> { advance(); tokens.add(Token(TokenType.LPAREN, "(")) }
                char == ')' -> { advance(); tokens.add(Token(TokenType.RPAREN, ")")) }
                else -> throw IllegalArgumentException("未知字符: $char at position $pos")
            }
        }
        tokens.add(Token(TokenType.EOF, ""))
        return tokens
    }

    private fun readNumber(): Token {
        val sb = StringBuilder()
        while (peek()?.isDigit() == true) {
            sb.append(advance())
        }
        return Token(TokenType.NUMBER, sb.toString())
    }

    private fun readDiceOrModifier(): Token {
        advance() // 跳过 'd'
        val next = peek()
        return when (next?.lowercaseChar()) {
            'h' -> { advance(); Token(TokenType.DH, "dh") }
            'l' -> { advance(); Token(TokenType.DL, "dl") }
            else -> Token(TokenType.DICE, "d")
        }
    }

    private fun readModifier(prefix: Char): Token {
        advance() // 跳过 'k'
        val next = peek()
        return when (next?.lowercaseChar()) {
            'h' -> { advance(); Token(TokenType.KH, "kh") }
            'l' -> { advance(); Token(TokenType.KL, "kl") }
            else -> throw IllegalArgumentException("无效修饰符: $prefix$next")
        }
    }
}
