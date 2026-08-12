package xyz.sakulik.d20.app.data.local

import androidx.room.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.sakulik.d20.app.domain.combat.OngoingEffect

/**
 * 剧本/存档实体 (根节点)
 */
@Entity(tableName = "campaigns")
data class CampaignEntity(
    @PrimaryKey
    val id: String, // UUID
    val title: String, // 剧情标题
    @ColumnInfo(name = "system_id")
    val systemId: String, // 指向 DND_5E 或 COC_7E
    @ColumnInfo(name = "world_name")
    val worldName: String = "",
    val tone: String = "",
    @ColumnInfo(name = "core_setting")
    val coreSetting: String = "",
    @ColumnInfo(name = "custom_rules")
    val customRules: String = "",
    @ColumnInfo(name = "worldview_id")
    val worldviewId: String? = null,
    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * 角色实体 (外键关联 Campaign)
 */
@Entity(
    tableName = "characters",
    foreignKeys = [
        ForeignKey(
            entity = CampaignEntity::class,
            parentColumns = ["id"],
            childColumns = ["campaign_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["campaign_id"])]
)
data class CharacterEntity(
    @PrimaryKey
    val id: String, // UUID
    @ColumnInfo(name = "campaign_id")
    val campaignId: String,
    val name: String,
    /**
     * 核心设计：statsJson 存储不同规则下的属性 Map
     * CoC 7e: {"str": "50", "san": "40", "period": "1920s"}
     */
    val stats: Map<String, String>, 
    @ColumnInfo(name = "active_system")
    val activeSystem: String, // 用于标识该角色所属的规则系统
    @ColumnInfo(name = "inventory_json")
    val inventory: List<String> = emptyList()
)

/**
 * 消息历史实体 (外键关联 Campaign)
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = CampaignEntity::class,
            parentColumns = ["id"],
            childColumns = ["campaign_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["campaign_id"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "campaign_id")
    val campaignId: String,
    val role: String, // USER, AI, SYSTEM
    val content: String,
    @ColumnInfo(name = "is_hidden")
    val isHidden: Boolean = false, // 是否为隐藏的判定结论
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 物品实体
 */
@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = CampaignEntity::class,
            parentColumns = ["id"],
            childColumns = ["campaign_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["campaign_id"])]
)
data class ItemEntity(
    @PrimaryKey
    val id: String, // UUID
    @ColumnInfo(name = "campaign_id")
    val campaignId: String,
    val name: String,
    val description: String,
    val category: String, // 武器/防具/消耗品
    val modifiers: Map<String, String>, // 如 {"strength": "2"} 或 {"damage": "2d4+2"}
    @ColumnInfo(name = "is_equipped")
    val isEquipped: Boolean = false
)

/**
 * 世界书设定条目 (外键关联 Campaign)
 */
@Entity(
    tableName = "lore_entries",
    foreignKeys = [
        ForeignKey(
            entity = CampaignEntity::class,
            parentColumns = ["id"],
            childColumns = ["campaign_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["campaign_id"]), Index(value = ["title"])]
)
data class LoreEntryEntity(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    @ColumnInfo(name = "campaign_id")
    val campaignId: String,
    val title: String,
    val category: String = "LORE",
    val keywords: String = "",
    val content: String,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,
    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "combatants",
    primaryKeys = ["campaign_id", "id"],
    foreignKeys = [
        ForeignKey(
            entity = CampaignEntity::class,
            parentColumns = ["id"],
            childColumns = ["campaign_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["campaign_id"])]
)
data class CombatantEntity(
    @ColumnInfo(name = "campaign_id")
    val campaignId: String,
    val id: String,
    val name: String,
    val initiative: Int,
    val ac: Int,
    val hp: Int,
    @ColumnInfo(name = "max_hp")
    val maxHp: Int,
    val resistances: List<String> = emptyList(),
    val vulnerabilities: List<String> = emptyList(),
    val immunities: List<String> = emptyList(),
    @ColumnInfo(name = "saving_throws")
    val savingThrows: Map<String, Int> = emptyMap(),
    val attributes: Map<String, String> = emptyMap()
)

@Entity(
    tableName = "combat_sessions",
    foreignKeys = [
        ForeignKey(
            entity = CampaignEntity::class,
            parentColumns = ["id"],
            childColumns = ["campaign_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["campaign_id"])]
)
data class CombatSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "campaign_id")
    val campaignId: String,
    val round: Int,
    @ColumnInfo(name = "initiative_queue")
    val initiativeQueue: List<String>,
    @ColumnInfo(name = "current_turn_index")
    val currentTurnIndex: Int,
    @ColumnInfo(name = "ruleset_id")
    val rulesetId: String,
    @ColumnInfo(name = "life_policy")
    val lifePolicy: String,
    @ColumnInfo(name = "participant_initiative")
    val participantInitiative: Int,
    @ColumnInfo(name = "turn_resources")
    val turnResources: Map<String, Int>,
    @ColumnInfo(name = "defeat_at_zero_hp")
    val defeatAtZeroHp: Boolean,
    @ColumnInfo(name = "ongoing_effects")
    val ongoingEffects: List<OngoingEffect> = emptyList()
)

/**
 * Room 类型转换器
 */
class Converters {
    @TypeConverter
    fun fromStringMap(value: Map<String, String>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toStringMap(value: String): Map<String, String> {
        return try {
            Json.decodeFromString(value)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun fromIntMap(value: Map<String, Int>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toIntMap(value: String): Map<String, Int> {
        return try {
            Json.decodeFromString(value)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun fromInventory(value: List<String>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toInventory(value: String): List<String> {
        return try {
            Json.decodeFromString(value)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromOngoingEffects(value: List<OngoingEffect>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toOngoingEffects(value: String): List<OngoingEffect> {
        return try {
            Json.decodeFromString(value)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
