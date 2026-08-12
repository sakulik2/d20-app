package xyz.sakulik.d20.app.domain.common.updater

object PluginSources {
    private const val RULESET_INDEX_URL =
        "https://raw.githubusercontent.com/sakulik/d20-rulesets/main/index.json"
    private const val WORLDVIEW_INDEX_URL =
        "https://raw.githubusercontent.com/sakulik/d20-worldviews/main/index.json"

    fun indexUrl(type: PluginType): String = when (type) {
        PluginType.RULESET -> RULESET_INDEX_URL
        PluginType.WORLDVIEW -> WORLDVIEW_INDEX_URL
    }
}
