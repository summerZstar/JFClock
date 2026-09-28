package com.example.jfclock

import androidx.room.*

@Dao
interface AlarmDao {

    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<Alarm>>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: Long): Alarm?

    @Query("SELECT * FROM alarms")
    suspend fun getAll(): List<Alarm>

    @Insert
    suspend fun insert(alarm: Alarm): Long

    @Update
    suspend fun update(alarm: Alarm)

    @Delete
    suspend fun delete(alarm: Alarm)
}
