package com.murmur.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
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

        /** 加背景/立绘字段。写真迁移而不是走销毁重建，不能因为加两列就把人聊了半天的记录清了。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE characters ADD COLUMN portraitUri TEXT")
                db.execSQL("ALTER TABLE characters ADD COLUMN backgroundUri TEXT")
                db.execSQL("ALTER TABLE chats ADD COLUMN backgroundUri TEXT")
            }
        }

        fun get(context: Context): MurmurDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                MurmurDb::class.java,
                "murmur.db",
            ).addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
    }
}
