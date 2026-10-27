package com.zcode.watchrec

import android.content.Context

enum class RecordingQuality(val kbps: Int, val title: String, val megabytesPerHour: Int) {
    STANDARD(64, "标准", 29),
    HIGH(96, "高清", 43),
    HIGHER(128, "更高", 58);

    val bitsPerSecond: Int get() = kbps * 1000
}

internal object RecordingSettings {
    const val PREFS = "recording_settings"
    const val QUALITY_KEY = "quality_kbps"

    fun quality(context: Context): RecordingQuality {
        val stored = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(QUALITY_KEY, RecordingQuality.HIGH.kbps)
        }.getOrDefault(RecordingQuality.HIGH.kbps)
        return RecordingQuality.entries.firstOrNull { it.kbps == stored } ?: RecordingQuality.HIGH
    }

    fun select(context: Context, quality: RecordingQuality): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(QUALITY_KEY, quality.kbps).commit()
}
