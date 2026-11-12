package me.capcom.smsgateway.modules.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import java.lang.reflect.Type

class PreferencesStorage(
    private val preferences: SharedPreferences,
    private val prefix: String,
    serializer: ValueSerializer? = null,
    /** true: write with commit() (synchronous) instead of apply(). */
    private val synchronous: Boolean = false,
): KeyValueStorage {
    private val serializer = serializer ?: ValueSerializer()

    override fun <T> set(key: String, value: T) {
        val editor = preferences.edit()
        if (value == null) {
            editor.remove("${prefix}.${key}")
        } else {
            when (value) {
                is Long -> editor.putLong("${prefix}.${key}", value)
                is Int -> editor.putInt("${prefix}.${key}", value)
                is String -> editor.putString("${prefix}.${key}", value)
                is Boolean -> editor.putBoolean("${prefix}.${key}", value)
                is Float -> editor.putFloat("${prefix}.${key}", value)
                else -> editor.putString("${prefix}.${key}", serializer.serialize(value))
            }
        }
        if (synchronous) {
            // A failed write must not pass silently: the caller fails closed on the exception.
            if (!editor.commit()) throw java.io.IOException("preferences write failed")
        } else {
            editor.apply()
        }
    }

    override fun <T> get(key: String, typeOfT: Type): T? {
        if (!preferences.contains("${prefix}.${key}")) {
            return null
        }

        return try {
            preferences.getString("${prefix}.${key}", null)?.let {
                serializer.deserialize(it, typeOfT)
            }
        } catch (th: ClassCastException) {
            getFallback<T>(typeOfT, key)
        } catch (th: com.google.gson.JsonParseException) {
            getFallback<T>(typeOfT, key)
        }
    }

    override fun remove(key: String) {
        preferences.edit(commit = synchronous) {
            remove("${prefix}.${key}")
        }
    }

    private fun <T> getFallback(typeOfT: Type, key: String) = when (typeOfT) {
        java.lang.Long::class.java -> preferences.getLong("${prefix}.${key}", 0) as T
        Integer::class.java -> preferences.getInt("${prefix}.${key}", 0) as T
        java.lang.String::class.java -> preferences.getString("${prefix}.${key}", "") as T
        java.lang.Boolean::class.java -> preferences.getBoolean(
            "${prefix}.${key}",
            false
        ) as T

        java.lang.Float::class.java -> preferences.getFloat("${prefix}.${key}", 0.0f) as T
        else -> throw RuntimeException("Unknown type for key $key")
    }
}