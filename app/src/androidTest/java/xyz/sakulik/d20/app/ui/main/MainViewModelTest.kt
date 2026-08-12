package xyz.sakulik.d20.app.ui.main

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sakulik.d20.app.data.local.AppDatabase
import xyz.sakulik.d20.app.data.local.CampaignEntity
import xyz.sakulik.d20.app.data.local.CharacterEntity
import xyz.sakulik.d20.app.data.local.ItemEntity
import xyz.sakulik.d20.app.data.model.CharacterGenState
import xyz.sakulik.d20.app.data.model.ChatMessage
import xyz.sakulik.d20.app.data.model.EncounterParticipantRequest
import xyz.sakulik.d20.app.data.model.GameEvent
import xyz.sakulik.d20.app.data.model.StreamState
import xyz.sakulik.d20.app.data.repository.ContextAssembler
import xyz.sakulik.d20.app.data.repository.InventoryRepository
import xyz.sakulik.d20.app.data.repository.LlmRepository
import xyz.sakulik.d20.app.data.security.LlmKeyManager
import xyz.sakulik.d20.app.domain.rules.dynamic.CheckIntent
import xyz.sakulik.d20.app.domain.rules.dynamic.DiceSubmission

@RunWith(AndroidJUnit4::class)
class MainViewModelTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        database.campaignDao().insertCampaign(
            CampaignEntity(
                id = CAMPAIGN_ID,
                title = "测试剧本",
                systemId = "dnd_5e"
            )
        )
        database.characterDao().insertCharacter(
            CharacterEntity(
                id = "hero",
                campaignId = CAMPAIGN_ID,
                name = "测试英雄",
                stats = mapOf(
                    "hp" to "10",
                    "max_hp" to "10",
                    "dex" to "10",
                    "str" to "16",
                    "int" to "16",
                    "prof_bonus" to "2",
                    "resources" to """{"spell_slots":{"level_1":{"current":1,"max":1}}}"""
                ),
                activeSystem = "dnd_5e"
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun weaponAttackOpensDamageStageAndPersistsTargetHp() = runBlocking {
        database.itemDao().insertItem(
            ItemEntity(
                id = "longsword",
                campaignId = CAMPAIGN_ID,
                name = "长剑",
                description = "测试武器",
                category = "武器",
                modifiers = mapOf(
                    "attack_ability" to "STR",
                    "proficient" to "true",
                    "damage_formula" to "1d8",
                    "damage_ability" to "STR",
                    "damage_type" to "slashing"
                ),
                isEquipped = true
            )
        )
        val viewModel = createViewModel()
        withTimeout(2_000) {
            viewModel.uiState.first { it.character != null && it.availableWeapons.isNotEmpty() }
        }
        viewModel.handleGameEvents(
            listOf(
                GameEvent.StartCombat(
                    listOf(EncounterParticipantRequest("orc", "兽人", "orc"))
                )
            )
        )
        submitPlayerInitiative(viewModel, 20)

        viewModel.onDiceResult(
            DiceSubmission.manual("1d20", 20),
            CheckIntent(
                actionId = "dnd_attack",
                meta = mapOf(
                    "expression" to "1d20",
                    "reason" to "攻击兽人",
                    "weapon_id" to "longsword",
                    "target_id" to "orc"
                )
            )
        )
        val effectIntent = withTimeout(2_000) {
            viewModel.uiState.first {
                it.currentDiceIntent?.actionId == "dnd_attack_effect"
            }.currentDiceIntent!!
        }
        assertEquals("2d8+3", effectIntent.meta["expression"])

        viewModel.onDiceResult(DiceSubmission.manual("2d8+3", 11), effectIntent)
        withTimeout(2_000) {
            database.combatantDao().observeByCampaign(CAMPAIGN_ID)
                .first { combatants -> combatants.singleOrNull()?.hp == 9 }
        }
        assertEquals(9, database.combatantDao().getByCampaign(CAMPAIGN_ID).single().hp)
        assertEquals(0, database.combatSessionDao().getByCampaign(CAMPAIGN_ID)
            ?.turnResources?.get("action"))
    }

    @Test
    fun automaticSpellAtomicallyConsumesSlotAndPersistsDamage() = runBlocking {
        database.itemDao().insertItem(
            ItemEntity(
                id = "magic_missile",
                campaignId = CAMPAIGN_ID,
                name = "魔法飞弹",
                description = "自动命中",
                category = "法术",
                modifiers = mapOf(
                    "resolution_type" to "AUTOMATIC",
                    "slot_level" to "1",
                    "ability" to "int",
                    "damage_formula" to "1d4+1",
                    "damage_type" to "force"
                ),
                isEquipped = true
            )
        )
        val viewModel = createViewModel()
        withTimeout(2_000) {
            viewModel.uiState.first { it.character != null && it.preparedSpells.isNotEmpty() }
        }
        viewModel.handleGameEvents(
            listOf(
                GameEvent.StartCombat(
                    listOf(EncounterParticipantRequest("goblin", "地精", "goblin"))
                )
            )
        )
        submitPlayerInitiative(viewModel, 20)

        viewModel.onDiceResult(
            DiceSubmission.manual("1d4+1", 4),
            CheckIntent(
                actionId = "dnd_cast",
                meta = mapOf(
                    "expression" to "1d4+1",
                    "reason" to "魔法飞弹效果",
                    "spell_id" to "magic_missile",
                    "target_id" to "goblin",
                    "resolution_stage" to "EFFECT"
                )
            )
        )
        withTimeout(2_000) {
            database.combatantDao().observeByCampaign(CAMPAIGN_ID)
                .first { combatants -> combatants.singleOrNull()?.hp == 3 }
        }

        val savedCharacter = database.characterDao().getCharacterByCampaign(CAMPAIGN_ID)!!
        val slots = Json.parseToJsonElement(savedCharacter.stats.getValue("resources"))
            .jsonObject.getValue("spell_slots").jsonObject
        assertEquals(0, slots.getValue("level_1").jsonObject
            .getValue("current").jsonPrimitive.int)
        assertEquals(3, database.combatantDao().getByCampaign(CAMPAIGN_ID).single().hp)
        assertEquals(0, database.combatSessionDao().getByCampaign(CAMPAIGN_ID)
            ?.turnResources?.get("action"))
    }

    private suspend fun submitPlayerInitiative(viewModel: MainViewModel, total: Int) {
        val intent = withTimeout(2_000) {
            viewModel.uiState.first {
                it.currentDiceIntent?.actionId == "combat_initiative"
            }.currentDiceIntent!!
        }
        viewModel.onDiceResult(
            DiceSubmission.manual(intent.meta.getValue("expression"), total),
            intent
        )
        withTimeout(2_000) {
            viewModel.uiState.first { it.combatState?.isPlayerTurn == true }
        }
    }

    private fun createViewModel(): MainViewModel {
        val contextAssembler = ContextAssembler(
            context = context,
            campaignDao = database.campaignDao(),
            characterDao = database.characterDao(),
            messageDao = database.messageDao(),
            loreEntryDao = database.loreEntryDao(),
            combatantDao = database.combatantDao(),
            combatSessionDao = database.combatSessionDao(),
            keyManager = FakeKeyManager
        )
        return MainViewModel(
            context = context,
            campaignId = CAMPAIGN_ID,
            repository = FakeLlmRepository,
            contextAssembler = contextAssembler,
            characterDao = database.characterDao(),
            messageDao = database.messageDao(),
            inventoryRepository = InventoryRepository(database.itemDao()),
            keyManager = FakeKeyManager,
            loreEntryDao = database.loreEntryDao(),
            combatantDao = database.combatantDao(),
            gameStateDao = database.gameStateDao()
        )
    }

    private object FakeLlmRepository : LlmRepository {
        override fun chatStream(
            baseUrl: String,
            messages: List<ChatMessage>
        ): Flow<StreamState> = emptyFlow()

        override fun chatRaw(
            baseUrl: String,
            messages: List<ChatMessage>
        ): Flow<String> = emptyFlow()

        override fun generateCharacter(
            baseUrl: String,
            description: String,
            rulesetId: String,
            promptInjection: String?
        ): Flow<CharacterGenState> = emptyFlow()
    }

    private object FakeKeyManager : LlmKeyManager {
        override fun saveKey(key: String) = Unit
        override fun getKey(): String? = null
        override fun hasKey(): Boolean = false
        override fun clearKey() = Unit
        override fun saveBaseUrl(url: String) = Unit
        override fun getBaseUrl(): String = "https://example.invalid"
        override fun saveModel(model: String) = Unit
        override fun getModel(): String = "test"
        override fun saveThemeStyle(style: String) = Unit
        override fun getThemeStyle(): String = "AUTO"
        override fun saveApiProtocol(protocol: String) = Unit
        override fun getApiProtocol(): String = "CHAT_COMPLETIONS"
        override fun saveMaxHistoryTurns(turns: Int) = Unit
        override fun getMaxHistoryTurns(): Int = 8
    }

    private companion object {
        const val CAMPAIGN_ID = "view-model-test"
    }
}
