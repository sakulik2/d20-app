package xyz.sakulik.d20.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        CampaignEntity::class, 
        CharacterEntity::class, 
        MessageEntity::class,
        ItemEntity::class,
        LoreEntryEntity::class,
        CombatantEntity::class,
        CombatSessionEntity::class
    ], 
    version = 11,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun campaignDao(): CampaignDao
    abstract fun characterDao(): CharacterDao
    abstract fun messageDao(): MessageDao
    abstract fun itemDao(): ItemDao
    abstract fun loreEntryDao(): LoreEntryDao
    abstract fun combatantDao(): CombatantDao
    abstract fun combatSessionDao(): CombatSessionDao
    abstract fun gameStateDao(): GameStateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "d20_database"
                )
                .addMigrations(*DatabaseMigrations.TO_CURRENT)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
