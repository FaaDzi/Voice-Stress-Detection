package com.example.myapplication.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class VoiceProfile(
    val name: String,
    val focus: String, // all | patient | doctor
)

class VoiceProfileStore(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "voice_profiles"
        private const val PROFILES_KEY = "profiles_json"
        private const val DEFAULT_ALL = "All Voices"
        private const val DEFAULT_USER = "User Group"
        private const val DEFAULT_DOCTOR = "Doctor Group"
    }

    fun loadProfiles(): MutableList<VoiceProfile> {
        val raw = prefs().getString(PROFILES_KEY, null)
        if (raw.isNullOrBlank()) {
            return defaultProfiles().toMutableList()
        }

        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<VoiceProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val focus = obj.optString("focus", "all").lowercase()
                if (name.isBlank()) continue
                if (focus !in listOf("all", "patient", "doctor")) continue
                out.add(VoiceProfile(name, focus))
            }
            if (out.isEmpty()) defaultProfiles().toMutableList() else out
        } catch (_: Exception) {
            defaultProfiles().toMutableList()
        }
    }

    fun saveProfiles(profiles: List<VoiceProfile>) {
        val normalized = profiles
            .mapNotNull { p ->
                val name = p.name.trim()
                val focus = p.focus.lowercase()
                if (name.isBlank() || focus !in listOf("all", "patient", "doctor")) null
                else VoiceProfile(name, focus)
            }
            .distinctBy { it.name.lowercase() }

        val arr = JSONArray()
        for (p in normalized) {
            arr.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("focus", p.focus)
                }
            )
        }
        prefs().edit().putString(PROFILES_KEY, arr.toString()).apply()
    }

    fun upsertProfile(name: String, focus: String) {
        val profiles = loadProfiles()
        val safeName = name.trim()
        val safeFocus = focus.lowercase()
        if (safeName.isBlank() || safeFocus !in listOf("all", "patient", "doctor")) return

        val idx = profiles.indexOfFirst { it.name.equals(safeName, ignoreCase = true) }
        val updated = VoiceProfile(safeName, safeFocus)
        if (idx >= 0) profiles[idx] = updated else profiles.add(updated)
        saveProfiles(profiles)
    }

    fun removeProfileByName(name: String): Boolean {
        val profiles = loadProfiles().toMutableList()
        val idx = profiles.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (idx < 0) return false
        if (isDefaultProfile(profiles[idx].name)) return false
        profiles.removeAt(idx)
        saveProfiles(profiles)
        return true
    }

    fun isDefaultProfile(name: String): Boolean {
        return name.equals(DEFAULT_ALL, ignoreCase = true) ||
            name.equals(DEFAULT_USER, ignoreCase = true) ||
            name.equals(DEFAULT_DOCTOR, ignoreCase = true)
    }

    fun focusToLabel(focus: String): String {
        return when (focus.lowercase()) {
            "patient" -> "User"
            "doctor" -> "Doctor"
            else -> "All"
        }
    }

    private fun defaultProfiles(): List<VoiceProfile> {
        return listOf(
            VoiceProfile(DEFAULT_ALL, "all"),
            VoiceProfile(DEFAULT_USER, "patient"),
            VoiceProfile(DEFAULT_DOCTOR, "doctor"),
        )
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
