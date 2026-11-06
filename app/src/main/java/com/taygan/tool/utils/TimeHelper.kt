package com.taygan.tool.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeHelper {

    private val timeFormatWithTenths = SimpleDateFormat("HH:mm:ss.S", Locale.getDefault())
    private val timeFormatFull = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun getCurrentTimeFormatted(): String {
        return timeFormatWithTenths.format(Date())
    }

    fun getRemainingMillis(targetHour: Int, targetMinute: Int, targetSecond: Int, offsetMillis: Long = 0): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, targetSecond)
            set(Calendar.MILLISECOND, 0)
        }

        // If target time has already passed today, target for tomorrow
        if (target.timeInMillis + offsetMillis < now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        return (target.timeInMillis + offsetMillis) - now.timeInMillis
    }
}
