@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PrivateTheme { Messenger((application as ChatApplication).repository, this) } }
    }
}

@Composable
fun PrivateTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialExpressiveTheme(colorScheme = colors, content = content)
}

@Composable
private fun Messenger(repo: MessengerRepository, activity: ComponentActivity) {
    val s by repo.state.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = settings || s.roomId != null) { if (settings) settings = false else repo.back() }
    val keyVisible = s.recoveryKey != null
    DisposableEffect(keyVisible) {
        if (keyVisible) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (keyVisible) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    if (s.loading) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (s.userId == null) {
        AuthenticationScreen(repo, s)
    } else {
        Scaffold(topBar = {
            TopAppBar(title = { Column {
                Text(if (settings) "Настройки" else if (s.roomId != null) s.roomName else "Личный чат", maxLines = 1)
                if (s.roomId != null && !settings) Text("Сквозное шифрование", style = MaterialTheme.typography.labelSmall)
            } }, navigationIcon = {
                if (settings || s.roomId != null) IconButton(onClick = { if (settings) settings = false else repo.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                }
            }, actions = {
                if (s.roomId != null && !settings) IconButton(onClick = repo::requestVerification, enabled = !s.busy) {
                    Icon(Icons.Default.VerifiedUser, "Проверить собеседника")
                }
                IconButton(onClick = repo::refresh, enabled = !s.busy) { Icon(Icons.Default.Refresh, "Обновить") }
                if (!settings) IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Настройки") }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    settings -> SettingsScreen(repo, s)
                    s.roomId != null -> ConversationScreen(repo, s)
                    else -> RoomsScreen(repo, s)
                }
            }
        }
    }
    s.error?.let { error -> AlertDialog(onDismissRequest = repo::dismissError, title = { Text("Не удалось выполнить действие") },
        text = { Text(error) }, confirmButton = { TextButton(onClick = repo::dismissError) { Text("Понятно") } }) }
    s.recoveryKey?.let { key ->
        val context = LocalContext.current
        AlertDialog(onDismissRequest = {}, title = { Text("Сохраните ключ восстановления") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Он нужен для истории на новом устройстве. Сервер не сможет восстановить его за вас. Сохраните ключ в менеджере паролей.")
                Text(key, fontWeight = FontWeight.Bold)
            } }, dismissButton = { TextButton(onClick = {
                val clip = ClipData.newPlainText("Ключ восстановления", key)
                clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            }) { Text("Скопировать") } }, confirmButton = { TextButton(onClick = repo::hideRecovery) { Text("Я сохранил ключ") } })
    }
    s.verification?.let { prompt -> AlertDialog(onDismissRequest = repo::cancelVerification,
        title = { Text(prompt.title) }, text = { Text(prompt.content, fontSize = if (prompt.stage == "compare") 26.sp else 16.sp) },
        confirmButton = {
            when (prompt.stage) {
                "request" -> TextButton(onClick = repo::acceptVerification) { Text("Принять") }
                "compare" -> TextButton(onClick = repo::approveVerification) { Text("Совпадают") }
                "done" -> TextButton(onClick = repo::closeVerification) { Text("Закрыть") }
            }
        }, dismissButton = { if (prompt.stage != "done") TextButton(onClick = repo::cancelVerification) {
            Text(if (prompt.stage == "compare") "Не совпадают" else "Отмена")
        } }) }
}

@Composable
private fun AuthenticationScreen(repo: MessengerRepository, s: ChatState) {
    var register by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf("") }
    var login by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    // Passwords deliberately do not use rememberSaveable.
    var password by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Spacer(Modifier.height(32.dp)); Icon(Icons.Default.Forum, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary) }
        item { Text(if (register) "Создать аккаунт" else "Личный чат", style = MaterialTheme.typography.headlineLarge) }
        item { Text("Переписка на вашем сервере", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Адрес сервера") },
            placeholder = { Text("https://chat.example.com:8443") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) }
        item { OutlinedTextField(login, { login = it }, Modifier.fillMaxWidth(), label = { Text("Логин для входа") }, singleLine = true,
            supportingText = { if (register) Text("Приватный логин: 3–64 символа a-z, 0-9, _, -, .") }) }
        if (register) item { OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Публичный @username") },
            singleLine = true, supportingText = { Text("Для поиска вас: 3–32 символа a-z, 0-9, _") }) }
        item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { if (register) Text("От 12 символов, до 72 байт UTF-8") }) }
        item { Button(onClick = {
            repo.authenticate(server, login, username, password, register)
            password = ""
        }, enabled = !s.busy && server.isNotBlank() && login.isNotBlank() && password.isNotBlank() && (!register || username.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(if (register) "Зарегистрироваться" else "Войти") } }
        item { TextButton(onClick = { register = !register }, enabled = !s.busy) {
            Text(if (register) "Уже есть аккаунт — войти" else "Создать аккаунт")
        } }
        if (s.busy) item { CircularProgressIndicator() }
    }
}

