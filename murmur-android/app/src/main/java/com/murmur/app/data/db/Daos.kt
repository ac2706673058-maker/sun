package com.murmur.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY favorite DESC, updatedAt DESC")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE id = :id")
    fun observe(id: String): Flow<CharacterEntity?>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun get(id: String): CharacterEntity?

    @Query("SELECT COUNT(*) FROM characters")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(item: CharacterEntity)

    @Delete
    suspend fun delete(item: CharacterEntity)

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY isDefault DESC, createdAt ASC")
    fun observeAll(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun get(id: String): PersonaEntity?

    @Query("SELECT * FROM personas WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): PersonaEntity?

    @Query("SELECT COUNT(*) FROM personas")
    suspend fun count(): Int

    @Query("UPDATE personas SET isDefault = 0")
    suspend fun clearDefault()

    @Upsert
    suspend fun upsert(item: PersonaEntity)

    @Query("DELETE FROM personas WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE characterId = :characterId ORDER BY pinned DESC, updatedAt DESC")
    fun observeForCharacter(characterId: String): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observe(id: String): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun get(id: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE characterId = :characterId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestForCharacter(characterId: String): ChatEntity?

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecent(): ChatEntity?

    @Query("SELECT * FROM chats WHERE characterId = :characterId")
    suspend fun listForCharacter(characterId: String): List<ChatEntity>

    @Upsert
    suspend fun upsert(item: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM chats WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY idx ASC")
    fun observeForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY idx ASC")
    suspend fun listForChat(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("SELECT IFNULL(MAX(idx), -1) FROM messages WHERE chatId = :chatId")
    suspend fun maxIdx(chatId: String): Long

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    suspend fun count(chatId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MessageEntity>)

    @Upsert
    suspend fun upsert(item: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND idx >= :fromIdx")
    suspend fun deleteFrom(chatId: String, fromIdx: Long)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteForChat(chatId: String)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE chatId = :chatId ORDER BY pinned DESC, importance DESC, createdAt DESC")
    fun observeForChat(chatId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE chatId = :chatId AND enabled = 1")
    suspend fun listEnabled(chatId: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE chatId = :chatId")
    suspend fun listAll(chatId: String): List<MemoryEntity>

    @Upsert
    suspend fun upsert(item: MemoryEntity)

    @Upsert
    suspend fun upsertAll(items: List<MemoryEntity>)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM memories WHERE chatId = :chatId")
    suspend fun deleteForChat(chatId: String)
}

@Dao
interface LoreDao {
    @Query("SELECT * FROM lore WHERE characterId = :characterId OR characterId IS NULL ORDER BY priority DESC")
    fun observeForCharacter(characterId: String): Flow<List<LoreEntity>>

    @Query("SELECT * FROM lore WHERE (characterId = :characterId OR characterId IS NULL) AND enabled = 1 ORDER BY priority DESC")
    suspend fun listActive(characterId: String): List<LoreEntity>

    @Query("SELECT * FROM lore WHERE characterId = :characterId ORDER BY priority DESC")
    suspend fun listOwnedBy(characterId: String): List<LoreEntity>

    @Upsert
    suspend fun upsert(item: LoreEntity)

    @Upsert
    suspend fun upsertAll(items: List<LoreEntity>)

    @Query("DELETE FROM lore WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM lore WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: String)
}

@Dao
interface ApiProfileDao {
    @Query("SELECT * FROM api_profiles ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ApiProfileEntity>>

    @Query("SELECT * FROM api_profiles WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<ApiProfileEntity?>

    @Query("SELECT * FROM api_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ApiProfileEntity?

    @Query("SELECT * FROM api_profiles WHERE id = :id")
    suspend fun get(id: String): ApiProfileEntity?

    @Query("SELECT COUNT(*) FROM api_profiles")
    suspend fun count(): Int

    @Query("UPDATE api_profiles SET isActive = 0")
    suspend fun clearActive()

    @Upsert
    suspend fun upsert(item: ApiProfileEntity)

    @Query("DELETE FROM api_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}
