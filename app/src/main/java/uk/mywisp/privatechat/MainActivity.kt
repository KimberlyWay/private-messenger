@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as ChatApplication).repository
        setContent {
            val appearance by repo.appearance.state.collectAsStateWithLifecycle()
            PrivateTheme(appearance) { Messenger(repo, this) }
        }
    }
}

@Composable
private fun Messenger(repo: MessengerRepository, activity: ComponentActivity) {
    val s by repo.state.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    var oldChats by remember { mutableStateOf(false) }
    BackHandler(enabled = settings || s.roomId != null) { if (settings) settings = false else repo.back() }
    val keyVisible = s.recoveryKey != null
    DisposableEffect(keyVisible) {
        if (keyVisible) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (keyVisible) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    if (s.loading) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) { LoadingIndicator() }
    } else if (s.userId == null) {
        AuthenticationScreen(repo, s)
    } else {
        Scaffold(topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface), title = { Column {
                Text(if (settings) "Профиль" else if (s.roomId != null) s.roomName else "Чаты", maxLines = 1)
                if (s.roomId != null && !settings) Text("Сквозное шифрование", style = MaterialTheme.typography.labelSmall)
            } }, navigationIcon = {
                if (settings || s.roomId != null) IconButton(onClick = { if (settings) settings = false else repo.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                }
            }, actions = {
                if (s.roomId != null && !settings && s.alternateRoomIds.isNotEmpty()) Box {
                    IconButton(onClick = { oldChats = true }) { Icon(Icons.Default.History, "Прежние переписки") }
                    DropdownMenu(expanded = oldChats, onDismissRequest = { oldChats = false }) {
                        s.alternateRoomIds.forEachIndexed { index, id ->
                            DropdownMenuItem(text = { Text("Прежняя переписка ${index + 1}") },
                                onClick = { oldChats = false; repo.openHistoricalRoom(id) })
                        }
                    }
                }
                if (s.roomId != null && !settings) IconButton(onClick = repo::requestVerification, enabled = !s.busy) {
                    Icon(Icons.Default.VerifiedUser, "Проверить собеседника")
                }
                IconButton(onClick = repo::refresh, enabled = !s.busy) { Icon(Icons.Default.Refresh, "Обновить") }
                if (!settings) IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Настройки") }
            })
        }, bottomBar = {
            if (s.roomId == null || settings) NavigationBar {
                NavigationBarItem(selected = !settings, onClick = { settings = false; repo.back() },
                    icon = { Icon(Icons.Default.ChatBubbleOutline, null) }, label = { Text("Чаты") })
                NavigationBarItem(selected = settings, onClick = { settings = true },
                    icon = { Icon(Icons.Default.Person, null) }, label = { Text("Профиль") })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!settings && (!s.cryptoReady || !s.verified)) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(if (!s.cryptoReady) "Шифрование настраивается…" else "Подтвердите это устройство, чтобы отправлять сообщения")
                        TextButton(onClick = { settings = true }) { Text("Открыть безопасность") }
                    }
                }
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
    var login by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    // Passwords deliberately do not use rememberSaveable.
    var password by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Spacer(Modifier.height(32.dp)); Icon(Icons.Default.Forum, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary) }
        item { Text(if (register) "Создать аккаунт" else "Личный чат", style = MaterialTheme.typography.headlineLarge) }
        item { Text("Общайтесь с теми, кто вам близок", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(login, { login = it }, Modifier.fillMaxWidth(), label = { Text("Логин для входа") }, singleLine = true,
            supportingText = { if (register) Text("Приватный логин: 3–64 символа a-z, 0-9, _, -, .") }) }
        if (register) item { OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Публичный @username") },
            singleLine = true, supportingText = { Text("Для поиска вас: 3–32 символа a-z, 0-9, _") }) }
        item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { if (register) Text("От 12 символов, до 72 байт UTF-8") }) }
        item { Button(onClick = {
            repo.authenticate(BuildConfig.DEFAULT_SERVER, login, username, password, register)
            password = ""
        }, enabled = !s.busy && login.isNotBlank() && password.isNotBlank() && (!register || username.isNotBlank()),
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
    var search by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        if (s.rooms.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp)); Text("Начните первую переписку", style = MaterialTheme.typography.titleLarge)
            Text("Найдите человека по @username", Modifier.padding(top = 8.dp))
        }
        LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = { Text("Поиск чатов") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp))
            }
            items(s.rooms.filter { it.name.contains(search, ignoreCase = true) }, key = { it.id }) { room ->
                Card(onClick = { repo.openRoom(room.id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    ListItem(headlineContent = { Text(room.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(if (room.invited) "Приглашение — нажмите, чтобы принять" else "Личная переписка") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { ProfileAvatar(repo, room.peerId, room.name, s.profileRevision, 56) },
                        trailingContent = { Icon(if (room.invited) Icons.Default.MarkChatUnread else Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) })
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
private fun ProfileAvatar(repo: MessengerRepository, user: String?, name: String, revision: Int, size: Int) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, user, revision) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = user?.let { repo.profilePhoto(it) } ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    Surface(Modifier.size(size.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image != null) Image(image.asImageBitmap(), "Аватар", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(name.trim().take(1).uppercase().ifBlank { "?" },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = if (size > 64) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge)
        }
    }
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
    val appearance = LocalAppearance.current
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(chatBackground()), state = list,
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(if (appearance.compact) 4.dp else 8.dp)) {
            item { TextButton(onClick = repo::older, modifier = Modifier.fillMaxWidth(), enabled = !s.busy) { Text("Загрузить предыдущие") } }
            items(s.messages, key = { it.id }) { message -> MessageBubble(repo, message) }
        }
        s.sendError?.let { error ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(error)
                    if (s.canRetrySend) TextButton(onClick = repo::retrySending, enabled = !s.busy) { Text("Повторить отправку") }
                }
            }
        }
        if (s.historical) FilledTonalButton(onClick = repo::openMainConversation, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Открыть основной чат")
        } else Surface(tonalElevation = 3.dp) {
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
                        IconButton(onClick = { val original = text; repo.sendText(original) { if (text == original) text = "" } }, enabled = text.isNotBlank() && !s.busy && s.cryptoReady && s.verified) {
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
    val appearance = LocalAppearance.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.own) Arrangement.End else Arrangement.Start) {
        Surface(shape = RoundedCornerShape(appearance.bubbleRadius.dp), color = if (message.own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 310.dp)) {
            Column(Modifier.padding(if (appearance.compact) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (attachment == null) androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(message.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = appearance.textSize.sp))
                }
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
                message.trustWarning?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
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

@Composable
private fun SettingsScreen(repo: MessengerRepository, s: ChatState) {
    val appearance by repo.appearance.state.collectAsStateWithLifecycle()
    var name by remember(s.userId, s.displayName) { mutableStateOf(s.displayName) }
    var security by rememberSaveable { mutableStateOf(!s.verified) }
    var recoveryInput by remember { mutableStateOf("") }
    var logout by remember { mutableStateOf(false) }
    var avatarUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> avatarUri = uri }
    val username = s.userId.orEmpty().substringBefore(':')
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfileAvatar(repo, s.userId, s.displayName.ifBlank { username.removePrefix("@") }, s.profileRevision, 104)
                    Text(s.displayName.ifBlank { "Мой профиль" }, style = MaterialTheme.typography.headlineSmall)
                    Text(username, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !s.busy) {
                        Icon(Icons.Default.AddAPhoto, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text("Изменить фото")
                    }
                    if (s.avatarUrl != null) TextButton(onClick = repo::removeAvatar, enabled = !s.busy) { Text("Удалить фото") }
                    Text("Фото и имя видны другим пользователям", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { OutlinedTextField(name, { if (it.length <= 64) name = it }, Modifier.fillMaxWidth(),
            label = { Text("Отображаемое имя") }, singleLine = true, shape = RoundedCornerShape(16.dp)) }
        item { Button(onClick = { repo.saveProfile(name) }, enabled = !s.busy && name.isNotBlank() && name.trim() != s.displayName,
            modifier = Modifier.fillMaxWidth()) { Text("Сохранить имя") } }
        appearanceItems(repo.appearance, appearance)
        item { HorizontalDivider() }
        item { FilledTonalButton(onClick = { security = !security }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.VerifiedUser, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(if (security) "Скрыть настройки безопасности" else "Безопасность и восстановление")
        } }
        if (security) {
        item { Text(if (s.verified) "Это устройство подтверждено" else "Устройство требует подтверждения или восстановления ключей") }
        if (!s.verified) item { FilledTonalButton(onClick = repo::requestOwnVerification, enabled = !s.busy && s.cryptoReady) {
            Text("Подтвердить с другого устройства")
        } }
        item { Text("Передавайте ключ восстановления только своим устройствам. Чтобы проверить собеседника, откройте чат и нажмите значок щита.") }
        item { Text("Восстановление: " + when (s.recovery) {
            "ENABLED" -> "настроено"; "DISABLED" -> "не настроено"; "INCOMPLETE" -> "требуется существующий ключ"; else -> "проверяем…"
        }) }
        if (s.recovery == "DISABLED" && s.verified) item { Button(onClick = repo::createRecovery, enabled = !s.busy) { Text("Создать ключ восстановления") } }
        item { OutlinedTextField(recoveryInput, { recoveryInput = it }, Modifier.fillMaxWidth(), label = { Text("Существующий ключ восстановления") },
            visualTransformation = PasswordVisualTransformation(), maxLines = 4) }
        item { FilledTonalButton(onClick = { repo.recover(recoveryInput); recoveryInput = "" }, enabled = recoveryInput.isNotBlank() && !s.busy) { Text("Восстановить ключи") } }
        }
        item { HorizontalDivider() }
        item { Text("Фото и видео до 50 МБ. Синхронизация работает, пока приложение запущено. Доставка уведомлений при закрытом приложении пока не подключена.") }
        item { OutlinedButton(onClick = { logout = true }) { Text("Выйти из аккаунта") } }
    }
    avatarUri?.let { uri -> AvatarEditor(uri, { avatarUri = null }, repo::setAvatarJpeg) }
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("Выйти?") },
        text = { Text("Для старой истории после повторного входа понадобится ключ восстановления. Убедитесь, что сохранили его.") },
        confirmButton = { TextButton(onClick = { logout = false; repo.logout() }) { Text("Выйти") } },
        dismissButton = { TextButton(onClick = { logout = false }) { Text("Остаться") } })
}
