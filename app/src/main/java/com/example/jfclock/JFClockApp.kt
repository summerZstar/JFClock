package com.example.jfclock

import android.app.Application

class JFClockApp : Application() {

    val database by lazy { AlarmDatabase.get(this) }
    val repository by lazy { AlarmRepository(database.alarmDao()) }
}
