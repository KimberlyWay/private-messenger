package uk.mywisp.privatechat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

internal class MessageNotifications(private val context: Context) {
    private val prefs = context.getSharedPreferences("live-notifications", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    init { channels(context) }

    @Synchronized fun received(raw: String, room: String, title: String, own: String, visible: Boolean) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (event.optString("type") != "m.room.message" || event.optString("sender") == own) return
        val id = event.optString("event_id")
        if (id.isBlank()) return
        val seenKey = "seen|$own"
        val seen = prefs.getString(seenKey, "").orEmpty().split('\n').filter { it.isNotBlank() }.toMutableList()
        if (id in seen) return
        seen.add(id)
        prefs.edit().putString(seenKey, seen.takeLast(256).joinToString("\n")).apply()
        // Initial history must not produce a burst of notifications on first login.
        if (!enabled || visible || !prefs.getBoolean("synced|$own", false)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val body = event.optJSONObject("content")?.optString("body").orEmpty().take(240).ifBlank { "Новое сообщение" }
        val notice = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title.take(80)).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open(context)).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, MESSAGES).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Private Messenger").setContentText("Новое сообщение").build())
            .build()
        manager.notify("message:$room", room.hashCode(), notice)
    }

    fun synced(user: String) { if (!prefs.getBoolean("synced|$user", false)) prefs.edit().putBoolean("synced|$user", true).apply() }
    fun clearMessages() { manager.activeNotifications.filter { it.tag?.startsWith("message:") == true }.forEach { manager.cancel(it.tag, it.id) } }

    companion object {
        const val MESSAGES = "messages"
        const val CONNECTION = "connection"
        fun channels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(MESSAGES, "Новые сообщения", NotificationManager.IMPORTANCE_HIGH))
            manager.createNotificationChannel(NotificationChannel(CONNECTION, "Связь в фоне", NotificationManager.IMPORTANCE_LOW))
        }
        fun open(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun connection(context: Context, text: String): Notification = NotificationCompat.Builder(context, CONNECTION)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Private Messenger").setContentText(text)
            .setContentIntent(open(context)).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).build()
    }
}
