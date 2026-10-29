@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package uk.mywisp.privatechat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) LiveSyncService.start(this)
    }
    fun enableLiveNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences("live-notifications", MODE_PRIVATE)
            if (!prefs.getBoolean("permission_asked", false)) {
                prefs.edit().putBoolean("permission_asked", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else LiveSyncService.start(this)
    }
    override fun onStart() { super.onStart(); (application as ChatApplication).repository.setForeground(true) }
    override fun onStop() { (application as ChatApplication).repository.setForeground(false); super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as ChatApplication).repository
        setContent {
            val appearance by repo.appearance.state.collectAsStateWithLifecycle()
            PrivateTheme(appearance) { LeanApp(repo, this) }
        }
    }
}

@Composable
internal fun MessageBubble(repo: MessengerRepository, message: ChatMessage, onLongPress: () -> Unit = {}, onClick: () -> Unit = {}, selected: Boolean = false, quote: String? = null) {
    var opened by remember { mutableStateOf<File?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var shouldLoad by remember { mutableStateOf(message.attachment?.kind == "image") }
    var attempt by remember { mutableIntStateOf(0) }
    val attachment = message.attachment
    var file by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(attachment?.source?.url(), shouldLoad, attempt) {
        if (attachment != null && shouldLoad && file == null) {
            loading = true
            try { file = repo.download(attachment); loadError = false } catch (_: Exception) { loadError = true }
            loading = false
        }
    }
    val image by produceState<android.graphics.Bitmap?>(null, file, attachment?.kind) {
        if (file != null && attachment?.kind == "image") value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file!!.path, options)
                var sample = 1
                while (options.outWidth / sample > 1024 || options.outHeight / sample > 1024) sample *= 2
                BitmapFactory.decodeFile(file!!.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    val appearance = LocalAppearance.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.own) Arrangement.End else Arrangement.Start) {
        Surface(shape = RoundedCornerShape(topStart = appearance.bubbleRadius.dp, topEnd = appearance.bubbleRadius.dp, bottomStart = if (message.own) appearance.bubbleRadius.dp else 8.dp, bottomEnd = if (message.own) 8.dp else appearance.bubbleRadius.dp), color = if (message.own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 310.dp).combinedClickable(onClick = onClick, onLongClick = onLongPress), tonalElevation = if (selected) 6.dp else 0.dp) {
            Column(Modifier.padding(if (appearance.compact) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (quote != null) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .45f)) {
                    Column(Modifier.padding(10.dp)) { Text("В ответ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); Text(quote, maxLines = 2, style = MaterialTheme.typography.bodySmall) }
                }
                if (attachment == null) {
                    Text(message.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = appearance.textSize.sp), color = if (message.own) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                }
                else {
                    val circle = attachment.kind == "circle"
                    Box(Modifier.then(if (circle) Modifier.size(200.dp).clip(CircleShape) else Modifier.fillMaxWidth().heightIn(min = 64.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).combinedClickable(onLongClick = onLongPress, onClick = {
                            if (selected) onClick() else if (file != null) opened = file else { shouldLoad = true; attempt++ }
                        }), contentAlignment = Alignment.Center) {
                        if (image != null) Image(image!!.asImageBitmap(), "Фото", Modifier.fillMaxWidth().heightIn(max = 280.dp))
                        else if (loading) CircularProgressIndicator(Modifier.padding(16.dp))
                        else Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (attachment.kind == "voice") Icons.Default.PlayArrow else if (circle) Icons.Default.PlayCircle else if (attachment.kind == "file") Icons.Default.InsertDriveFile else Icons.Default.Movie, null, Modifier.size(40.dp))
                            Text(if (loadError) "Нажмите, чтобы повторить" else if (file != null) "Воспроизвести" else when (attachment.kind) {
                                "voice" -> "Голосовое"; "circle" -> "Кружочек"; "video" -> "Видео"; "file" -> attachment.name; else -> "Фото"
                            })
                        }
                    }
                }
                message.trustWarning?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (message.starred) Icon(Icons.Default.Star, "Помечено", Modifier.size(12.dp))
                    Text(if (message.status.isNotEmpty()) message.status else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.time)),
                        style = MaterialTheme.typography.labelSmall,
                        color = (if (message.own) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = .75f))
                    if (message.own && message.status.isEmpty()) Icon(if (message.read) Icons.Default.DoneAll else Icons.Default.Done,
                        if (message.read) "Прочитано" else "Отправлено", Modifier.size(15.dp), tint = if (message.read) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
    if (opened != null && attachment != null) MediaDialog(opened!!, attachment.kind) { opened = null }
}

@Composable
internal fun MediaDialog(file: File, kind: String, close: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (kind == "file") {
                    Icon(Icons.Default.InsertDriveFile, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Вложение", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = {
                        try {
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, "Нет приложения для просмотра этого файла", Toast.LENGTH_SHORT).show() }
                    }) { Text("Открыть в приложении") }
                } else if (kind == "image") {
                    val bitmap by produceState<android.graphics.Bitmap?>(null, file) {
                        value = withContext(Dispatchers.IO) {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.path, bounds)
                            var sample = 1
                            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                    }
                    bitmap?.let { ZoomablePhoto(it) }
                } else {
                    val player = remember(file) { ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file))); prepare(); playWhenReady = true
                    } }
                    DisposableEffect(player) { onDispose { player.release() } }
                    AndroidView(factory = { (android.view.LayoutInflater.from(it).inflate(R.layout.video_player, null) as PlayerView).apply { this.player = player } },
                        modifier = if (kind == "circle") Modifier.size(280.dp).clip(CircleShape) else Modifier.fillMaxWidth().height(if (kind == "voice") 110.dp else 320.dp))
                }
                TextButton(onClick = close) { Text("Закрыть") }
            }
        }
    }
}
