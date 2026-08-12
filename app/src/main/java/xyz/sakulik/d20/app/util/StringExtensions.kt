package xyz.sakulik.d20.app.util

/**
 * 处理 JSON 字符串中的转义字符 (\n, \t, \", \\)
 */
fun String.unescapeJson(): String {
    if (!this.contains('\\')) return this
    val sb = StringBuilder()
    var i = 0
    while (i < this.length) {
        val c = this[i]
        if (c == '\\' && i + 1 < this.length) {
            when (this[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                else -> sb.append(this[i + 1])
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}
