package xyz.sakulik.d20.app.util

/**
 * TRPG 检定需求解析与规范化引擎
 * 统一处理大模型返回的各类不一致语法（如 "感知（察觉）", "**力量** 判定", "智力(奥秘): 解读" 等）
 * 并完整保留检定需求与上下文信息。
 */
object CheckReasonParser {

    data class ParsedCheck(
        val skillName: String,
        val detailContext: String?,
        val displayTitle: String,
        val formattedContent: String
    )

    private val skillMap = mapOf(
        "感知（察觉）" to "察觉",
        "感知(察觉)" to "察觉",
        "感知-察觉" to "察觉",
        "感知察觉" to "察觉",
        "力量（运动）" to "运动",
        "力量(运动)" to "运动",
        "敏捷（体操）" to "体操",
        "敏捷(体操)" to "体操",
        "敏捷（隐匿）" to "隐匿",
        "敏捷(隐匿)" to "隐匿",
        "敏捷（手法）" to "手法",
        "智力（调查）" to "调查",
        "智力(调查)" to "调查",
        "智力（奥秘）" to "奥秘",
        "智力(奥秘)" to "奥秘",
        "智力（历史）" to "历史",
        "智力(历史)" to "历史",
        "智力（自然）" to "自然",
        "智力（宗教）" to "宗教",
        "感知（洞察）" to "洞察",
        "感知(洞察)" to "洞察",
        "感知（医术）" to "医术",
        "感知（生存）" to "生存",
        "魅力（游说）" to "游说",
        "魅力(游说)" to "游说",
        "魅力（欺瞒）" to "欺瞒",
        "魅力（威吓）" to "威吓",
        "魅力（表演）" to "表演",
        "侦查" to "侦查",
        "聆听" to "聆听",
        "理智" to "理智",
        "意志" to "意志",
        "心理学" to "心理学",
        "闪避" to "闪避"
    )

    /**
     * 解析与规范化 LLM 传入的原始检定 Reason，并保留完整需求描述
     */
    fun parse(rawReason: String, resultLevelCn: String = "", rollValueStr: String = "", dcStr: String = ""): ParsedCheck {
        // 1. 剥离 Markdown 符号与乱码
        var clean = rawReason
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .replace("🎲", "")
            .replace("✅", "")
            .replace("❌", "")
            .replace("🌟", "")
            .replace("💀", "")
            .trim()

        // 2. 提取拆分需求上下文
        var detailContext: String? = null
        if (clean.contains("：") || clean.contains(":") || clean.contains(" - ")) {
            val delimiter = when {
                clean.contains("：") -> "："
                clean.contains(":") -> ":"
                else -> " - "
            }
            val parts = clean.split(delimiter, limit = 2)
            val head = parts[0].trim()
            val tail = parts.getOrNull(1)?.trim()?.ifBlank { null }

            var foundSkill: String? = null
            for ((alias, canonical) in skillMap) {
                if (head.contains(alias) || tail?.contains(alias) == true) {
                    foundSkill = canonical
                    break
                }
            }

            clean = foundSkill ?: head.removeSuffix("判定").removeSuffix("检定").trim()
            detailContext = tail?.removeSuffix("判定")?.removeSuffix("检定")?.trim()
        } else {
            for ((alias, canonical) in skillMap) {
                if (clean.contains(alias)) {
                    val sub = clean.replace(alias, "").removeSuffix("判定").removeSuffix("检定").trim()
                    if (sub.isNotBlank()) {
                        detailContext = sub
                    }
                    clean = canonical
                    break
                }
            }
            clean = clean.removeSuffix("判定").removeSuffix("检定").removeSuffix("掷骰").trim()
        }

        if (clean.isBlank()) {
            clean = "属性"
        }

        // 3. 构建保留完整需求的规范标题
        val displayTitle = if (!detailContext.isNullOrBlank() && detailContext != clean) {
            "${clean}检定 - $detailContext"
        } else {
            "${clean}检定"
        }

        val formattedContent = if (resultLevelCn.isNotBlank()) {
            val dcSuffix = if (dcStr.isNotBlank()) ", $dcStr" else ""
            "**$displayTitle**：$resultLevelCn (点数: $rollValueStr$dcSuffix)"
        } else {
            val dcSuffix = if (dcStr.isNotBlank()) " ($dcStr)" else ""
            "**检定需求：$displayTitle**$dcSuffix"
        }

        return ParsedCheck(
            skillName = clean,
            detailContext = detailContext,
            displayTitle = displayTitle,
            formattedContent = formattedContent
        )
    }
}
