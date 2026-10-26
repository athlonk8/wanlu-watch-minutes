package com.zcode.watchrec

import android.content.Context
import java.io.File

/** Private storage is reliable on ColorOS; keep reading the 1.x external directory. */
object RecordingStorage {
    fun recordings(context: Context): List<File> {
        val directories = listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
        return directories.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.extension == "m4a" }?.toList().orEmpty()
        }.distinctBy { it.name }.sortedByDescending { it.name }
    }
    fun find(context: Context, name: String): File? {
        if (!Regex("REC_[A-Za-z0-9_-]+\\.m4a").matches(name)) return null
        return recordings(context).firstOrNull { it.name == name }
    }
}
