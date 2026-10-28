package uk.mywisp.privatechat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Appearance(val theme: String = "system", val accent: String = "dynamic")

class AppearancePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(Appearance(prefs.getString("theme", "system")!!, prefs.getString("accent", "dynamic")!!))
    val state = mutable.asStateFlow()

    fun update(theme: String = mutable.value.theme, accent: String = mutable.value.accent) {
        require(theme in listOf("system", "light", "dark"))
        require(accent in listOf("dynamic", "lavender", "ocean", "forest"))
        prefs.edit().putString("theme", theme).putString("accent", accent).apply()
        mutable.value = Appearance(theme, accent)
    }
}
