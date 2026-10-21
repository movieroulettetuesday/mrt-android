package com.movieroulettetuesday.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File
import java.io.IOException

/** Tiny settings abstraction (iOS UserDefaults) so stores are testable without Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String): Long? = getString(key)?.toLongOrNull()
    fun putLong(key: String, value: Long?) = putString(key, value?.toString())
    fun getBoolean(key: String, default: Boolean): Boolean = getString(key)?.toBooleanStrictOrNull() ?: default
    fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
}

class InMemoryKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private val map = HashMap(initial)
    @Synchronized override fun getString(key: String): String? = map[key]
    @Synchronized override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** Everything is stored as strings in one private SharedPreferences file ("mrt"). */
class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) = prefs.edit { if (value == null) remove(key) else putString(key, value) }

    companion object {
        const val FILE = "mrt"
        fun of(context: Context) = PrefsKeyValueStore(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

object AtomicFile {
    fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Couldn't write ${target.name}")
        }
    }
}