@Composable
private fun RoomsScreen(repo: MessengerRepository, s: ChatState) {
    var adding by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        if (s.rooms.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp)); Text("Начните первую переписку", style = MaterialTheme.typography.titleLarge)
            Text("Найдите человека по @username", Modifier.padding(top = 8.dp))
        }
        LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.rooms, key = { it.id }) { room ->
                Card(onClick = { repo.openRoom(room.id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    ListItem(headlineContent = { Text(room.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(if (room.invited) "Приглашение — нажмите, чтобы принять" else "Личная переписка") },
                        leadingContent = { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(if (room.invited) Icons.Default.MarkChatUnread else Icons.Default.Person, null, Modifier.padding(16.dp))
                        } })
                }
            }
        }
        FloatingActionButton(onClick = { adding = true }, Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
            Icon(Icons.Default.Add, "Новый чат")
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("Новый чат") },
        text = { OutlinedTextField(username, { username = it }, label = { Text("@username") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { repo.findAndChat(username); adding = false }, enabled = username.isNotBlank() && !s.busy) { Text("Написать") } },
        dismissButton = { TextButton(onClick = { adding = false }) { Text("Отмена") } })
}

@Composable
private fun ConversationScreen(repo: MessengerRepository, s: ChatState) {
    val context = LocalContext.current
    var text by remember(s.roomId) { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    val voice = remember { VoiceRecorder(context) }
    fun finishVoice() {
        val result = voice.stop()
        recording = false
        if (result != null) repo.sendRecorded(result.first, "voice", result.second)
        else Toast.makeText(context, "Запись слишком короткая или недоступна", Toast.LENGTH_SHORT).show()
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) {
            runCatching { voice.start { finishVoice() }; recording = true; seconds = 0 }
                .onFailure { Toast.makeText(context, "Не удалось включить микрофон", Toast.LENGTH_SHORT).show() }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(repo::sendPicked) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.getStringExtra("file")?.let {
            repo.sendRecorded(File(it), "circle", result.data?.getLongExtra("duration", 0) ?: 0)
        }
    }
    val cameraPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) camera.launch(Intent(context, CircleCaptureActivity::class.java))
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { voice.cancel(); recording = false }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); voice.cancel() }
    }
    LaunchedEffect(recording) { while (recording) { delay(1000); seconds++ } }
    val list = rememberLazyListState()
    LaunchedEffect(s.messages.lastOrNull()?.id) { if (s.messages.isNotEmpty()) list.animateScrollToItem(s.messages.size) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TextButton(onClick = repo::older, modifier = Modifier.fillMaxWidth(), enabled = !s.busy) { Text("Загрузить предыдущие") } }
            items(s.messages, key = { it.id }) { message -> MessageBubble(repo, message) }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                if (recording) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error)
                    Text("Запись: ${seconds} с", Modifier.weight(1f).padding(8.dp))
                    TextButton(onClick = { voice.cancel(); recording = false }) { Text("Отмена") }
                    FilledTonalButton(onClick = { finishVoice() }) { Text("Отправить") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(text, { if (it.length <= 16_000) text = it }, Modifier.weight(1f),
                            placeholder = { Text("Сообщение") }, maxLines = 5, shape = RoundedCornerShape(28.dp))
                        IconButton(onClick = { val original = text; repo.sendText(original) { if (text == original) text = "" } }, enabled = text.isNotBlank() && !s.busy) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Отправить")
                        }
                    }
                    Row {
                        IconButton(onClick = { picker.launch(arrayOf("image/*", "video/*")) }, enabled = !s.busy) { Icon(Icons.Default.AttachFile, "Фото или видео") }
                        IconButton(onClick = { microphone.launch(Manifest.permission.RECORD_AUDIO) }, enabled = !s.busy) { Icon(Icons.Default.Mic, "Голосовое сообщение") }
                        IconButton(onClick = { cameraPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }, enabled = !s.busy) {
                            Icon(Icons.Default.Videocam, "Записать кружочек")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(repo: MessengerRepository, message: ChatMessage) {
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.own) Arrangement.End else Arrangement.Start) {
        Surface(shape = RoundedCornerShape(22.dp), color = if (message.own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 310.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (attachment == null) Text(message.text, style = MaterialTheme.typography.bodyLarge)
                else {
                    val circle = attachment.kind == "circle"
                    Box(Modifier.then(if (circle) Modifier.size(200.dp).clip(CircleShape) else Modifier.fillMaxWidth().heightIn(min = 64.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).clickable {
                            if (file != null) opened = file else { shouldLoad = true; attempt++ }
                        }, contentAlignment = Alignment.Center) {
                        if (image != null) Image(image!!.asImageBitmap(), "Фото", Modifier.fillMaxWidth().heightIn(max = 280.dp))
                        else if (loading) CircularProgressIndicator(Modifier.padding(16.dp))
                        else Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (attachment.kind == "voice") Icons.Default.PlayArrow else if (circle) Icons.Default.PlayCircle else Icons.Default.Movie, null, Modifier.size(40.dp))
                            Text(if (loadError) "Нажмите, чтобы повторить" else if (file != null) "Воспроизвести" else when (attachment.kind) {
                                "voice" -> "Голосовое"; "circle" -> "Кружочек"; "video" -> "Видео"; else -> "Фото"
                            })
                        }
                    }
                }
                Text(if (message.status.isNotEmpty()) message.status else SimpleDateFormat("HH:mm",
                    Locale.forLanguageTag(androidx.compose.ui.text.intl.Locale.current.toLanguageTag())).format(Date(message.time)),
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
            }
        }
    }
    if (opened != null && attachment != null) MediaDialog(opened!!, attachment.kind) { opened = null }
}

