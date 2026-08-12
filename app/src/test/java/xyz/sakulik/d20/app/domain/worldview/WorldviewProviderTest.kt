package xyz.sakulik.d20.app.domain.worldview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorldviewProviderTest {
    @Test
    fun parsesValidManifest() {
        val manifest = WorldviewProvider.parseManifest(
            """{"id":"fantasy","name":"Fantasy","version":"1.0","compatibleRulesets":["dnd_5e"]}"""
        )

        assertEquals("fantasy", manifest?.id)
    }

    @Test
    fun rejectsUnknownFields() {
        val manifest = WorldviewProvider.parseManifest(
            """{"id":"fantasy","name":"Fantasy","unknown":true}"""
        )

        assertNull(manifest)
    }

    @Test
    fun rejectsInvalidSemanticFields() {
        assertNull(WorldviewProvider.parseManifest("""{"id":"../fantasy","name":"Fantasy"}"""))
        assertNull(WorldviewProvider.parseManifest("""{"id":"fantasy","name":" "}"""))
        assertNull(
            WorldviewProvider.parseManifest(
                """{"id":"fantasy","name":"Fantasy","version":"v1","compatibleRulesets":["any"]}"""
            )
        )
        assertNull(
            WorldviewProvider.parseManifest(
                """{"id":"fantasy","name":"Fantasy","compatibleRulesets":[]}"""
            )
        )
    }
}
