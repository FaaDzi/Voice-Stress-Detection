package com.example.myapplication.core

import android.content.Context
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppStorageManager(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "app_setup"
        private const val STORAGE_PATH_KEY = "storage_root_path"
    }

    fun saveStorageRoot(path: File) {
        path.mkdirs()
        prefs().edit().putString(STORAGE_PATH_KEY, path.absolutePath).apply()
    }

    fun getPrimaryExternalDir(): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
    }

    fun getRemovableExternalDir(): File? {
        val dirs = ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_DOCUMENTS)
        return dirs.firstOrNull { it != null && Environment.isExternalStorageRemovable(it) }
    }

    fun appRootDir(): File {
        val saved = prefs().getString(STORAGE_PATH_KEY, null)
        val base = saved?.let { File(it) }?.takeIf { it.exists() || it.mkdirs() } ?: getPrimaryExternalDir()
        return File(base, "StressDetection")
    }

    fun assetDir(): File = File(appRootDir(), "Asset")
    fun soundDir(): File = File(appRootDir(), "sound")
    fun imgDir(): File = File(appRootDir(), "img")
    fun runsDir(): File = File(appRootDir(), "runs")
    fun logsDir(): File = File(appRootDir(), "logs")
    fun setupLogFile(): File = File(logsDir(), "setup-log.txt")

    fun createSessionId(): String {
        return "run-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }

    fun sessionDir(sessionId: String): File = File(runsDir(), sessionId)
    fun sessionSoundDir(sessionId: String): File = File(sessionDir(sessionId), "sound")
    fun sessionImgDir(sessionId: String): File = File(sessionDir(sessionId), "img")
    fun sessionMetaFile(sessionId: String): File = File(sessionDir(sessionId), "session.json")
    fun sessionCompareDir(sessionId: String): File = File(sessionDir(sessionId), "compare")
    fun sessionCompareReportFile(sessionId: String): File = File(sessionDir(sessionId), "comparison_report.json")

    fun appUsageBytes(): Long = dirSize(appRootDir())

    fun compareCacheBytes(): Long {
        val runs = runsDir()
        if (!runs.exists()) return 0L
        return runs.listFiles()?.sumOf { session ->
            dirSize(File(session, "compare")) + sessionCompareReportFile(session.name).takeIf { it.exists() }?.length().orZero()
        } ?: 0L
    }

    fun clearCompareCaches(): Int {
        val runs = runsDir()
        if (!runs.exists()) return 0
        var cleared = 0
        runs.listFiles()?.forEach { session ->
            val compareDir = File(session, "compare")
            if (compareDir.exists()) {
                compareDir.deleteRecursively()
                cleared += 1
            }
            val report = sessionCompareReportFile(session.name)
            if (report.exists()) {
                report.delete()
            }
        }
        return cleared
    }

    private fun dirSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
