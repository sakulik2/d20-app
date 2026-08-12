package xyz.sakulik.d20.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameStateDaoBatchTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        database.campaignDao().insertCampaign(
            CampaignEntity(id = CAMPAIGN_ID, title = "批量事务", systemId = "dnd_5e")
        )
        database.characterDao().insertCharacter(character(resource = 1))
        database.combatantDao().insertAll(
            listOf(
                CombatantEntity(
                    campaignId = CAMPAIGN_ID,
                    id = "existing",
                    name = "现有目标",
                    initiative = 10,
                    ac = 12,
                    hp = 10,
                    maxHp = 10
                )
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun missingSecondTargetRollsBackCharacterAndFirstTarget() = runBlocking {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                database.gameStateDao().applyRuleOutcomes(
                    character = character(resource = 0),
                    targetHpUpdates = linkedMapOf(
                        "existing" to 4,
                        "missing" to 0
                    ),
                    combatSession = null,
                    combatEnded = false
                )
            }
        }

        val savedCharacter = database.characterDao().getCharacterByCampaign(CAMPAIGN_ID)
        val savedTarget = database.combatantDao().getByCampaign(CAMPAIGN_ID).single()
        assertEquals("1", savedCharacter?.stats?.get("resource"))
        assertEquals(10, savedTarget.hp)
    }

    private fun character(resource: Int) = CharacterEntity(
        id = "hero",
        campaignId = CAMPAIGN_ID,
        name = "英雄",
        stats = mapOf("resource" to resource.toString()),
        activeSystem = "dnd_5e"
    )

    private companion object {
        const val CAMPAIGN_ID = "batch-campaign"
    }
}
