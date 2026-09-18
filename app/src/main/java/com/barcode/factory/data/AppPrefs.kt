package com.barcode.factory.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the working draft, the last used render format/color/options and the archive list.
 * The last-used format is restored on every launch (feature: "格式支持记录上一次使用").
 */
class AppPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var lastFormat: String
        get() = prefs.getString(KEY_FORMAT, "EAN13") ?: "EAN13"
        set(value) = prefs.edit().putString(KEY_FORMAT, value).apply()

    var lastColor: String
        get() = prefs.getString(KEY_COLOR, "#000000") ?: "#000000"
        set(value) = prefs.edit().putString(KEY_COLOR, value).apply()

    var columns: Int
        get() = prefs.getInt(KEY_COLUMNS, 1)
        set(value) = prefs.edit().putInt(KEY_COLUMNS, value).apply()

    var showIndex: Boolean
        get() = prefs.getBoolean(KEY_SHOW_INDEX, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_INDEX, value).apply()

    var draft: List<String>
        get() = jsonToStringList(prefs.getString(KEY_DRAFT, null))
        set(value) = prefs.edit().putString(KEY_DRAFT, stringListToJson(value).toString()).apply()

    fun loadArchives(): MutableList<Archive> {
        val raw = prefs.getString(KEY_ARCHIVES, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            val result = mutableListOf<Archive>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                result.add(
                    Archive(
                        id = obj.optLong("id"),
                        time = obj.optLong("time"),
                        format = obj.optString("format", "EAN13"),
                        values = jsonToStringList(obj.optJSONArray("values")?.toString())
                    )
                )
            }
            result
        } catch (t: Throwable) {
            mutableListOf()
        }
    }

    fun saveArchives(archives: List<Archive>) {
        val array = JSONArray()
        archives.forEach { archive ->
            val obj = JSONObject()
            obj.put("id", archive.id)
            obj.put("time", archive.time)
            obj.put("format", archive.format)
            obj.put("values", stringListToJson(archive.values))
            array.put(obj)
        }
        prefs.edit().putString(KEY_ARCHIVES, array.toString()).apply()
    }

    private fun stringListToJson(values: List<String>): JSONArray {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array
    }

    private fun jsonToStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = mutableListOf<String>()
            for (i in 0 until array.length()) result.add(array.optString(i))
            result
        } catch (t: Throwable) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "barcode_factory"
        private const val KEY_FORMAT = "last_format"
        private const val KEY_COLOR = "last_color"
        private const val KEY_COLUMNS = "columns"
        private const val KEY_SHOW_INDEX = "show_index"
        private const val KEY_DRAFT = "draft"
        private const val KEY_ARCHIVES = "archives"
    }
}
