package com.example.myapplication.core

import android.content.Context

class SetupState(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "app_setup"
        private const val SETUP_VERSION_KEY = "setup_version"
    }

    fun isSetupComplete(currentVersion: Int): Boolean {
        return prefs().getInt(SETUP_VERSION_KEY, 0) >= currentVersion
    }

    fun markSetupComplete(currentVersion: Int) {
        prefs().edit().putInt(SETUP_VERSION_KEY, currentVersion).apply()
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
