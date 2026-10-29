@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package uk.mywisp.privatechat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.*

@Composable
internal fun LeanConversation(repo: MessengerRepository, s: ChatState, tool: String = "", toolVersion: Int = 0, overlay: (Boolean) -> Unit) {
    val context = LocalContext.current
    val locale = Locale.forLanguageTag(androidx.compose.ui.text.intl.Locale.current.toLanguageTag())
    val gestureThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var text by remember(s.roomId) { mutableStateOf("") }
    var reply by remember(s.roomId) { mutableStateOf<ChatMessage?>(null) }
    var search by remember(s.roomId) { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var starredOnly by remember { mutableStateOf(false) }
    LaunchedEffect(tool, toolVersion) {
        when (tool) { "search" -> { searching = !searching; search = "" }; "stars" -> starredOnly = !starredOnly }
    }
    var attachments by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var amplitude by remember { mutableFloatStateOf(0f) }
    var selection by remember(s.roomId) { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var forwarding by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val chosen = s.messages.filter { it.id in selection }
    val room = s.roomId
    val voice = remember { VoiceRecorder(context) }
    fun clearSelection() { selection = emptySet(); menu = false; choosing = false }
    fun finishVoice() {
        val result = voice.stop(); recording = false; locked = false
        if (result != null) repo.sendRecorded(result.first, "voice", result.second)
        else Toast.makeText(context, "Запись слишком короткая", Toast.LENGTH_SHORT).show()
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) runCatching { voice.start { finishVoice() }; recording = true; locked = false; seconds = 0 }.onFailure { Toast.makeText(context, "Не удалось включить микрофон", Toast.LENGTH_SHORT).show() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(repo::sendPicked) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.getStringExtra("file")?.let { repo.sendRecorded(File(it), "circle", result.data?.getLongExtra("duration", 0) ?: 0) }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted -> if (granted.values.all { it }) camera.launch(Intent(context, CircleCaptureActivity::class.java)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { voice.cancel(); recording = false } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); voice.cancel(); overlay(false) }
    }
    LaunchedEffect(menu, deleting, forwarding, info) { overlay(menu || deleting || forwarding || info) }
    LaunchedEffect(recording) {
        var elapsed = 0
        while (recording) { delay(80); elapsed += 80; seconds = elapsed / 1000; amplitude = voice.amplitude() }
    }
    LaunchedEffect(s.messages.map { it.id }) {
        selection = selection.intersect(s.messages.map { it.id }.toSet())
        if (selection.isEmpty()) { menu = false; choosing = false }
    }
    BackHandler(selection.isNotEmpty()) { clearSelection() }
    var firstUnread by remember(s.roomId) { mutableStateOf<String?>(null) }
    LaunchedEffect(s.unreadBeforeId) { if (firstUnread == null) firstUnread = s.unreadBeforeId }
    val list = rememberLazyListState()
    val showJump by remember(list) { derivedStateOf {
        (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) < list.layoutInfo.totalItemsCount - 4
    } }
    var previousLast by remember(s.roomId) { mutableStateOf<String?>(null) }
    LaunchedEffect(s.messages.lastOrNull()?.id) {
        val latest = s.messages.lastOrNull()
        if (latest != null && latest.id != previousLast && ((list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= s.messages.size - 4 || latest.own || previousLast == null)) list.animateScrollToItem(s.messages.size)
        previousLast = latest?.id
    }
    val visibleMessages = s.messages.filter { (!starredOnly || it.starred) && (search.isBlank() || it.text.contains(search, true)) }
    LaunchedEffect(list, s.messages.lastOrNull()?.id, search, starredOnly, s.inForeground) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
            if (!starredOnly && search.isBlank() && last >= s.messages.size && s.messages.isNotEmpty()) repo.markVisibleRead()
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (choosing) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { clearSelection() }) { Icon(Icons.Default.Close, "Отменить выбор") }
                Text("Выбрано: ${selection.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { forwarding = true }, enabled = chosen.isNotEmpty()) { Icon(Icons.Default.Forward, "Переслать выбранное") }
                IconButton(onClick = { deleting = true }, enabled = chosen.isNotEmpty()) { Icon(Icons.Default.DeleteOutline, "Удалить выбранное") }
            }
        } else if (searching || starredOnly) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (searching) TextField(search, { search = it }, Modifier.weight(1f), placeholder = { Text("Поиск в переписке") }, singleLine = true, colors = leanFieldColors(), shape = RoundedCornerShape(24.dp))
            if (starredOnly) FilterChip(true, { starredOnly = false }, label = { Text("Помеченные") }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
            if (searching) IconButton(onClick = { searching = false; search = "" }) { Icon(Icons.Default.Close, "Закрыть поиск") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(chatBackground())) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(if (LocalAppearance.current.compact) 4.dp else 8.dp)) {
                item { TextButton(onClick = repo::older, modifier = Modifier.fillMaxWidth(), enabled = !s.busy) { Text("Загрузить предыдущие") } }
                itemsIndexed(visibleMessages, key = { _, m -> m.id }) { index, m ->
                    if (index == 0 || day(m.time) != day(visibleMessages[index - 1].time)) Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Text(dayLabel(m.time), Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium) }
                    }
                    if (m.id == firstUnread) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f)); Text("Не прочитано", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); HorizontalDivider(Modifier.weight(1f))
                    }
                    Row(Modifier.animateItem(), verticalAlignment = Alignment.CenterVertically) {
                        if (choosing) Checkbox(m.id in selection, { if (selection.size < 100 || m.id in selection) selection = if (m.id in selection) selection - m.id else selection + m.id })
                        Box(Modifier.weight(1f)) {
                            MessageBubble(repo, m, selected = m.id in selection,
                                onLongPress = { selection = setOf(m.id); menu = true },
                                onClick = { if (choosing && (selection.size < 100 || m.id in selection)) selection = if (m.id in selection) selection - m.id else selection + m.id },
                                quote = m.replyToId?.let { id -> s.messages.firstOrNull { it.id == id }?.text ?: "Сообщение" })
                        }
                    }
                }
                if (visibleMessages.isEmpty()) item { Text(if (search.isNotBlank()) "Ничего не найдено" else if (starredOnly) "Здесь появятся помеченные сообщения" else "Скажите привет 👋", Modifier.fillMaxWidth().padding(32.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (!searching && !starredOnly && showJump) SmallFloatingActionButton(onClick = { scope.launch { list.animateScrollToItem(s.messages.size) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp), containerColor = MaterialTheme.colorScheme.secondaryContainer) { Icon(Icons.Default.ArrowDownward, "К новым сообщениям") }
        }
        SnackbarHost(snackbar)
        s.sendError?.let { error -> Surface(color = MaterialTheme.colorScheme.errorContainer) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); if (s.canRetrySend) TextButton(onClick = repo::retrySending) { Text("Повторить") } } } }
        if (s.historical) Button(onClick = repo::openMainConversation, Modifier.fillMaxWidth().padding(12.dp)) { Text("Открыть основной чат") }
        else {
            reply?.let { original ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Reply, null, tint = MaterialTheme.colorScheme.primary); Column(Modifier.weight(1f).padding(12.dp)) { Text("В ответ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(original.text, maxLines = 2, style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = { reply = null }) { Icon(Icons.Default.Close, "Отменить ответ") } }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    if (recording) {
                        var drag by remember { mutableStateOf(Offset.Zero) }
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).pointerInput(locked) {
                            detectDragGestures(onDrag = { change, delta -> change.consume(); drag += delta; if (drag.y < -gestureThreshold) locked = true; if (drag.x < -gestureThreshold * 1.3f && !locked) { voice.cancel(); recording = false } })
                        }, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { voice.cancel(); recording = false }) { Icon(Icons.Default.DeleteOutline, "Отменить запись", tint = MaterialTheme.colorScheme.error) }
                            Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelLarge)
                            Waveform(amplitude, Modifier.weight(1f).height(36.dp).padding(horizontal = 8.dp))
                            IconButton(onClick = { locked = !locked }) { Icon(if (locked) Icons.Default.Lock else Icons.Default.LockOpen, "Закрепить запись") }
                        }
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { attachments = true }, enabled = !s.busy) { Icon(Icons.Default.AddCircleOutline, "Вложения") }
                        TextField(text, { if (it.length <= 16_000) text = it }, Modifier.weight(1f), placeholder = { Text("Сообщение") }, maxLines = 5, colors = leanFieldColors(), shape = RoundedCornerShape(28.dp))
                        IconButton(onClick = { picker.launch(arrayOf("image/*", "video/*")) }, enabled = !s.busy) { Icon(Icons.Default.Image, "Фото или видео") }
                    }
                }
                val hasText = text.isNotBlank()
                val radius by animateDpAsState(if (hasText || recording) 20.dp else 28.dp, label = "send shape")
                FilledIconButton(onClick = {
                    if (recording) finishVoice()
                    else if (hasText) { val original = text; repo.sendText(original, reply) { if (text == original) { text = ""; reply = null } } }
                    else microphone.launch(Manifest.permission.RECORD_AUDIO)
                }, enabled = !s.busy, modifier = Modifier.size(56.dp), shape = RoundedCornerShape(radius)) {
                    Crossfade(hasText || recording, label = "send icon") { send -> Icon(if (send) Icons.AutoMirrored.Filled.Send else Icons.Default.GraphicEq, if (send) "Отправить" else "Голосовое сообщение", Modifier.size(24.dp)) }
                }
            }
        }
    }
    if (attachments) ModalBottomSheet(onDismissRequest = { attachments = false }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Поделиться", style = MaterialTheme.typography.headlineMedium)
            SettingsRow(Icons.Default.PhotoLibrary, "Фото и видео", "Из галереи устройства") { attachments = false; picker.launch(arrayOf("image/*", "video/*")) }
            SettingsRow(Icons.Default.InsertDriveFile, "Файл", "Документ или другой файл до 50 МБ") { attachments = false; picker.launch(arrayOf("*/*")) }
            SettingsRow(Icons.Default.Videocam, "Кружочек", "Короткое видеосообщение") { attachments = false; permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (menu && chosen.isNotEmpty()) Dialog(onDismissRequest = { clearSelection() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(24.dp).heightIn(max = 680.dp).verticalScroll(rememberScrollState()), horizontalAlignment = if (chosen.first().own) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MessageBubble(repo, chosen.first(), selected = true, condensed = true)
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 16.dp, modifier = Modifier.widthIn(max = 280.dp)) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    MenuAction(Icons.Default.Reply, "Ответить", chosen.size == 1 && chosen[0].id.startsWith("$")) { reply = chosen[0]; clearSelection() }
                    MenuAction(Icons.Default.ContentCopy, "Копировать") { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Сообщение", chosen.joinToString("\n") { it.text })); clearSelection() }
                    MenuAction(Icons.Default.Forward, "Переслать") { menu = false; forwarding = true }
                    MenuAction(if (chosen.all { it.starred }) Icons.Default.Star else Icons.Default.StarOutline, if (chosen.all { it.starred }) "Убрать отметку" else "Пометить") { if (room != null) repo.messagePreference(room, chosen.map { it.id }, "starred", !chosen.all { it.starred }); clearSelection() }
                    MenuAction(Icons.Default.DeleteOutline, "Удалить", chosen.all { it.id.startsWith("$") }) { menu = false; deleting = true }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    MenuAction(Icons.Default.Checklist, "Выбрать ещё") { menu = false; choosing = true }
                    MenuAction(Icons.Default.Info, "Информация") { menu = false; info = true }
                }
            }
        }
    }
    if (deleting && chosen.isNotEmpty()) AlertDialog(onDismissRequest = { deleting = false; clearSelection() }, icon = { Icon(Icons.Default.DeleteOutline, null) }, title = { Text(if (chosen.size == 1) "Удалить сообщение?" else "Удалить сообщения (${chosen.size})?") }, text = { Text("«У меня» скроет выбранное на всех ваших устройствах. «У всех» доступно для ваших сообщений и уберёт их из переписки обоих участников.") },
        confirmButton = { Column {
            if (chosen.all { it.own }) TextButton(onClick = { if (room != null) repo.deleteForEveryone(room, chosen.map { it.id }); deleting = false; clearSelection() }) { Text("Удалить у всех", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = {
                val ids = chosen.map { it.id }; val target = room
                if (target != null) repo.messagePreference(target, ids, "hidden") {
                    scope.launch { if (snackbar.showSnackbar("Удалено у вас", "Отменить", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) repo.messagePreference(target, ids, "hidden", false) }
                }
                deleting = false; clearSelection()
            }) { Text("Удалить у меня") }
        } }, dismissButton = { TextButton(onClick = { deleting = false; clearSelection() }) { Text("Отмена") } })
    if (forwarding) ModalBottomSheet(onDismissRequest = { forwarding = false; clearSelection() }) {
        LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Кому переслать", style = MaterialTheme.typography.headlineMedium) }
            val targets = s.rooms.filter { !it.invited }
            if (targets.isEmpty()) item { Text("Сначала создайте переписку с нужным человеком") }
            itemsIndexed(targets) { _, target -> SettingsRow(Icons.Default.ChatBubbleOutline, target.name, "Переслать ${chosen.size} сообщ.") { if (room != null) repo.forwardMessages(room, chosen.map { it.id }, target.id); forwarding = false; clearSelection() } }
        }
    }
    if (info && chosen.isNotEmpty()) AlertDialog(onDismissRequest = { info = false; clearSelection() }, title = { Text("О сообщении") }, text = { val m = chosen.first(); Text("${m.sender.substringBefore(':')}\n${SimpleDateFormat("d MMMM yyyy, HH:mm:ss", locale).format(Date(m.time))}\n${m.status.ifBlank { if (m.read) "Прочитано" else "Отправлено на сервер" }}") }, confirmButton = { TextButton(onClick = { info = false; clearSelection() }) { Text("Понятно") } })
}

@Composable
private fun MenuAction(icon: ImageVector, text: String, enabled: Boolean = true, click: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = click, enabled = enabled, leadingIcon = { Icon(icon, null, Modifier.size(22.dp)) })
}

@Composable
private fun Waveform(amplitude: Float, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) { for (i in 0..17) {
        val height = size.height * (.15f + amplitude * (.3f + .7f * abs(sin(i * 1.7f))))
        val x = size.width * i / 17
        drawLine(color, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    } }
}

private fun day(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
private fun dayLabel(time: Long): String = when (day(time)) { LocalDate.now() -> "Сегодня"; LocalDate.now().minusDays(1) -> "Вчера"; else -> SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(time)) }
