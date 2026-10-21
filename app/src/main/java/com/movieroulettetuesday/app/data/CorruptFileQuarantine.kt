package com.movieroulettetuesday.app.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Moves an unreadable data file aside as `name.corrupt-yyyyMMdd-HHmmss[-n].ext` (UTC) so the app can
 * start fresh without silently overwriting it; keeps the newest [KEEP_BACKUPS]. Port of iOS
 * `CorruptFileQuarantine`.
 */
object CorruptFileQuarantine {
    const val KEEP_BACKUPS = 3

    fun quarantine(file: File, now: Long = System.currentTimeMillis()): File? {
        if (!file.exists()) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
        val base = file.nameWithoutExtension
        val ext = file.extension
        val dir = file.absoluteFile.parentFile ?: return null
        fun name(n: Int) = "$base.corrupt-$stamp" + (if (n > 0) "-$n" else "") + (if (ext.isNotEmpty()) ".$ext" else "")
        var n = 0
        var backup = File(dir, name(n))
        while (backup.exists()) backup = File(dir, name(++n))
        if (!file.renameTo(backup)) {
            file.delete() // last resort, so the app can't get stuck
            return null
        }
        prune(dir, base)
        return backup
    }

    fun backups(file: File): List<File> {
        val dir = file.absoluteFile.parentFile ?: return emptyList()
        val prefix = "${file.nameWithoutExtension}.corrupt-"
        return dir.listFiles().orEmpty().filter { it.name.startsWith(prefix) }.sortedBy { it.name }
    }

    private fun prune(dir: File, base: String) {
        val all = backups(File(dir, base))
        if (all.size > KEEP_BACKUPS) all.take(all.size - KEEP_BACKUPS).forEach { it.delete() }
    }
}
