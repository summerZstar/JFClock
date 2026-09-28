package com.example.jfclock

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

@Database(entities = [Alarm::class], version = 1, exportSchema = false)
abstract class AlarmDatabase : RoomDatabase() {

    abstract fun alarmDao(): AlarmDao

    companion object {
        @Volatile
        private var INSTANCE: AlarmDatabase? = null

        fun get(context: Context): AlarmDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AlarmDatabase::class.java,
                    "jfclock.db"
                ).build().also { INSTANCE = it }
            }
    }
}
