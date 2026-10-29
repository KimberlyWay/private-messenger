package uk.mywisp.privatechat

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class DiagnosticEntry(val time: Long, val level: String, val code: String, val detail: String)

/** Only static event descriptions, numeric status and class/frame names. Never exception messages or HTTP bodies. */
internal class DiagnosticJournal(context: Context) {
    private val file = File(context.filesDir, "diagnostics.jsonl")
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val state = mutable.asStateFlow()
    init {
        synchronized(lock) {
            mutable.value = runCatching {
                if (file.length() > 1024 * 1024) emptyList() else file.readLines().takeLast(400).mapNotNull { line ->
                    runCatching { JSONObject(line).let { DiagnosticEntry(it.getLong("time"), it.getString("level"), it.getString("code"), it.getString("detail")) } }.getOrNull()
                }
            }.getOrDefault(emptyList())
        }
    }
    fun record(level: String, code: String, detail: String = "", error: Throwable? = null, immediate: Boolean = false) {
        val frames = error?.let {
            // Exception messages often contain tokens or server response bodies: omit them entirely.
            "\n" + it.javaClass.simpleName + " cause=" + (it.cause?.javaClass?.simpleName ?: "none") + "\n" + it.stackTrace.filter { frame -> frame.className.startsWith("uk.mywisp.privatechat") }
                .take(10).joinToString("\n") { frame -> "${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})" }
        }.orEmpty()
        synchronized(lock) {
            mutable.value = (mutable.value + DiagnosticEntry(System.currentTimeMillis(), level, code, (detail + frames).take(1800))).takeLast(400)
        }
        if (immediate) persist() else scope.launch { persist() }
    }
    private fun persist() = synchronized(lock) {
        runCatching {
            val temporary = File(file.parentFile, "diagnostics.tmp")
            temporary.writeText(mutable.value.joinToString("\n") { JSONObject().put("time", it.time).put("level", it.level)
                .put("code", it.code).put("detail", it.detail).toString() })
            if (!temporary.renameTo(file)) { temporary.copyTo(file, overwrite = true); temporary.delete() }
        }
        Unit
    }
    fun clear() { synchronized(lock) { mutable.value = emptyList(); persist() } }
    fun export(): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT)
        return "Private Messenger ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nAndroid ${android.os.Build.VERSION.SDK_INT}\n" +
            state.value.joinToString("\n\n") { "${format.format(Date(it.time))} ${it.level} ${it.code}\n${it.detail}" }
    }
}