@Composable
private fun MediaDialog(file: File, kind: String, close: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (kind == "image") {
                    val bitmap by produceState<android.graphics.Bitmap?>(null, file) {
                        value = withContext(Dispatchers.IO) {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.path, bounds)
                            var sample = 1
                            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                    }
                    bitmap?.let { Image(it.asImageBitmap(), "Фото", Modifier.fillMaxWidth().heightIn(max = 500.dp)) }
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

@Composable
private fun SettingsScreen(repo: MessengerRepository, s: ChatState) {
    var recoveryInput by remember { mutableStateOf("") }
    var logout by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(s.userId.orEmpty(), style = MaterialTheme.typography.titleLarge) }
        item { Text(s.server, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Защита переписки", style = MaterialTheme.typography.titleMedium) }
        item { Text(if (s.verified) "Это устройство подтверждено" else "Устройство требует подтверждения или восстановления ключей") }
        item { Text("Передавайте ключ восстановления только своим устройствам. Чтобы проверить собеседника, откройте чат и нажмите значок щита.") }
        item { Text("Восстановление: " + when (s.recovery) {
            "ENABLED" -> "настроено"; "DISABLED" -> "не настроено"; "INCOMPLETE" -> "требуется существующий ключ"; else -> "проверяем…"
        }) }
        if (s.recovery == "DISABLED" && s.verified) item { Button(onClick = repo::createRecovery, enabled = !s.busy) { Text("Создать ключ восстановления") } }
        item { OutlinedTextField(recoveryInput, { recoveryInput = it }, Modifier.fillMaxWidth(), label = { Text("Существующий ключ восстановления") },
            visualTransformation = PasswordVisualTransformation(), maxLines = 4) }
        item { FilledTonalButton(onClick = { repo.recover(recoveryInput); recoveryInput = "" }, enabled = recoveryInput.isNotBlank() && !s.busy) { Text("Восстановить ключи") } }
        item { HorizontalDivider() }
        item { Text("Фото и видео до 50 МБ. Синхронизация работает, пока приложение запущено. Доставка уведомлений при закрытом приложении пока не подключена.") }
        item { OutlinedButton(onClick = { logout = true }) { Text("Выйти из аккаунта") } }
    }
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("Выйти?") },
        text = { Text("Для старой истории после повторного входа понадобится ключ восстановления. Убедитесь, что сохранили его.") },
        confirmButton = { TextButton(onClick = { logout = false; repo.logout() }) { Text("Выйти") } },
        dismissButton = { TextButton(onClick = { logout = false }) { Text("Остаться") } })
}
