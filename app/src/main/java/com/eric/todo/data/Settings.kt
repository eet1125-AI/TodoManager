package com.eric.todo.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App settings, saved on the phone. */
class Settings private constructor(context: Context) {

    data class Values(
        val textSize: Int,        // task text size in sp
        val lightTheme: Boolean,
        val geminiKey: String,
        val geminiModel: String,
        val useAi: Boolean,       // use AI for imports when a key is set
        val lastList: String,
    )

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values.asStateFlow()

    private fun read() = Values(
        textSize = prefs.getInt("text_size", 24),
        lightTheme = prefs.getBoolean("light_theme", false),
        geminiKey = prefs.getString("gemini_key", "") ?: "",
        geminiModel = prefs.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
        useAi = prefs.getBoolean("use_ai", true),
        lastList = prefs.getString("last_list", BUSINESS) ?: BUSINESS,
    )

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _values.value = read()
    }

    fun setTextSize(sp: Int) = edit { putInt("text_size", sp) }
    fun setLightTheme(on: Boolean) = edit { putBoolean("light_theme", on) }
    fun setGeminiKey(key: String) = edit { putString("gemini_key", key.trim()) }
    fun setGeminiModel(model: String) = edit { putString("gemini_model", model.trim()) }
    fun setUseAi(on: Boolean) = edit { putBoolean("use_ai", on) }
    fun setLastList(list: String) = edit { putString("last_list", list) }

    /** Which list each home-screen widget shows. */
    fun widgetList(widgetId: Int): String = prefs.getString("widget_list_$widgetId", BUSINESS) ?: BUSINESS
    fun setWidgetList(widgetId: Int, list: String) = prefs.edit().putString("widget_list_$widgetId", list).apply()

    val aiReady: Boolean get() = _values.value.geminiKey.isNotBlank() && _values.value.useAi

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val LITE_MODEL = "gemini-3.5-flash-lite"
        val TEXT_SIZES = listOf(20 to "Large", 24 to "Extra large", 28 to "Huge", 32 to "Giant")

        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings =
            instance ?: synchronized(this) { instance ?: Settings(context).also { instance = it } }
    }
}
