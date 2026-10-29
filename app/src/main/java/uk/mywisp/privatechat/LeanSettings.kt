@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package uk.mywisp.privatechat

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal val settingsTitles = mapOf("root" to "Настройки", "profile" to "Мой профиль", "colors" to "Оформление", "layout" to "Размер и интерфейс", "notifications" to "Уведомления", "storage" to "Данные и память", "logs" to "Диагностика", "about" to "О Lean")

@Composable
internal fun SettingsRow(icon: ImageVector, title: String, subtitle: String, click: () -> Unit) {
    Surface(onClick = click, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodyMedium) },
            leadingContent = { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(icon, null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            } }, trailingContent = { Icon(Icons.Default.ChevronRight, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

@Composable
internal fun LeanSettings(repo: MessengerRepository, s: ChatState, page: String, navigate: (String) -> Unit) {
    val a by repo.appearance.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var logout by remember { mutableStateOf(false) }
    var clearCache by remember { mutableStateOf(false) }
    when (page) {
        "logs" -> DiagnosticsScreen(repo.diagnostics) { navigate("root") }
        "profile" -> ProfileEditor(repo, s)
        else -> LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                "root" -> {
                    item {
                        Surface(onClick = { navigate("profile") }, shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                ProfileAvatar(repo, s.userId, s.displayName, s.profileRevision, 72)
                                Column(Modifier.weight(1f)) {
                                    Text(s.displayName.ifBlank { "Мой профиль" }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(s.userId.orEmpty().substringBefore(':'), color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text("Фото, имя и личный статус", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                    item { Text("Сделайте Lean своим", Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    item { SettingsRow(Icons.Default.Palette, "Оформление", "Цвета Monet, тема и фон чата") { navigate("colors") } }
                    item { SettingsRow(Icons.Default.FormatSize, "Размер и интерфейс", "Масштаб, текст и плотность") { navigate("layout") } }
                    item { SettingsRow(Icons.Default.Notifications, "Уведомления", "Фоновая связь, звук и предпросмотр") { navigate("notifications") } }
                    item { SettingsRow(Icons.Default.Storage, "Данные и память", "Медиафайлы и кэш") { navigate("storage") } }
                    item { SettingsRow(Icons.Default.BugReport, "Диагностика", "Соединение и журнал ошибок") { navigate("logs") } }
                    item { SettingsRow(Icons.Default.Info, "О Lean", "Версия ${BuildConfig.VERSION_NAME}") { navigate("about") } }
                    item { TextButton(onClick = { logout = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("Выйти из аккаунта") } }
                }
                "colors", "layout" -> appearanceItems(repo.appearance, a, page)
                "notifications" -> {
                    item { PreferenceHero(Icons.Default.NotificationsActive, "Всегда на связи", "Получайте новые сообщения, даже когда Lean свёрнут.") }
                    item {
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("Живые уведомления", style = MaterialTheme.typography.titleMedium); Text("Поддерживать соединение в фоне", style = MaterialTheme.typography.bodySmall) }
                                Switch(s.liveNotifications, repo::setLiveNotifications)
                            }
                        }
                    }
                    item { Text("Android показывает постоянное уведомление о фоновой связи. После принудительной остановки приложения откройте Lean снова. Энергосбережение устройства может задерживать доставку.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { SettingsRow(Icons.Default.Tune, "Настройки Android", "Звук, вибрация, экран блокировки и разрешения") {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    } }
                }
                "storage" -> {
                    item { PreferenceHero(Icons.Default.CloudDone, "История с вами", "Сообщения и вложения хранятся на сервере и доступны после входа на другом устройстве.") }
                    item { SettingsRow(Icons.Default.CleaningServices, "Очистить кэш", "Скачанные фото и вложения загрузятся заново") { clearCache = true } }
                    item { Text("Фото и видео до 50 МБ. Удаление кэша не удаляет переписку и не затрагивает отправляемые файлы.", style = MaterialTheme.typography.bodyMedium) }
                }
                "about" -> {
                    item { Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) { LeanMark(112); Spacer(Modifier.height(16.dp)); Text("Lean", style = MaterialTheme.typography.displayMedium); Text("Ближе к своим.", color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge) } }
                    item { PreferenceHero(Icons.Default.Lock, "Защищённое соединение", "HTTPS/TLS защищает передачу. Облачная переписка хранится на сервере и доступна его владельцу.") }
                }
            }
        }
    }
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("Выйти из Lean?") }, text = { Text("История останется на сервере. Для входа понадобятся ваш логин и пароль.") },
        confirmButton = { TextButton(onClick = { logout = false; repo.logout() }) { Text("Выйти") } }, dismissButton = { TextButton(onClick = { logout = false }) { Text("Отмена") } })
    if (clearCache) AlertDialog(onDismissRequest = { clearCache = false }, title = { Text("Очистить кэш?") }, text = { Text("Скачанные файлы будут удалены с этого устройства. Переписка сохранится.") },
        confirmButton = { TextButton(onClick = { clearCache = false; repo.clearMediaCache() }) { Text("Очистить") } }, dismissButton = { TextButton(onClick = { clearCache = false }) { Text("Отмена") } })
}

@Composable
internal fun PreferenceHero(icon: ImageVector, title: String, body: String) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(28.dp)); Text(title, style = MaterialTheme.typography.titleLarge); Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun ProfileEditor(repo: MessengerRepository, s: ChatState) {
    var name by remember(s.displayName) { mutableStateOf(s.displayName) }
    var status by remember(s.personalStatus) { mutableStateOf(s.personalStatus) }
    var shape by remember(s.avatarShape) { mutableStateOf(s.avatarShape) }
    var avatarUri by remember { mutableStateOf<Uri?>(null) }
    var viewProfile by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { avatarUri = it }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.clickable { viewProfile = true }) { ProfileAvatar(repo, s.userId, s.displayName, s.profileRevision, 128, shape) }
                Text(s.userId.orEmpty().substringBefore(':'), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !s.busy) { Icon(Icons.Default.AddAPhoto, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Изменить фото") }
                if (s.avatarUrl != null) TextButton(onClick = repo::removeAvatar, enabled = !s.busy) { Text("Удалить фото") }
            }
        }
        item { TextField(name, { if (it.length <= 64) name = it }, Modifier.fillMaxWidth(), label = { Text("Отображаемое имя") }, singleLine = true, shape = RoundedCornerShape(20.dp), colors = leanFieldColors()) }
        item { Button(onClick = { repo.saveProfile(name) }, enabled = !s.busy && name.isNotBlank() && name.trim() != s.displayName, modifier = Modifier.fillMaxWidth()) { Text("Сохранить имя") } }
        item { TextField(status, { if (it.length <= 120) status = it }, Modifier.fillMaxWidth(), label = { Text("Личный статус") }, placeholder = { Text("Например, на своей волне 🌊") }, maxLines = 3, shape = RoundedCornerShape(20.dp), colors = leanFieldColors()) }
        item { Text("Форма аватара", style = MaterialTheme.typography.titleMedium); Text("Её увидят ваши собеседники", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(avatarShapeNames.entries.toList()) { (key, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(onClick = { shape = key }, shape = RoundedCornerShape(22.dp), color = if (shape == key) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Box(Modifier.padding(12.dp)) { AvatarArtwork(null, name, key, 52) }
                        }
                        Text(label, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item { Button(onClick = { repo.saveProfileDetails(status, shape) }, enabled = !s.busy && (status != s.personalStatus || shape != s.avatarShape), modifier = Modifier.fillMaxWidth()) { Text("Сохранить профиль") } }
        item { Text("Имя, фото, форма аватара и личный статус видны другим пользователям Lean.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    avatarUri?.let { AvatarEditor(it, { avatarUri = null }, repo::setAvatarJpeg, shape) }
    if (viewProfile && s.userId != null) PeerProfileSheet(repo, s.userId) { viewProfile = false }
}

@Composable
internal fun PeerProfileSheet(repo: MessengerRepository, user: String, close: () -> Unit) {
    var profile by remember(user) { mutableStateOf<UserProfile?>(null) }
    var error by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf<File?>(null) }
    var openedKind by remember { mutableStateOf("image") }
    val state by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(user, attempt) { error = false; try { profile = repo.userProfile(user, true) } catch (_: Exception) { error = true } }
    ModalBottomSheet(onDismissRequest = close) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val p = profile
                    Box(Modifier.clickable(enabled = p?.avatar != null) { scope.launchSafely({ error = true }) { openedKind = "image"; opened = repo.profilePhotoUrl(requireNotNull(p?.avatar)) } }) {
                        ProfileAvatar(repo, user, p?.name.orEmpty(), attempt + (p?.hashCode() ?: 0), 144, p?.shape)
                    }
                    Text(p?.name?.ifBlank { user.substringBefore(':') } ?: user.substringBefore(':'), style = MaterialTheme.typography.headlineMedium)
                    Text(user.substringBefore(':'), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!p?.status.isNullOrBlank()) Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Text(p!!.status, Modifier.padding(16.dp)) }
                }
            }
            if (error) item { FilledTonalButton(onClick = { attempt++ }) { Text("Не удалось загрузить. Повторить") } }
            else if (profile == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!profile?.photos.isNullOrEmpty()) {
                item { Text("Фотографии профиля", style = MaterialTheme.typography.titleMedium) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(profile!!.photos) { mxc ->
                            val bitmap by produceState<android.graphics.Bitmap?>(null, mxc) { value = withContext(Dispatchers.IO) { runCatching { decodeProfile(repo.profilePhotoUrl(mxc).path, 512) }.getOrNull() } }
                            Box(Modifier.clickable { scope.launchSafely({ error = true }) { openedKind = "image"; opened = repo.profilePhotoUrl(mxc) } }) { AvatarArtwork(bitmap, "", "squircle", 96) }
                        }
                    }
                }
            }
            if (state.peerId == user) {
                item { HorizontalDivider(); Text("Медиа в переписке", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium) }
                val media = state.messages.mapNotNull { it.attachment }
                if (media.isEmpty()) item { Text("В загруженной части истории пока нет вложений", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(media.takeLast(30)) { attachment ->
                    SettingsRow(when (attachment.kind) { "image" -> Icons.Default.Image; "voice" -> Icons.Default.Mic; "file" -> Icons.Default.InsertDriveFile; else -> Icons.Default.Videocam },
                        when (attachment.kind) { "image" -> "Фото"; "voice" -> "Голосовое сообщение"; "circle" -> "Кружочек"; "file" -> attachment.name; else -> "Видео" }, "Открыть вложение") {
                        scope.launchSafely({ error = true }) { openedKind = attachment.kind; opened = repo.download(attachment) }
                    }
                }
                item { TextButton(onClick = repo::older, enabled = !state.busy) { Text("Загрузить предыдущие") } }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
    opened?.let { MediaDialog(it, openedKind) { opened = null } }
}

private fun kotlinx.coroutines.CoroutineScope.launchSafely(onError: () -> Unit, block: suspend () -> Unit) = launch { try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { onError() } }
