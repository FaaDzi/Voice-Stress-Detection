package com.example.myapplication.core

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R

object AppThemeManager {
    private const val PREFS_NAME = "app_theme"
    private const val THEME_KEY = "theme_mode"

    const val THEME1 = "theme1"
    const val THEME2 = "theme2"

    fun currentTheme(context: Context): String {
        val stored = prefs(context).getString(THEME_KEY, THEME1) ?: THEME1
        return if (stored == THEME2) THEME2 else THEME1
    }

    fun saveTheme(context: Context, themeMode: String) {
        prefs(context).edit().putString(THEME_KEY, if (themeMode == THEME2) THEME2 else THEME1).apply()
    }

    fun applyTheme(activity: AppCompatActivity) {
        val themeRes = if (currentTheme(activity) == THEME2)
            R.style.Theme_MyApplication_Theme2
        else
            R.style.Theme_MyApplication_Theme1
        activity.setTheme(themeRes)
    }

    fun isTheme2(context: Context): Boolean = currentTheme(context) == THEME2

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
