package uk.mywisp.privatechat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Appearance(
    val theme: String = "system", val accent: String = "dynamic",
    val hue: Float = 275f, val contrast: Float = 0f, val amoled: Boolean = false,
    val textSize: Float = 16f, val bubbleRadius: Float = 24f,
    val background: String = "tonal", val compact: Boolean = false, val uiScale: Float = 1f,
)

class AppearancePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(Appearance(
        theme = prefs.getString("theme", "system")!!, accent = prefs.getString("accent", "dynamic")!!,
        hue = prefs.getFloat("hue", 275f), contrast = prefs.getFloat("contrast", 0f),
        amoled = prefs.getBoolean("amoled", false), textSize = prefs.getFloat("textSize", 16f),
        bubbleRadius = prefs.getFloat("bubbleRadius", 24f), background = prefs.getString("background", "tonal")!!,
        compact = prefs.getBoolean("compact", false), uiScale = prefs.getFloat("uiScale", 1f),
    ))
    val state = mutable.asStateFlow()

    fun update(theme: String = mutable.value.theme, accent: String = mutable.value.accent) =
        set(mutable.value.copy(theme = theme, accent = accent))

    fun set(value: Appearance) {
        require(value.theme in listOf("system", "light", "dark"))
        require(value.accent in listOf("dynamic", "lavender", "ocean", "forest", "rose", "peach", "custom"))
        require(value.hue.isFinite() && value.hue in 0f..360f)
        require(value.contrast.isFinite() && value.contrast in 0f..1f)
        require(value.textSize.isFinite() && value.textSize in 14f..22f)
        require(value.bubbleRadius.isFinite() && value.bubbleRadius in 4f..28f)
        require(value.uiScale.isFinite() && value.uiScale in .85f..1.2f)
        require(value.background in listOf("plain", "tonal", "gradient"))
        prefs.edit().putString("theme", value.theme).putString("accent", value.accent)
            .putFloat("hue", value.hue).putFloat("contrast", value.contrast).putBoolean("amoled", value.amoled)
            .putFloat("textSize", value.textSize).putFloat("bubbleRadius", value.bubbleRadius)
            .putString("background", value.background).putBoolean("compact", value.compact).putFloat("uiScale", value.uiScale).apply()
        mutable.value = value
    }

    fun reset() = set(Appearance())
}
