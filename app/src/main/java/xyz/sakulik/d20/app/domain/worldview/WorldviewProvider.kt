package xyz.sakulik.d20.app.domain.worldview

import kotlinx.serialization.json.Json

object WorldviewProvider {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseManifest(jsonStr: String): WorldviewManifest? {
        return try {
            json.decodeFromString<WorldviewManifest>(jsonStr)
        } catch (e: Exception) {
            null
        }
    }
}
