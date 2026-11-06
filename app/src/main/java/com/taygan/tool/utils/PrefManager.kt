package com.taygan.tool.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.taygan.tool.model.VoucherItem

class PrefManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tay_gan_tool_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_HUNT_BY_PERCENT = "hunt_by_percent"
        private const val KEY_AUTO_ORDER = "auto_order"
        private const val KEY_HUNT_BY_TIME = "hunt_by_time"
        private const val KEY_TARGET_HOUR = "target_hour"
        private const val KEY_TARGET_MINUTE = "target_minute"
        private const val KEY_TARGET_SECOND = "target_second"
        private const val KEY_VOUCHER_LIST = "voucher_list"
        private const val KEY_ACTIVE_TAB = "active_tab"
    }

    var huntByPercent: Boolean
        get() = prefs.getBoolean(KEY_HUNT_BY_PERCENT, false)
        set(value) = prefs.edit().putBoolean(KEY_HUNT_BY_PERCENT, value).apply()

    var autoOrder: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ORDER, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ORDER, value).apply()

    var huntByTime: Boolean
        get() = prefs.getBoolean(KEY_HUNT_BY_TIME, true)
        set(value) = prefs.edit().putBoolean(KEY_HUNT_BY_TIME, value).apply()

    var targetHour: Int
        get() = prefs.getInt(KEY_TARGET_HOUR, 17)
        set(value) = prefs.edit().putInt(KEY_TARGET_HOUR, value).apply()

    var targetMinute: Int
        get() = prefs.getInt(KEY_TARGET_MINUTE, 59)
        set(value) = prefs.edit().putInt(KEY_TARGET_MINUTE, value).apply()

    var targetSecond: Int
        get() = prefs.getInt(KEY_TARGET_SECOND, 58)
        set(value) = prefs.edit().putInt(KEY_TARGET_SECOND, value).apply()

    var activeTab: Int
        get() = prefs.getInt(KEY_ACTIVE_TAB, 0)
        set(value) = prefs.edit().putInt(KEY_ACTIVE_TAB, value).apply()

    fun getVouchers(): MutableList<VoucherItem> {
        val json = prefs.getString(KEY_VOUCHER_LIST, null) ?: return VoucherItem.getDefaultList()
        val type = object : TypeToken<MutableList<VoucherItem>>() {}.type
        return try {
            gson.fromJson(json, type) ?: VoucherItem.getDefaultList()
        } catch (e: Exception) {
            VoucherItem.getDefaultList()
        }
    }

    fun saveVouchers(list: List<VoucherItem>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_VOUCHER_LIST, json).apply()
    }
}
