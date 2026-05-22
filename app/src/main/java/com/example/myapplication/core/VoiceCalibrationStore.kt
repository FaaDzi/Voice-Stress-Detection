package com.example.myapplication.core

import android.content.Context
import java.io.File

object VoiceCalibrationStore {
    private const val FINGERPRINT_FILENAME = "user1_voiceprint.json"

    // Returns true if User 1 has a saved voice fingerprint
    fun hasCalibration(context: Context): Boolean {
        return fingerprintFile(context).exists()
    }

    // Save the MFCC fingerprint JSON string returned by Python
    fun saveFingerprint(context: Context, mfccJson: String) {
        fingerprintFile(context).writeText(mfccJson)
    }

    // Load the stored fingerprint JSON, or null if not calibrated
    fun loadFingerprint(context: Context): String? {
        val f = fingerprintFile(context)
        return if (f.exists()) f.readText().takeIf { it.isNotBlank() } else null
    }

    // Delete the stored fingerprint (reset calibration)
    fun clearCalibration(context: Context): Boolean {
        val f = fingerprintFile(context)
        return if (f.exists()) f.delete() else false
    }

    private fun fingerprintFile(context: Context): File {
        val dir = File(context.filesDir, "voice_calibration")
        dir.mkdirs()
        return File(dir, FINGERPRINT_FILENAME)
    }
}
