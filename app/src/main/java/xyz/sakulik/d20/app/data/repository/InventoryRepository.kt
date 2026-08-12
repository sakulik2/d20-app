package xyz.sakulik.d20.app.data.repository

import kotlinx.coroutines.flow.Flow
import xyz.sakulik.d20.app.data.local.ItemDao
import xyz.sakulik.d20.app.data.local.ItemEntity
import xyz.sakulik.d20.app.domain.rules.action.SpellProfile
import xyz.sakulik.d20.app.domain.rules.action.WeaponProfile
import xyz.sakulik.d20.app.domain.rules.action.toSpellProfileOrNull
import xyz.sakulik.d20.app.domain.rules.action.toWeaponProfileOrNull

class InventoryRepository(private val itemDao: ItemDao) {

    fun getItems(campaignId: String): Flow<List<ItemEntity>> = 
        itemDao.getItemsByCampaign(campaignId)

    suspend fun getEquippedModifiers(campaignId: String, statId: String): Int {
        val equippedItems = itemDao.getEquippedItems(campaignId)
        var totalBonus = 0
        equippedItems.forEach { item ->
            val value = item.modifiers.entries
                .firstOrNull { (key, _) -> key.equals(statId, ignoreCase = true) }
                ?.value
            totalBonus += value?.toIntOrNull() ?: 0
        }
        return totalBonus
    }

    suspend fun getEquippedWeapons(campaignId: String): List<WeaponProfile> {
        return itemDao.getEquippedItems(campaignId).mapNotNull(ItemEntity::toWeaponProfileOrNull)
    }

    suspend fun getPreparedSpells(campaignId: String): List<SpellProfile> {
        return itemDao.getEquippedItems(campaignId).mapNotNull(ItemEntity::toSpellProfileOrNull)
    }

    suspend fun addItem(item: ItemEntity) {
        itemDao.insertItem(item)
    }

    suspend fun toggleEquip(item: ItemEntity) {
        itemDao.updateItem(item.copy(isEquipped = !item.isEquipped))
    }

    suspend fun updateRuleParameters(item: ItemEntity, modifiers: Map<String, String>) {
        itemDao.updateItem(item.copy(modifiers = modifiers))
    }
}
