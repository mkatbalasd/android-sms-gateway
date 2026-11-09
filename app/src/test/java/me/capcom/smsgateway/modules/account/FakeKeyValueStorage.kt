package me.capcom.smsgateway.modules.account

import com.google.gson.Gson
import me.capcom.smsgateway.modules.settings.KeyValueStorage
import java.lang.reflect.Type

/**
 * A storage that keeps every value as the JSON text the real store would hold,
 * so a test can search [values] for anything that must never be written.
 */
internal class FakeKeyValueStorage : KeyValueStorage {
    val values = LinkedHashMap<String, String>()
    private val gson = Gson()

    override fun <T> set(key: String, value: T) {
        if (value == null) {
            values.remove(key)
        } else {
            values[key] = gson.toJson(value)
        }
    }

    override fun <T> get(key: String, typeOfT: Type): T? =
        values[key]?.let { gson.fromJson<T>(it, typeOfT) }

    override fun remove(key: String) {
        values.remove(key)
    }

    /** Everything stored, as one string. */
    fun dump(): String = values.entries.joinToString("\n") { "${it.key}=${it.value}" }
}
