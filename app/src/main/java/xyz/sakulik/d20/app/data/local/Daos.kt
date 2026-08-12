package xyz.sakulik.d20.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 剧本详情聚合类
 */
data class CampaignWithData(
    @Embedded val campaign: CampaignEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "campaign_id"
    )
    val character: CharacterEntity?,
    @Relation(
        parentColumn = "id",
        entityColumn = "campaign_id"
    )
    val messages: List<MessageEntity>
)

@Dao
interface CampaignDao {
    @Query("SELECT * FROM campaigns ORDER BY last_updated DESC")
    fun getAllCampaigns(): Flow<List<CampaignEntity>>

    @Transaction
    @Query("SELECT * FROM campaigns WHERE id = :campaignId")
    suspend fun getCampaignWithData(campaignId: String): CampaignWithData?

    @Query("SELECT * FROM campaigns WHERE id = :campaignId")
    suspend fun getCampaignById(campaignId: String): CampaignEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCampaign(campaign: CampaignEntity)

    @Update
    suspend fun updateCampaign(campaign: CampaignEntity)

    @Query("DELETE FROM campaigns WHERE id = :campaignId")
    suspend fun deleteCampaign(campaignId: String)
}

@Dao
interface CharacterDao {
    @Query("SELECT DISTINCT campaign_id FROM characters")
    fun observeCampaignIds(): Flow<List<String>>

    @Query("SELECT * FROM characters WHERE campaign_id = :campaignId LIMIT 1")
    fun observeCharacterByCampaign(campaignId: String): Flow<CharacterEntity?>

    @Query("SELECT * FROM characters WHERE campaign_id = :campaignId LIMIT 1")
    suspend fun getCharacterByCampaign(campaignId: String): CharacterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacter(character: CharacterEntity)

    @Update
    suspend fun updateCharacter(character: CharacterEntity)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE campaign_id = :campaignId ORDER BY timestamp ASC")
    fun getMessagesByCampaign(campaignId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE campaign_id = :campaignId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(campaignId: String, limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Insert
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE campaign_id = :campaignId")
    suspend fun clearHistory(campaignId: String)
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE campaign_id = :campaignId")
    fun getItemsByCampaign(campaignId: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE campaign_id = :campaignId AND is_equipped = 1")
    suspend fun getEquippedItems(campaignId: String): List<ItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ItemEntity)

    @Update
    suspend fun updateItem(item: ItemEntity)

    @Delete
    suspend fun deleteItem(item: ItemEntity)
}

@Dao
interface LoreEntryDao {
    @Query("SELECT * FROM lore_entries WHERE campaign_id = :campaignId AND is_enabled = 1")
    suspend fun getEnabledEntriesByCampaign(campaignId: String): List<LoreEntryEntity>

    @Query("SELECT * FROM lore_entries WHERE campaign_id = :campaignId AND title = :title LIMIT 1")
    suspend fun getEntryByTitle(campaignId: String, title: String): LoreEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateLore(entry: LoreEntryEntity)

    @Query("DELETE FROM lore_entries WHERE campaign_id = :campaignId AND title = :title")
    suspend fun deleteByTitle(campaignId: String, title: String)

    @Query("DELETE FROM lore_entries WHERE campaign_id = :campaignId")
    suspend fun clearLore(campaignId: String)
}

@Dao
interface CombatantDao {
    @Query("SELECT * FROM combatants WHERE campaign_id = :campaignId ORDER BY initiative DESC")
    fun observeByCampaign(campaignId: String): Flow<List<CombatantEntity>>

    @Query("SELECT * FROM combatants WHERE campaign_id = :campaignId ORDER BY initiative DESC")
    suspend fun getByCampaign(campaignId: String): List<CombatantEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(combatants: List<CombatantEntity>)

    @Query("UPDATE combatants SET hp = :hp WHERE campaign_id = :campaignId AND id = :targetId")
    suspend fun updateHp(campaignId: String, targetId: String, hp: Int)

    @Query("DELETE FROM combatants WHERE campaign_id = :campaignId")
    suspend fun clearCampaign(campaignId: String)

    @Transaction
    suspend fun replaceCampaign(campaignId: String, combatants: List<CombatantEntity>) {
        clearCampaign(campaignId)
        if (combatants.isNotEmpty()) insertAll(combatants)
    }
}

@Dao
interface CombatSessionDao {
    @Query("SELECT * FROM combat_sessions WHERE campaign_id = :campaignId LIMIT 1")
    fun observeByCampaign(campaignId: String): Flow<CombatSessionEntity?>

    @Query("SELECT * FROM combat_sessions WHERE campaign_id = :campaignId LIMIT 1")
    suspend fun getByCampaign(campaignId: String): CombatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: CombatSessionEntity)

    @Query("DELETE FROM combat_sessions WHERE campaign_id = :campaignId")
    suspend fun deleteByCampaign(campaignId: String)
}

@Dao
interface GameStateDao {
    @Query("SELECT * FROM combat_sessions WHERE campaign_id = :campaignId LIMIT 1")
    fun observeCombatSession(campaignId: String): Flow<CombatSessionEntity?>

