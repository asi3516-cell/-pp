package com.openhands.tvplayer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserChannelDao {

    @Query("SELECT * FROM user_channels ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<UserChannelEntity>>

    @Query("SELECT * FROM user_channels ORDER BY updatedAt DESC")
    suspend fun all(): List<UserChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: UserChannelEntity)

    @Query("DELETE FROM user_channels WHERE baseUrl = :base")
    suspend fun remove(base: String)
}
