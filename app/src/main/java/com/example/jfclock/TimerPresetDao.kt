package com.example.jfclock

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TimerPresetDao {

    @Query("SELECT * FROM timer_presets ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<TimerPreset>>

    @Insert
    suspend fun insert(preset: TimerPreset): Long

    @Update
    suspend fun update(preset: TimerPreset)

    @Delete
    suspend fun delete(preset: TimerPreset)

    /** 拖动排序后按新顺序回写（只改顺序列，不动名称/时长）。 */
    @Query("UPDATE timer_presets SET sortOrder = :order WHERE id = :id")
    suspend fun updateOrder(id: Long, order: Int)
}