    @Update
    suspend fun updateCharacter(character: CharacterEntity): Int

    @Query("UPDATE combatants SET hp = :hp WHERE campaign_id = :campaignId AND id = :targetId")
    suspend fun updateTargetHp(campaignId: String, targetId: String, hp: Int): Int

    @Query("DELETE FROM combatants WHERE campaign_id = :campaignId")
    suspend fun clearCombatants(campaignId: String)

    @Query("DELETE FROM combat_sessions WHERE campaign_id = :campaignId")
    suspend fun clearCombatSession(campaignId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCombatants(combatants: List<CombatantEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCombatSession(session: CombatSessionEntity)

    @Transaction
    suspend fun startCombat(
        campaignId: String,
        combatants: List<CombatantEntity>,
        session: CombatSessionEntity
    ) {
        clearCombatants(campaignId)
        clearCombatSession(campaignId)
        if (combatants.isNotEmpty()) insertCombatants(combatants)
        upsertCombatSession(session)
    }

    @Transaction
    suspend fun endCombat(campaignId: String) {
        clearCombatants(campaignId)
        clearCombatSession(campaignId)
    }

    @Transaction
    suspend fun applyRuleOutcome(
        character: CharacterEntity,
        targetId: String?,
        targetHp: Int?,
        combatSession: CombatSessionEntity?,
        combatEnded: Boolean
    ) {
        check(updateCharacter(character) == 1) { "规则写回失败：角色记录已不存在" }
        if (targetId != null && targetHp != null) {
            check(updateTargetHp(character.campaignId, targetId, targetHp) == 1) {
                "规则写回失败：战斗目标已不存在"
            }
        }
        if (combatEnded) {
            clearCombatants(character.campaignId)
            clearCombatSession(character.campaignId)
        } else if (combatSession != null) {
            upsertCombatSession(combatSession)
        }
    }

    @Transaction
    suspend fun applyRuleOutcomes(
        character: CharacterEntity,
        targetHpUpdates: Map<String, Int>,
        combatSession: CombatSessionEntity?,
        combatEnded: Boolean
    ) {
        check(updateCharacter(character) == 1) { "规则写回失败：角色记录已不存在" }
        targetHpUpdates.forEach { (targetId, targetHp) ->
            check(updateTargetHp(character.campaignId, targetId, targetHp) == 1) {
                "规则写回失败：战斗目标 $targetId 已不存在"
            }
        }
        if (combatEnded) {
            clearCombatants(character.campaignId)
            clearCombatSession(character.campaignId)
        } else if (combatSession != null) {
            upsertCombatSession(combatSession)
        }
    }
}
