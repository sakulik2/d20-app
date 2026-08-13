package xyz.sakulik.d20.app.util

object StreamingNarrativeExtractor {
    fun extract(rawJson: String): String {
        val keyIndex = rawJson.indexOf("\"narrative\"")
        if (keyIndex < 0) return ""
        val colonIndex = rawJson.indexOf(':', keyIndex + NARRATIVE_KEY_LENGTH)
        if (colonIndex < 0) return ""
        var index = colonIndex + 1
        while (index < rawJson.length && rawJson[index].isWhitespace()) index++
        if (index >= rawJson.length || rawJson[index] != '"') return ""
        index++

        val output = StringBuilder()
        while (index < rawJson.length) {
            when (val character = rawJson[index++]) {
                '"' -> return output.toString()
                '\\' -> {
                    if (index >= rawJson.length) return output.toString()
                    when (val escaped = rawJson[index++]) {
                        '"', '\\', '/' -> output.append(escaped)
                        'b' -> output.append('\b')
                        'f' -> output.append('\u000C')
                        'n' -> output.append('\n')
                        'r' -> output.append('\r')
                        't' -> output.append('\t')
                        'u' -> {
                            if (index + 4 > rawJson.length) return output.toString()
                            val hex = rawJson.substring(index, index + 4)
                            val decoded = hex.toIntOrNull(16) ?: return output.toString()
                            output.append(decoded.toChar())
                            index += 4
                        }
                        else -> return output.toString()
                    }
                }
                else -> output.append(character)
            }
        }
        return output.toString()
    }

    private const val NARRATIVE_KEY_LENGTH = 11
}
