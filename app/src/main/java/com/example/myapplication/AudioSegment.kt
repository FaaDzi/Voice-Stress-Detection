package com.example.myapplication

import java.io.Serializable

data class AudioSegment(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val subjectName: String,
    val sourceFileName: String
) : Serializable {
    fun label(): String = "${formatMillis(startTimeMs)} - ${formatMillis(endTimeMs)}"

    companion object {
        fun formatMillis(value: Long): String {
            val totalSeconds = (value / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
    }
}
