package com.openhands.tvplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FavEntity::class], version = 1, exportSchema = false)
abstract class FavDatabase : RoomDatabase() {

    abstract fun favDao(): FavDao

    companion object {
        @Volatile
        private var instance: FavDatabase? = null

        fun get(context: Context): FavDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FavDatabase::class.java,
                    "tv_favourites.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
