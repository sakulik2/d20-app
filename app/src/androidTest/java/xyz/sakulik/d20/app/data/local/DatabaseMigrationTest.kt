package xyz.sakulik.d20.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sakulik.d20.app.domain.worldview.LEGACY_WORLDVIEW_PROMPT_PENDING

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrateVersionSixDatabasePreservesUserDataAndCreatesCombatants() {
        createVersionSixFixture()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(*DatabaseMigrations.TO_CURRENT)
            .allowMainThreadQueries()
            .build()

        runBlocking {
            val campaign = database.campaignDao().getCampaignById(CAMPAIGN_ID)
            val character = database.characterDao().getCharacterByCampaign(CAMPAIGN_ID)
            val messages = database.messageDao().getRecentMessages(CAMPAIGN_ID, 10)
            val items = database.itemDao().getItemsByCampaign(CAMPAIGN_ID).first()
            val lore = database.loreEntryDao().getEnabledEntriesByCampaign(CAMPAIGN_ID)
            val combatants = database.combatantDao().getByCampaign(CAMPAIGN_ID)

            assertEquals("旧版剧本", campaign?.title)
            assertEquals("旧版英雄", character?.name)
            assertEquals("旧消息", messages.single().content)
            assertEquals("旧剑", items.single().name)
            assertEquals("旧世界书", lore.single().title)
            assertEquals(emptyList<CombatantEntity>(), combatants)
            assertEquals(null, database.combatSessionDao().getByCampaign(CAMPAIGN_ID))
        }
        database.openHelper.writableDatabase
            .query("SELECT * FROM combatants LIMIT 1")
            .use { cursor -> assertEquals(12, cursor.columnCount) }
        database.openHelper.writableDatabase
            .query("SELECT * FROM combat_sessions LIMIT 1")
            .use { cursor -> assertEquals(10, cursor.columnCount) }
        database.close()
    }

    @Test
    fun migrateVersionSevenCombatantAddsEmptySavingThrows() {
        createVersionSevenFixture()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(*DatabaseMigrations.TO_CURRENT)
            .allowMainThreadQueries()
            .build()

        val combatant = runBlocking {
            database.combatantDao().getByCampaign(CAMPAIGN_ID).single()
        }
        assertEquals(emptyMap<String, Int>(), combatant.savingThrows)
        assertEquals(emptyMap<String, String>(), combatant.attributes)
        database.close()
    }

    @Test
    fun migrateVersionNineCombatStateToDeclaredPolicyFields() {
        createVersionNineFixture()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(*DatabaseMigrations.TO_CURRENT)
            .allowMainThreadQueries()
            .build()

        val session = runBlocking {
            database.combatSessionDao().getByCampaign(CAMPAIGN_ID)!!
        }
        val combatant = runBlocking {
            database.combatantDao().getByCampaign(CAMPAIGN_ID).single()
        }

        assertEquals("dnd_5e", session.rulesetId)
        assertEquals("DND_5E", session.lifePolicy)
        assertEquals(18, session.participantInitiative)
        assertEquals(
            mapOf("action" to 0, "bonus_action" to 1, "reaction" to 0),
            session.turnResources
        )
        assertEquals(true, session.defeatAtZeroHp)
        assertEquals(emptyList<xyz.sakulik.d20.app.domain.combat.OngoingEffect>(), session.ongoingEffects)
        assertEquals(emptyMap<String, String>(), combatant.attributes)
        database.close()
    }

    @Test
    fun migrateVersionNineNonDndCombatWithoutDndAssumptions() {
        createVersionNineFixture(systemId = "coc_7e")

        val database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(*DatabaseMigrations.TO_CURRENT)
            .allowMainThreadQueries()
            .build()

        val session = runBlocking {
            database.combatSessionDao().getByCampaign(CAMPAIGN_ID)!!
        }

        assertEquals("coc_7e", session.rulesetId)
        assertEquals("NONE", session.lifePolicy)
        assertEquals(emptyMap<String, Int>(), session.turnResources)
        assertEquals(false, session.defeatAtZeroHp)
        database.close()
    }

    @Test
    fun migrateVersionElevenAddsEmptyWorldviewPromptWithoutLosingCampaign() {
        createVersionElevenFixture()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(*DatabaseMigrations.TO_CURRENT)
            .allowMainThreadQueries()
            .build()

        val campaign = runBlocking {
            database.campaignDao().getCampaignById(CAMPAIGN_ID)!!
        }
        assertEquals("旧设定世界", campaign.worldName)
        assertEquals("forgotten_realms", campaign.worldviewId)
        assertEquals(LEGACY_WORLDVIEW_PROMPT_PENDING, campaign.worldviewPrompt)
        database.close()
    }

    private fun createVersionSixFixture() {
        val path = context.getDatabasePath(DATABASE_NAME)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("CREATE TABLE campaigns (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, system_id TEXT NOT NULL, world_name TEXT NOT NULL, tone TEXT NOT NULL, core_setting TEXT NOT NULL, custom_rules TEXT NOT NULL, worldview_id TEXT, last_updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE characters (id TEXT NOT NULL PRIMARY KEY, campaign_id TEXT NOT NULL, name TEXT NOT NULL, stats TEXT NOT NULL, active_system TEXT NOT NULL, inventory_json TEXT NOT NULL, FOREIGN KEY(campaign_id) REFERENCES campaigns(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, campaign_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, is_hidden INTEGER NOT NULL, timestamp INTEGER NOT NULL, FOREIGN KEY(campaign_id) REFERENCES campaigns(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE items (id TEXT NOT NULL PRIMARY KEY, campaign_id TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, category TEXT NOT NULL, modifiers TEXT NOT NULL, is_equipped INTEGER NOT NULL, FOREIGN KEY(campaign_id) REFERENCES campaigns(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE lore_entries (id TEXT NOT NULL PRIMARY KEY, campaign_id TEXT NOT NULL, title TEXT NOT NULL, category TEXT NOT NULL, keywords TEXT NOT NULL, content TEXT NOT NULL, is_enabled INTEGER NOT NULL, last_updated INTEGER NOT NULL, FOREIGN KEY(campaign_id) REFERENCES campaigns(id) ON DELETE CASCADE)")
        db.execSQL("INSERT INTO campaigns VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf(CAMPAIGN_ID, "旧版剧本", "dnd_5e", "旧世界", "冒险", "设定", "", "fantasy", 1L))
        db.execSQL("INSERT INTO characters VALUES (?, ?, ?, ?, ?, ?)", arrayOf("char", CAMPAIGN_ID, "旧版英雄", "{\"hp\":\"9\"}", "dnd_5e", "[]"))
        db.execSQL("INSERT INTO messages(campaign_id, role, content, is_hidden, timestamp) VALUES (?, ?, ?, ?, ?)", arrayOf(CAMPAIGN_ID, "assistant", "旧消息", 0, 2L))
        db.execSQL("INSERT INTO items VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf("item", CAMPAIGN_ID, "旧剑", "描述", "武器", "{}", 1))
        db.execSQL("INSERT INTO lore_entries VALUES (?, ?, ?, ?, ?, ?, ?, ?)", arrayOf("lore", CAMPAIGN_ID, "旧世界书", "LORE", "旧", "内容", 1, 3L))
        db.version = 6
        db.close()
    }

    private fun createVersionSevenFixture() {
        val path = context.getDatabasePath(DATABASE_NAME)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("CREATE TABLE campaigns (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, system_id TEXT NOT NULL, world_name TEXT NOT NULL, tone TEXT NOT NULL, core_setting TEXT NOT NULL, custom_rules TEXT NOT NULL, worldview_id TEXT, last_updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE combatants (campaign_id TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, initiative INTEGER NOT NULL, ac INTEGER NOT NULL, hp INTEGER NOT NULL, max_hp INTEGER NOT NULL, resistances TEXT NOT NULL, vulnerabilities TEXT NOT NULL, immunities TEXT NOT NULL, PRIMARY KEY(campaign_id, id), FOREIGN KEY(campaign_id) REFERENCES campaigns(id) ON DELETE CASCADE)")
        db.execSQL("INSERT INTO campaigns VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf(CAMPAIGN_ID, "旧版剧本", "dnd_5e", "旧世界", "冒险", "设定", "", null, 1L))
        db.execSQL("INSERT INTO combatants VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf(CAMPAIGN_ID, "goblin", "地精", 12, 15, 7, 7, "[]", "[]", "[]"))
        db.version = 7
        db.close()
    }

    private fun createVersionNineFixture(systemId: String = "dnd_5e") {
        val path = context.getDatabasePath(DATABASE_NAME)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("CREATE TABLE campaigns (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, system_id TEXT NOT NULL, world_name TEXT NOT NULL, tone TEXT NOT NULL, core_setting TEXT NOT NULL, custom_rules TEXT NOT NULL, worldview_id TEXT, last_updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE combatants (campaign_id TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, initiative INTEGER NOT NULL, ac INTEGER NOT NULL, hp INTEGER NOT NULL, max_hp INTEGER NOT NULL, resistances TEXT NOT NULL, vulnerabilities TEXT NOT NULL, immunities TEXT NOT NULL, saving_throws TEXT NOT NULL, PRIMARY KEY(campaign_id, id), FOREIGN KEY(campaign_id) REFERENCES campaigns(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE combat_sessions (campaign_id TEXT NOT NULL PRIMARY KEY, round INTEGER NOT NULL, initiative_queue TEXT NOT NULL, current_turn_index INTEGER NOT NULL, player_initiative INTEGER NOT NULL, action_available INTEGER NOT NULL, bonus_action_available INTEGER NOT NULL, reaction_available INTEGER NOT NULL, FOREIGN KEY(campaign_id) REFERENCES campaigns(id) ON DELETE CASCADE)")
        db.execSQL("INSERT INTO campaigns VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf(CAMPAIGN_ID, "旧版战斗", systemId, "旧世界", "冒险", "设定", "", null, 1L))
        db.execSQL("INSERT INTO combatants VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf(CAMPAIGN_ID, "goblin", "地精", 12, 15, 7, 7, "[]", "[]", "[]", "{}"))
        db.execSQL("INSERT INTO combat_sessions VALUES (?, ?, ?, ?, ?, ?, ?, ?)", arrayOf(CAMPAIGN_ID, 2, "[\"player\",\"goblin\"]", 0, 18, 0, 1, 0))
        db.version = 9
        db.close()
    }

    private fun createVersionElevenFixture() {
        val path = context.getDatabasePath(DATABASE_NAME)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("CREATE TABLE campaigns (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, system_id TEXT NOT NULL, world_name TEXT NOT NULL, tone TEXT NOT NULL, core_setting TEXT NOT NULL, custom_rules TEXT NOT NULL, worldview_id TEXT, last_updated INTEGER NOT NULL)")
        db.execSQL(
            "INSERT INTO campaigns VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                CAMPAIGN_ID,
                "旧版设定剧本",
                "dnd_5e",
                "旧设定世界",
                "冒险",
                "旧核心设定",
                "",
                "forgotten_realms",
                1L
            )
        )
        db.version = 11
        db.close()
    }

    companion object {
        private const val DATABASE_NAME = "migration-fixture.db"
        private const val CAMPAIGN_ID = "legacy-campaign"
    }
}
