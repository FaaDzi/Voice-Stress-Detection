package com.example.myapplication.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import kotlin.math.roundToInt

data class DeviceCapabilityReport(
    val summary: String,
    val scoreLabel: String,
    val scorePercent: Int,
    val canHandleMaxComparison: Boolean
)

object DeviceCapabilityChecker {
    fun buildReport(context: Context, storageManager: AppStorageManager): DeviceCapabilityReport {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val ramGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val availGb = memoryInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val lowRam = activityManager.isLowRamDevice
        val freeStorageGb = storageManager.appRootDir().usableSpace / (1024.0 * 1024.0 * 1024.0)

        var score = 0
        if (ramGb >= 6.0) score += 35 else if (ramGb >= 4.0) score += 22 else if (ramGb >= 3.0) score += 12
        if (cpuCores >= 8) score += 30 else if (cpuCores >= 6) score += 20 else if (cpuCores >= 4) score += 10
        if (freeStorageGb >= 8.0) score += 20 else if (freeStorageGb >= 4.0) score += 12 else if (freeStorageGb >= 2.0) score += 6
        if (!lowRam) score += 15

        val label = when {
            score >= 75 -> "Ready"
            score >= 55 -> "Usable"
            score >= 35 -> "Cautious"
            else -> "Limited"
        }
        val canHandleMax = score >= 55

        val lines = mutableListOf<String>()
        lines += "Device readiness for max compare mode:"
        lines += "Status: $label ($score/100)"
        lines += "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        lines += "RAM: ${ramGb.format1()} GB total, ${availGb.format1()} GB available now"
        lines += "CPU cores: $cpuCores"
        lines += "Low-RAM device flag: ${if (lowRam) "Yes" else "No"}"
        lines += "Free app storage area: ${freeStorageGb.format1()} GB"
        lines += ""
        lines += if (canHandleMax) {
            "This device should handle up to 4-model comparison for typical sessions, but long WAV files can still take time."
        } else {
            "This device may struggle with long sessions or 4-model comparison. Prefer single-model analysis or shorter audio files."
        }
        lines += "Tip: comparison mode writes extra charts/results for each saved model, so storage grows faster than normal analysis."

        return DeviceCapabilityReport(
            summary = lines.joinToString("\n"),
            scoreLabel = label,
            scorePercent = score.coerceIn(0, 100),
            canHandleMaxComparison = canHandleMax
        )
    }

    private fun Double.format1(): String = ((this * 10.0).roundToInt() / 10.0).toString()
}
