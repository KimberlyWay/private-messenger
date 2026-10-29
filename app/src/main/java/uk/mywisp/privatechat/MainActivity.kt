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
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var deleteChat by remember { mutableStateOf(false) }
    BackHandler(enabled = diagnostics || settings || s.roomId != null) { if (diagnostics) diagnostics = false else if (settings) settings = false else repo.back() }
    if (s.loading) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) { LoadingIndicator() }
    } else if (s.userId == null) {
        AuthenticationScreen(repo, s)
    } else {
        Scaffold(topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface), title = { Column {
                Text(if (settings) "Профиль" else if (s.roomId != null) s.roomName else "Чаты", maxLines = 1)
                if (s.roomId != null && !settings) Text("Облачный чат · HTTPS", style = MaterialTheme.typography.labelSmall)
            } }, navigationIcon = {
                if (settings || s.roomId != null) IconButton(onClick = { if (diagnostics) diagnostics = false else if (settings) settings = false else repo.back() }) {
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
                IconButton(onClick = repo::refresh, enabled = !s.busy) { Icon(Icons.Default.Refresh, "Обновить") }
                if (!settings) IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Настройки") }
            })
        }, bottomBar = {
            if (s.roomId == null || settings) NavigationBar {
                NavigationBarItem(selected = !settings, onClick = { diagnostics = false; settings = false; repo.back() },
                    icon = { Icon(Icons.Default.ChatBubbleOutline, null) }, label = { Text("Чаты") })
                NavigationBarItem(selected = settings, onClick = { diagnostics = false; settings = true },
                    icon = { Icon(Icons.Default.Person, null) }, label = { Text("Профиль") })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    diagnostics -> DiagnosticsScreen(repo.diagnostics) { diagnostics = false }
                    settings -> SettingsScreen(repo, s) { diagnostics = true }
                    s.roomId != null -> ConversationScreen(repo, s)
                    else -> RoomsScreen(repo, s)
                }
            }
        }
    }
    if (deleteChat) AlertDialog(onDismissRequest = { deleteChat = false }, title = { Text("Удалить чат у себя?") },
        text = { Text("Переписка исчезнет из вашего списка, а прежние сообщения будут скрыты для вашего аккаунта на всех устройствах. У собеседника история останется. Новое сообщение снова покажет чат.") },
        confirmButton = { TextButton(onClick = { deleteChat = false; repo.deleteCurrentChat() }) { Text("Удалить у меня") } },
        dismissButton = { TextButton(onClick = { deleteChat = false }) { Text("Отмена") } })
    s.error?.let { error -> AlertDialog(onDismissRequest = repo::dismissError, title = { Text("Не удалось выполнить действие") },
        text = { Text(error) }, confirmButton = { TextButton(onClick = repo::dismissError) { Text("Понятно") } }) }
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
                            Icon(if (attachment.kind == "voice") Icons.Default.PlayArrow else if (circle) Icons.Default.PlayCircle else Icons.Default.…8246 tokens truncated…        mutable.update { it.copy(sendError = message, canRetrySend = update.isRecoverable,
                            messages = it.messages.map { m -> if (m.id == update.transactionId) m.copy(status = "Не отправлено") else m }) }
                    }
                    is RoomSendQueueUpdate.RetryEvent -> {
                        queueFailures.remove(update.transactionId)
                        mutable.update { it.copy(sendError = null, canRetrySend = false) }
                    }
                    is RoomSendQueueUpdate.SentEvent -> {
                        diagnostics.record("INFO", "MESSAGE_SENT")
                        queueFailures.remove(update.transactionId)
                        mutable.update { it.copy(sendError = null, canRetrySend = false) }
                    }
                    else -> Unit
                }
            }
        })
        listener = currentTimeline.addListener(object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                if (timelineGeneration != generation) return
                synchronized(itemLock) {
                    if (timelineGeneration != generation) return
                    applyDiff(diff)
                    mutable.update { it.copy(messages = items.mapNotNull(::message)) }
                }
            }
        })
        currentTimeline.fetchMembers()
        currentTimeline.paginateBackwards(30u)
    }

    private fun applyDiff(diffs: List<TimelineDiff>) {
        for (diff in diffs) when (diff) {
            is TimelineDiff.Append -> items.addAll(diff.values)
            TimelineDiff.Clear -> items.clear()
            is TimelineDiff.PushFront -> items.add(0, diff.value)
            is TimelineDiff.PushBack -> items.add(diff.value)
            TimelineDiff.PopFront -> if (items.isNotEmpty()) items.removeAt(0) else Unit
            TimelineDiff.PopBack -> if (items.isNotEmpty()) items.removeAt(items.lastIndex) else Unit
            is TimelineDiff.Insert -> items.add(diff.index.toInt(), diff.value)
            is TimelineDiff.Set -> items[diff.index.toInt()] = diff.value
            is TimelineDiff.Remove -> items.removeAt(diff.index.toInt())
            is TimelineDiff.Truncate -> while (items.size > diff.length.toInt()) items.removeAt(items.lastIndex)
            is TimelineDiff.Reset -> { items.clear(); items.addAll(diff.values) }
        }
    }

    private fun message(item: TimelineItem): ChatMessage? {
        val event = item.asEvent() ?: return null
        val activeRoom = state.value.roomId
        val cutoff = if (activeRoom != null) visibility[activeRoom]?.cutoff ?: -1 else -1
        if (event.timestamp.toLong() <= cutoff) return null
        val content = (event.content as? TimelineItemContent.MsgLike)?.content?.kind ?: return null
        val id = when (val value = event.eventOrTransactionId) {
            is EventOrTransactionId.EventId -> value.eventId
            is EventOrTransactionId.TransactionId -> value.transactionId
        }
        val status = if (event.isRemote) "" else if (queueFailures.containsKey(id) || event.localSendState?.javaClass?.simpleName?.contains("Fail") == true)
            "Не отправлено" else "Отправляется…"
        if (content is MsgLikeKind.UnableToDecrypt) return ChatMessage(id, event.sender, event.isOwn, event.timestamp.toLong(),
            "Сообщение из прежнего зашифрованного чата недоступно")
        val msg = (content as? MsgLikeKind.Message)?.content ?: return null
        val attachment = when (val type = msg.msgType) {
            is MessageType.Image -> Attachment(type.content.source, type.content.filename, type.content.info?.mimetype ?: "image/jpeg", "image")
            is MessageType.Video -> Attachment(type.content.source, type.content.filename, type.content.info?.mimetype ?: "video/mp4",
                if (type.content.filename.startsWith("circle_")) "circle" else "video")
            is MessageType.Audio -> Attachment(type.content.source, type.content.filename, type.content.info?.mimetype ?: "audio/mp4", "voice")
            else -> null
        }
        return ChatMessage(id, event.sender, event.isOwn, event.timestamp.toLong(), msg.body, attachment, status)
    }

    fun back() { closeActiveRoom(); mutable.update { it.copy(roomId = null, peerId = null, messages = emptyList()) } }
    private fun closeActiveRoom() {
        timelineGeneration = ""
        queueListener?.cancel(); queueListener = null
        listener?.cancel(); listener = null
        timeline?.close(); timeline = null
        synchronized(itemLock) { items.clear() }
    }

    private suspend fun sendingRoom(): Room {
        require(!state.value.historical) { "Откройте основной чат, чтобы отправить сообщение" }
        val room = client?.getRoom(requireNotNull(state.value.roomId)) ?: error("Выберите чат")
        require(!room.isEncrypted()) { "Откройте облачный чат через поиск собеседника" }
        room.enableSendQueue(true)
        return room
    }

    fun sendText(text: String, queued: () -> Unit = {}) = action("sendText") {
        require(text.isNotBlank() && text.length <= 16_000) { "Сообщение: от 1 до 16 000 символов" }
        sendingRoom()
        requireNotNull(timeline).send(messageEventContentNew(MessageType.Text(TextMessageContent(text, null))))
        withContext(Dispatchers.Main) { queued() }
    }

    fun sendPicked(uri: Uri) = action("sendPicked") {
        sendingRoom()
        val mime = context.contentResolver.getType(uri).orEmpty()
        require(mime.startsWith("image/") || mime.startsWith("video/")) { "Выберите фото или видео" }
        val extension = if (mime.startsWith("image/")) "jpg" else "mp4"
        val directory = File(context.cacheDir, "uploads").apply { mkdirs() }
        val file = File(directory, "media_${UUID.randomUUID()}.$extension")
        try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    total += count; require(total <= MAX_MEDIA) { "Размер файла не должен превышать 50 МБ" }
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("Файл недоступен")
        } catch (e: Exception) { file.delete(); throw e }
        sendFileInternal(file, if (mime.startsWith("image/")) "image" else "video", mime, 0)
    }

    fun sendRecorded(file: File, kind: String, duration: Long) = action("sendRecorded") {
        sendFileInternal(file, kind, if (kind == "voice") "audio/mp4" else "video/mp4", duration)
    }

    private suspend fun sendFileInternal(file: File, kind: String, mime: String, millis: Long) {
        sendingRoom()
        require(file.length() in 1..MAX_MEDIA) { "Файл пуст или больше 50 МБ" }
        val params = UploadParameters(UploadSource.File(file.path), null, null, null, null,
            if (kind == "circle") "{\"uk.mywisp.circle\":true}" else null)
        val info = when (kind) {
            "image" -> AttachmentKind.Image(ImageInfo(null, null, mime, file.length().toULong(), null, null, null, false), null)
            "voice" -> AttachmentKind.Voice(AudioInfo(Duration.ofMillis(millis), file.length().toULong(), mime), emptyList())
            else -> AttachmentKind.Video(VideoInfo(Duration.ofMillis(millis).takeIf { millis > 0 }, null, null, mime,
                file.length().toULong(), null, null, null), null)
        }
        requireNotNull(timeline).sendAttachment(params, info).join()
    }

    suspend fun download(attachment: Attachment): File = withContext(Dispatchers.IO) {
        val c = requireNotNull(client)
        val directory = File(context.cacheDir, "opened-media").apply { mkdirs() }
        val handle = c.getMediaFile(attachment.source, attachment.name, attachment.mime, true, directory.path)
        try {
            val target = File(directory, UUID.randomUUID().toString() + "." + if (attachment.kind == "image") "img" else "media")
            check(handle.persist(target.path)) { "Не удалось сохранить вложение" }
            target
        } finally { handle.close() }
    }

    fun older() = action("older") { timeline?.paginateBackwards(30u) }

    fun retrySending() = action("retrySending") {
        val room = sendingRoom()
        require(state.value.canRetrySend) { "Сначала устраните причину остановки отправки" }
        room.enableSendQueue(true)
    }

    private fun refreshProfile() {
        val data = sessionData ?: return
        val current = client ?: return
        val profile = ProfileApi.get(state.value.server, data.getString("access_token"), current.userId())
        if (client !== current) return
        profiles[current.userId()] = System.currentTimeMillis() to profile
        mutable.update { it.copy(displayName = profile.name, avatarUrl = profile.avatar, profileRevision = it.profileRevision + 1) }
    }

    fun saveProfile(name: String) = action("saveProfile") {
        val clean = name.trim()
        require(clean.isNotBlank() && clean.length <= 64) { "Имя: от 1 до 64 символов" }
        val data = requireNotNull(sessionData)
        ProfileApi.name(state.value.server, data.getString("access_token"), requireNotNull(client).userId(), clean)
        refreshProfile()
    }

    fun setAvatar(uri: Uri) = action("setAvatar") { uploadAvatar(avatarJpeg(context, uri)) }

    fun setAvatarJpeg(jpeg: ByteArray) = action("setAvatarJpeg") {
        require(jpeg.size <= 2 * 1024 * 1024) { "Фото слишком большое" }
        uploadAvatar(jpeg)
    }

    private fun uploadAvatar(jpeg: ByteArray) {
        val data = requireNotNull(sessionData)
        val token = data.getString("access_token")
        val mxc = ProfileApi.upload(state.value.server, token, jpeg)
        ProfileApi.avatar(state.value.server, token, requireNotNull(client).userId(), mxc)
        refreshProfile()
    }

    fun removeAvatar() = action("removeAvatar") {
        val data = requireNotNull(sessionData)
        ProfileApi.avatar(state.value.server, data.getString("access_token"), requireNotNull(client).userId(), null)
        refreshProfile()
    }

    suspend fun profilePhoto(user: String): File? = withContext(Dispatchers.IO) {
        val data = sessionData ?: return@withContext null
        val current = client ?: return@withContext null
        val base = data.getString("homeserver")
        val token = data.getString("access_token")
        val cached = profiles[user]
        val profile = if (cached != null && System.currentTimeMillis() - cached.first < 60_000) cached.second else {
            ProfileApi.get(base, token, user).also {
                if (profiles.size > 200) profiles.clear()
                profiles[user] = System.currentTimeMillis() to it
            }
        }
        val mxc = profile.avatar ?: return@withContext null
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest((base + mxc).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val directory = File(context.cacheDir, "profile-photos").apply { mkdirs() }
        val file = File(directory, hash + ".img")
        if (!file.isFile) {
            val bytes = ProfileApi.download(base, token, mxc)
            check(client === current) { "Сеанс завершён" }
            val temp = File.createTempFile("avatar-", ".tmp", directory)
            try { temp.writeBytes(bytes); check(temp.renameTo(file) || file.isFile) }
            finally { temp.delete() }
        }
        file
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun refresh() = action("refresh") {
        timeline?.fetchMembers()
        refreshRooms(); profiles.clear(); runCatching { refreshProfile() }
    }
    fun logout() = action("logout") {
        val c = client ?: return@action
        visibilityJob?.cancel(); visibilityJob = null
        c.logout()
        closeActiveRoom(); sync?.cancel(); sync = null
        c.close(); client = null; vault.clear(); sessionData = null; profiles.clear(); canonical.clear(); knownPeers.clear(); queueFailures.clear(); allRooms = emptyList()
        File(context.cacheDir, "profile-photos").deleteRecursively()
        mutable.value = ChatState(loading = false)
    }

    init {
        scope.launch {
            try {
                vault.load()?.let { connect(it) }
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Не удалось восстановить сеанс. Войдите снова — история загрузится с сервера.") }
            } finally { mutable.update { it.copy(loading = false) } }
        }
    }

    companion object { const val MAX_MEDIA = 50L * 1024 * 1024 }
}
