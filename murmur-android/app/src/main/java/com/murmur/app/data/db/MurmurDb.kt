package com.murmur.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        CharacterEntity::class,
        PersonaEntity::class,
        ChatEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        LoreEntity::class,
        ApiProfileEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class MurmurDb : RoomDatabase() {
    abstract fun characters(): CharacterDao
    abstract fun personas(): PersonaDao
    abstract fun chats(): ChatDao
    abstract fun messages(): MessageDao
    abstract fun memories(): MemoryDao
    abstract fun lore(): LoreDao
    abstract fun apiProfiles(): ApiProfileDao

    companion object {
        @Volatile
        private var INSTANCE: MurmurDb? = null

        fun get(context: Context): MurmurDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                MurmurDb::class.java,
                "murmur.db",
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}
