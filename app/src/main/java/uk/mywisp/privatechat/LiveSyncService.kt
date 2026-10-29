package uk.mywisp.privatechat

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class LiveSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        MessageNotifications.channels(this)
        startForeground(41, MessageNotifications.connection(this, "Подключение…"))
        val repo = (application as ChatApplication).repository
        repo.resumeConnection()
        scope.launch {
            repo.state.map { Triple(it.loading, it.userId, it.connection) }.distinctUntilChanged().collect { (loading, user, status) ->
                if (!loading && user == null) { stopSelf(); return@collect }
                getSystemService(android.app.NotificationManager::class.java).notify(41, MessageNotifications.connection(this@LiveSyncService, status.label))
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!MessageNotifications(this).enabled) { stopSelf(); return START_NOT_STICKY }
        (application as ChatApplication).repository.resumeConnection()
        return START_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, LiveSyncService::class.java)) }
            catch (e: IllegalStateException) { (context.applicationContext as ChatApplication).repository.diagnostics.record("WARNING", "BACKGROUND_SERVICE_START_REJECTED", error = e) }
        }
    }
}
