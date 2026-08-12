package xyz.sakulik.d20.app.domain.common.updater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sakulik.d20.app.domain.rules.RulesetRegistry
import xyz.sakulik.d20.app.domain.worldview.WorldviewProvider

@RunWith(AndroidJUnit4::class)
class PluginFallbackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = PluginRepository(context)

    @Before
    fun setUp() {
        cleanPlugin(PluginType.RULESET, "dnd_5e")
        cleanPlugin(PluginType.WORLDVIEW, "fantasy")
        RulesetRegistry.evictCache("dnd_5e")
    }

    @After
    fun tearDown() {
        cleanPlugin(PluginType.RULESET, "dnd_5e")
        cleanPlugin(PluginType.WORLDVIEW, "fantasy")
        RulesetRegistry.evictCache("dnd_5e")
    }

    @Test
    fun invalidManagedRulesetIsQuarantinedAndAssetLoads() {
        repository.getSandboxFile(PluginType.RULESET, "dnd_5e")
            .writeText("""{"id":"dnd_5e","version":"999.0.0"}""")
        assertTrue(repository.registerManagedInstallation(PluginType.RULESET, "dnd_5e"))

        val ruleset = RulesetRegistry.getRuleset(context, "dnd_5e")

        assertEquals("dnd_5e", ruleset?.id)
        assertFalse(repository.isManagedInstallation(PluginType.RULESET, "dnd_5e"))
        assertFalse(repository.getSandboxFile(PluginType.RULESET, "dnd_5e").exists())
        assertTrue(repository.getRejectedFile(PluginType.RULESET, "dnd_5e").exists())
    }

    @Test
    fun wrongManagedWorldviewIdIsQuarantinedAndAssetLoads() {
        repository.getSandboxFile(PluginType.WORLDVIEW, "fantasy")
            .writeText("""{"id":"wrong","name":"Wrong"}""")
        assertTrue(repository.registerManagedInstallation(PluginType.WORLDVIEW, "fantasy"))

        val worldview = WorldviewProvider.loadManifest(repository, "fantasy")

        assertEquals("fantasy", worldview?.id)
        assertFalse(repository.isManagedInstallation(PluginType.WORLDVIEW, "fantasy"))
        assertTrue(repository.getRejectedFile(PluginType.WORLDVIEW, "fantasy").exists())
    }

    private fun cleanPlugin(type: PluginType, id: String) {
        repository.getSandboxFile(type, id).delete()
        repository.getTempFile(type, id).delete()
        repository.getBackupFile(type, id).delete()
        repository.getRejectedFile(type, id).delete()
        repository.unregisterManagedInstallation(type, id)
    }
}
