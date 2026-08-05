package com.netfreeguard.app

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("netfree_guard_prefs", Context.MODE_PRIVATE)

    fun getCheckHostPort(): String? = prefs.getString("check_host_port", null)

    fun setCheckHostPort(value: String) {
        prefs.edit().putString("check_host_port", value).apply()
    }

    fun isInvertLogic(): Boolean = prefs.getBoolean("invert_logic", false)

    fun setInvertLogic(value: Boolean) {
        prefs.edit().putBoolean("invert_logic", value).apply()
    }

    fun isProtectionEnabled(): Boolean = prefs.getBoolean("protection_enabled", false)

    fun setProtectionEnabled(value: Boolean) {
        prefs.edit().putBoolean("protection_enabled", value).apply()
    }
}
