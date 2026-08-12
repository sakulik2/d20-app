package xyz.sakulik.d20.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import xyz.sakulik.d20.app.domain.worldview.LEGACY_WORLDVIEW_PROMPT_PENDING

object DatabaseMigrations {
    private const val CURRENT_VERSION = 12
    private val managedTables = listOf(
        "campaigns",
        "characters",
        "messages",
        "items",
        "lore_entries",
        "combatants",
        "combat_sessions"
    )

    val TO_CURRENT: Array<Migration> = (1 until CURRENT_VERSION).map { startVersion ->
        object : Migration(startVersion, CURRENT_VERSION) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateToCurrentSchema(db)
            }
        }
    }.toTypedArray()

    private fun migrateToCurrentSchema(database: SupportSQLiteDatabase) {
        managedTables.asReversed().forEach { table ->
            database.execSQL("DROP TABLE IF EXISTS `${table}_legacy_migration`")
        }
        managedTables.filter { table -> database.tableExists(table) }.forEach { table ->
            database.execSQL(
                "ALTER TABLE `$table` RENAME TO `${table}_legacy_migration`"
            )
        }

        createCurrentTables(database)
        copyCampaigns(database)
        copyCharacters(database)
        copyMessages(database)
        copyItems(database)
        copyLoreEntries(database)
        copyCombatants(database)
        copyCombatSessions(database)

        managedTables.asReversed().forEach { table ->
            database.execSQL("DROP TABLE IF EXISTS `${table}_legacy_migration`")
        }
        createCurrentIndices(database)
    }

    private fun createCurrentTables(database: SupportSQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS `campaigns` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `system_id` TEXT NOT NULL,
                `world_name` TEXT NOT NULL,
                `tone` TEXT NOT NULL,
                `core_setting` TEXT NOT NULL,
                `custom_rules` TEXT NOT NULL,
                `worldview_id` TEXT,
                `worldview_prompt` TEXT NOT NULL,
                `last_updated` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )""".trimIndent()
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS `characters` (
                `id` TEXT NOT NULL,
                `campaign_id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `stats` TEXT NOT NULL,
                `active_system` TEXT NOT NULL,
                `inventory_json` TEXT NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`campaign_id`) REFERENCES `campaigns`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent()
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS `messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `campaign_id` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `is_hidden` INTEGER NOT NULL,
                `timestamp` INTEGER NOT NULL,
                FOREIGN KEY(`campaign_id`) REFERENCES `campaigns`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent()
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS `items` (
                `id` TEXT NOT NULL,
                `campaign_id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `modifiers` TEXT NOT NULL,
                `is_equipped` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`campaign_id`) REFERENCES `campaigns`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent()
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS `lore_entries` (
                `id` TEXT NOT NULL,
                `campaign_id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `keywords` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `is_enabled` INTEGER NOT NULL,
                `last_updated` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`campaign_id`) REFERENCES `campaigns`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent()
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS `combatants` (
                `campaign_id` TEXT NOT NULL,
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `initiative` INTEGER NOT NULL,
                `ac` INTEGER NOT NULL,
                `hp` INTEGER NOT NULL,
                `max_hp` INTEGER NOT NULL,
                `resistances` TEXT NOT NULL,
                `vulnerabilities` TEXT NOT NULL,
                `immunities` TEXT NOT NULL,
                `saving_throws` TEXT NOT NULL,
                `attributes` TEXT NOT NULL,
                PRIMARY KEY(`campaign_id`, `id`),
                FOREIGN KEY(`campaign_id`) REFERENCES `campaigns`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent()
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS `combat_sessions` (
                `campaign_id` TEXT NOT NULL,
                `round` INTEGER NOT NULL,
                `initiative_queue` TEXT NOT NULL,
                `current_turn_index` INTEGER NOT NULL,
                `ruleset_id` TEXT NOT NULL,
                `life_policy` TEXT NOT NULL,
                `participant_initiative` INTEGER NOT NULL,
                `turn_resources` TEXT NOT NULL,
                `defeat_at_zero_hp` INTEGER NOT NULL,
                `ongoing_effects` TEXT NOT NULL,
                PRIMARY KEY(`campaign_id`),
                FOREIGN KEY(`campaign_id`) REFERENCES `campaigns`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent()
        )
    }

    private fun createCurrentIndices(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_characters_campaign_id` ON `characters` (`campaign_id`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_messages_campaign_id` ON `messages` (`campaign_id`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_items_campaign_id` ON `items` (`campaign_id`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_lore_entries_campaign_id` ON `lore_entries` (`campaign_id`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_lore_entries_title` ON `lore_entries` (`title`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_combatants_campaign_id` ON `combatants` (`campaign_id`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_combat_sessions_campaign_id` ON `combat_sessions` (`campaign_id`)"
        )
    }

    private fun copyCampaigns(database: SupportSQLiteDatabase) {
        copyTable(
            database = database,
            table = "campaigns",
            columns = listOf(
                column("id", "lower(hex(randomblob(16)))"),
                column("title", "'未命名剧本'"),
                column("system_id", "'coc_7e'", "systemId"),
                column("world_name", "''", "worldName"),
                column("tone", "''"),
                column("core_setting", "''", "coreSetting"),
                column("custom_rules", "''", "customRules"),
                nullableColumn("worldview_id", "worldviewId"),
                column(
                    "worldview_prompt",
                    "'$LEGACY_WORLDVIEW_PROMPT_PENDING'",
                    "worldviewPrompt"
                ),
                column("last_updated", currentTimeExpression, "lastUpdated")
            )
        )
    }

    private fun copyCharacters(database: SupportSQLiteDatabase) {
        copyChildTable(
            database = database,
            table = "characters",
            columns = listOf(
                column("id", "lower(hex(randomblob(16)))"),
                column("campaign_id", "''", "campaignId"),
                column("name", "'未命名角色'"),
                column("stats", "'{}'", "stats_json", "statsJson"),
                column("active_system", "'coc_7e'", "activeSystem"),
                column("inventory_json", "'[]'", "inventory", "inventoryJson")
            )
        )
        database.execSQL(
            """UPDATE `characters`
                SET `active_system` = COALESCE(
                    (SELECT `system_id` FROM `campaigns`
                        WHERE `campaigns`.`id` = `characters`.`campaign_id`),
                    `active_system`
                )
            """.trimIndent()
        )
    }

    private fun copyMessages(database: SupportSQLiteDatabase) {
        copyChildTable(
            database = database,
            table = "messages",
            columns = listOf(
                column("id", "legacy.`rowid`"),
                column("campaign_id", "''", "campaignId"),
                column("role", "'assistant'"),
                column("content", "''"),
                column("is_hidden", "0", "isHidden"),
                column("timestamp", currentTimeExpression)
            )
        )
    }

    private fun copyItems(database: SupportSQLiteDatabase) {
        copyChildTable(
            database = database,
            table = "items",
            columns = listOf(
                column("id", "lower(hex(randomblob(16)))"),
                column("campaign_id", "''", "campaignId"),
                column("name", "'未命名物品'"),
                column("description", "''"),
                column("category", "'其他'"),
                column("modifiers", "'{}'"),
                column("is_equipped", "0", "isEquipped")
            )
        )
    }

    private fun copyLoreEntries(database: SupportSQLiteDatabase) {
        copyChildTable(
            database = database,
            table = "lore_entries",
            columns = listOf(
                column("id", "lower(hex(randomblob(16)))"),
                column("campaign_id", "''", "campaignId"),
                column("title", "'未命名条目'"),
                column("category", "'LORE'"),
                column("keywords", "''"),
                column("content", "''"),
                column("is_enabled", "1", "isEnabled"),
                column("last_updated", currentTimeExpression, "lastUpdated")
            )
        )
    }

    private fun copyCombatants(database: SupportSQLiteDatabase) {
        copyChildTable(
            database = database,
            table = "combatants",
            columns = listOf(
                column("campaign_id", "''", "campaignId"),
                column("id", "lower(hex(randomblob(16)))"),
                column("name", "'未命名目标'"),
                column("initiative", "0"),
                column("ac", "10"),
                column("hp", "1"),
                column("max_hp", "1", "maxHp"),
                column("resistances", "'[]'"),
                column("vulnerabilities", "'[]'"),
                column("immunities", "'[]'"),
                column("saving_throws", "'{}'", "savingThrows"),
                column("attributes", "'{}'")
            )
        )
    }

    private fun copyCombatSessions(database: SupportSQLiteDatabase) {
        val sourceTable = "combat_sessions_legacy_migration"
        if (!database.tableExists(sourceTable)) return
        val sourceColumns = database.tableColumns(sourceTable)
        val campaignColumn = listOf("campaign_id", "campaignId")
            .firstOrNull(sourceColumns::contains) ?: return
        if ("turn_resources" in sourceColumns) {
            copyChildTable(
                database = database,
                table = "combat_sessions",
                columns = listOf(
                    column("campaign_id", "''", "campaignId"),
                    column("round", "1"),
                    column("initiative_queue", "'[]'", "initiativeQueue"),
                    column("current_turn_index", "0", "currentTurnIndex"),
                    column("ruleset_id", "''", "rulesetId"),
                    column("life_policy", "'NONE'", "lifePolicy"),
                    column("participant_initiative", "0", "participantInitiative"),
                    column("turn_resources", "'{}'", "turnResources"),
                    column("defeat_at_zero_hp", "0", "defeatAtZeroHp"),
                    column("ongoing_effects", "'[]'", "ongoingEffects")
                )
            )
            return
        }
        val initiativeColumn = listOf("player_initiative", "playerInitiative")
            .firstOrNull(sourceColumns::contains)
        val actionColumn = listOf("action_available", "actionAvailable")
            .firstOrNull(sourceColumns::contains)
        val bonusColumn = listOf("bonus_action_available", "bonusActionAvailable")
            .firstOrNull(sourceColumns::contains)
        val reactionColumn = listOf("reaction_available", "reactionAvailable")
            .firstOrNull(sourceColumns::contains)
        val initiativeValue = initiativeColumn?.let { "COALESCE(legacy.`$it`, 0)" } ?: "0"
        val actionValue = actionColumn?.let { "COALESCE(legacy.`$it`, 1)" } ?: "1"
        val bonusValue = bonusColumn?.let { "COALESCE(legacy.`$it`, 1)" } ?: "1"
        val reactionValue = reactionColumn?.let { "COALESCE(legacy.`$it`, 1)" } ?: "1"
        database.execSQL(
            """INSERT OR IGNORE INTO `combat_sessions` (
                `campaign_id`, `round`, `initiative_queue`, `current_turn_index`,
                `ruleset_id`, `life_policy`, `participant_initiative`,
                `turn_resources`, `defeat_at_zero_hp`, `ongoing_effects`
            ) SELECT
                legacy.`$campaignColumn`, legacy.`round`, legacy.`initiative_queue`,
                legacy.`current_turn_index`, campaigns.`system_id`,
                CASE WHEN lower(campaigns.`system_id`) = 'dnd_5e' THEN 'DND_5E' ELSE 'NONE' END,
                $initiativeValue,
                CASE WHEN lower(campaigns.`system_id`) = 'dnd_5e'
                    THEN '{"action":' || $actionValue || ',"bonus_action":' || $bonusValue ||
                        ',"reaction":' || $reactionValue || '}'
                    ELSE '{}'
                END,
                CASE WHEN lower(campaigns.`system_id`) = 'dnd_5e' THEN 1 ELSE 0 END,
                '[]'
            FROM `$sourceTable` AS legacy
            INNER JOIN `campaigns` ON campaigns.`id` = legacy.`$campaignColumn`""".trimIndent()
        )
    }

    private fun copyChildTable(
        database: SupportSQLiteDatabase,
        table: String,
        columns: List<MigrationColumn>
    ) {
        val sourceTable = "${table}_legacy_migration"
        if (!database.tableExists(sourceTable)) return

        val sourceColumns = database.tableColumns(sourceTable)
        val campaignColumn = listOf("campaign_id", "campaignId")
            .firstOrNull(sourceColumns::contains) ?: return
        copyTable(
            database = database,
            table = table,
            columns = columns,
            whereClause = "legacy.`$campaignColumn` IN (SELECT `id` FROM `campaigns`)"
        )
    }

    private fun copyTable(
        database: SupportSQLiteDatabase,
        table: String,
        columns: List<MigrationColumn>,
        whereClause: String? = null
    ) {
        val sourceTable = "${table}_legacy_migration"
        if (!database.tableExists(sourceTable)) return

        val sourceColumns = database.tableColumns(sourceTable)
        val targets = columns.joinToString(", ") { "`${it.target}`" }
        val values = columns.joinToString(", ") { migrationColumn ->
            val source = migrationColumn.sources.firstOrNull(sourceColumns::contains)
            when {
                source == null -> migrationColumn.fallback
                migrationColumn.nullable -> "legacy.`$source`"
                else -> "COALESCE(legacy.`$source`, ${migrationColumn.fallback})"
            }
        }
        val filter = whereClause?.let { " WHERE $it" }.orEmpty()
        database.execSQL(
            "INSERT OR IGNORE INTO `$table` ($targets) " +
                "SELECT $values FROM `$sourceTable` AS legacy$filter"
        )
    }

    private fun SupportSQLiteDatabase.tableExists(table: String): Boolean {
        query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(table)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun SupportSQLiteDatabase.tableColumns(table: String): Set<String> {
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            return columns
        }
    }

    private fun column(
        target: String,
        fallback: String,
        vararg aliases: String
    ): MigrationColumn {
        return MigrationColumn(target, listOf(target) + aliases, fallback, nullable = false)
    }

    private fun nullableColumn(target: String, vararg aliases: String): MigrationColumn {
        return MigrationColumn(target, listOf(target) + aliases, "NULL", nullable = true)
    }

    private data class MigrationColumn(
        val target: String,
        val sources: List<String>,
        val fallback: String,
        val nullable: Boolean
    )

    private const val currentTimeExpression =
        "CAST(strftime('%s', 'now') AS INTEGER) * 1000"
}
