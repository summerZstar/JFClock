package com.example.jfclock

import kotlinx.coroutines.flow.Flow

class AlarmRepository(private val dao: AlarmDao) {

    val allAlarms: Flow<List<Alarm>> = dao.observeAll()

    suspend fun getById(id: Long): Alarm? = dao.getById(id)

    suspend fun getAll(): List<Alarm> = dao.getAll()

    suspend fun insert(alarm: Alarm): Long = dao.insert(alarm)

    suspend fun update(alarm: Alarm) = dao.update(alarm)

    suspend fun delete(alarm: Alarm) = dao.delete(alarm)
}
