package com.itantra.transport.dtn

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DtnMessageEntity::class], version = 1, exportSchema = false)
abstract class DtnDatabase : RoomDatabase() {

    abstract fun dtnDao(): DtnDao

    companion object {
        @Volatile
        private var INSTANCE: DtnDatabase? = null

        fun getDatabase(context: Context): DtnDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DtnDatabase::class.java,
                    "itantra_dtn_mesh.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
