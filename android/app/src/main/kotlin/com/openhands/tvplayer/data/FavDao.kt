package com.openhands.tvplayer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavDao {

    @Query("SELECT * FROM favourites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavEntity>>

    @Query("SELECT * FROM favourites ORDER BY addedAt DESC")
    suspend fun all(): List<FavEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE url = :url)")
    suspend fun isFavourite(url: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(fav: FavEntity)

    @Query("DELETE FROM favourites WHERE url = :url")
    suspend fun remove(url: String)

    @Query("SELECT url FROM favourites")
    suspend fun favouriteUrls(): List<String>
}
