package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AspectPreset
import com.example.model.FitMode
import com.example.model.HistoryEntry
import com.example.model.HistoryEntryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.slicemarkDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "slicemark_studio_prefs",
)

class LocalPreferencesRepository(private val context: Context) {

    companion object {
        private const val MAX_HISTORY_ITEMS = 20
        private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
        private val KEY_HISTORY_JSON = stringPreferencesKey("local_history_json")
    }

    val darkThemeFlow: Flow<Boolean> = context.slicemarkDataStore.data.map { prefs ->
        prefs[KEY_DARK_THEME] ?: true // Confirmed Phase 1 default: Dark Studio
    }

    val historyFlow: Flow<List<HistoryEntry>> = context.slicemarkDataStore.data.map { prefs ->
        decodeHistory(prefs[KEY_HISTORY_JSON].orEmpty())
    }

    suspend fun setDarkTheme(dark: Boolean) {
        context.slicemarkDataStore.edit { prefs ->
            prefs[KEY_DARK_THEME] = dark
        }
    }

    suspend fun addHistoryEntry(entry: HistoryEntry) {
        context.slicemarkDataStore.edit { prefs ->
            val current = decodeHistory(prefs[KEY_HISTORY_JSON].orEmpty()).toMutableList()
            current.removeAll { it.id == entry.id }
            current.add(0, entry)
            val trimmed = current.take(MAX_HISTORY_ITEMS)
            prefs[KEY_HISTORY_JSON] = encodeHistory(trimmed)
        }
    }

    suspend fun deleteHistoryEntry(id: String) {
        context.slicemarkDataStore.edit { prefs ->
            val current = decodeHistory(prefs[KEY_HISTORY_JSON].orEmpty()).filterNot { it.id == id }
            prefs[KEY_HISTORY_JSON] = encodeHistory(current)
        }
    }

    suspend fun clearHistory() {
        context.slicemarkDataStore.edit { prefs ->
            prefs[KEY_HISTORY_JSON] = "[]"
        }
    }

    private fun encodeHistory(list: List<HistoryEntry>): String {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("type", item.type.name)
                put("title", item.title)
                put("subtitle", item.subtitle)
                put("sourceVideoName", item.sourceVideoName)
                put("timestampMs", item.timestampMs)
                put("brightness", item.brightness.toDouble())
                put("contrast", item.contrast.toDouble())
                put("saturation", item.saturation.toDouble())
                put("sharpen", item.sharpen.toDouble())
                put("denoiseEnabled", item.denoiseEnabled)
                put("aspectPresetName", item.aspectPresetName)
                put("fitModeName", item.fitModeName)
                put("volumeGain", item.volumeGain.toDouble())
                put("loudnessNormalize", item.loudnessNormalize)
                put("voiceClarityEq", item.voiceClarityEq)
                put("exportSummary", item.exportSummary ?: JSONObject.NULL)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun decodeHistory(raw: String): List<HistoryEntry> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val type = runCatching {
                        HistoryEntryType.valueOf(obj.optString("type", HistoryEntryType.AUTO_TUNE_LOOK.name))
                    }.getOrDefault(HistoryEntryType.AUTO_TUNE_LOOK)
                    add(
                        HistoryEntry(
                            id = obj.optString("id"),
                            type = type,
                            title = obj.optString("title", "Studio Look"),
                            subtitle = obj.optString("subtitle", ""),
                            sourceVideoName = obj.optString("sourceVideoName", "Clip"),
                            timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                            brightness = obj.optDouble("brightness", 0.0).toFloat(),
                            contrast = obj.optDouble("contrast", 1.0).toFloat(),
                            saturation = obj.optDouble("saturation", 1.0).toFloat(),
                            sharpen = obj.optDouble("sharpen", 0.0).toFloat(),
                            denoiseEnabled = obj.optBoolean("denoiseEnabled", false),
                            aspectPresetName = obj.optString("aspectPresetName", AspectPreset.ORIGINAL.name),
                            fitModeName = obj.optString("fitModeName", FitMode.CROP_FILL.name),
                            volumeGain = obj.optDouble("volumeGain", 1.0).toFloat(),
                            loudnessNormalize = obj.optBoolean("loudnessNormalize", false),
                            voiceClarityEq = obj.optBoolean("voiceClarityEq", false),
                            exportSummary = obj.optString("exportSummary").takeIf { it.isNotEmpty() && it != "null" },
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
