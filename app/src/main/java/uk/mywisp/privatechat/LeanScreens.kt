@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package uk.mywisp.privatechat

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun leanFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
    focusedLabelColor = MaterialTheme.colorScheme.primary, unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary)

@Composable
internal fun ConnectionCaption(status: ConnectionStatus) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val color = when (status) { ConnectionStatus.CONNECTED -> Color(0xFF36A767); ConnectionStatus.OFFLINE, ConnectionStatus.SIGNED_OUT -> MaterialTheme.colorScheme.error; else -> Color(0xFFCF9727) }
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(status.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LeanApp(repo: MessengerRepository, activity: ComponentActivity) {
    val s by repo.state.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("root") }
    var profile by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    var deleteChat by remember { mutableStateOf(false) }
    var selectionOverlay by remember { mutableStateOf(false) }
    val gear by animateFloatAsState(if (settings) 90f else 0f, spring(dampingRatio = .72f), label = "settings rotation")
    LaunchedEffect(s.userId, s.liveNotifications) {
        if (s.userId != null && s.liveNotifications) (activity as? MainActivity)?.enableLiveNotifications()
        if (s.userId == null) { settings = false; page = "root" }
    }
    fun back() { if (settings && page != "root") page = "root" else if (settings) settings = false else repo.back() }
    BackHandler(enabled = settings || s.roomId != null) { back() }
    when {
        s.loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) { LeanMark(104); Box(Modifier.align(Alignment.BottomCenter).padding(60.dp)) { LoadingIndicator() } }
        s.userId == null -> LeanAuthentication(repo, s)
        else -> Scaffold(modifier = Modifier.blur(if (selectionOverlay) 12.dp else 0.dp), containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(title = {
                    if (!settings && s.roomId != null) Row(Modifier.clickable { profile = s.peerId }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProfileAvatar(repo, s.peerId, s.roomName, s.profileRevision, 42)
                        Column { Text(s.roomName, maxLines = 1, style = MaterialTheme.typography.titleMedium); ConnectionCaption(s.connection) }
                    } else Column {
                        Text(if (settings) settingsTitles[page] ?: "Настройки" else "Lean", style = if (!settings || page == "root") MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                        if (!settings || page == "root") ConnectionCaption(s.connection)
                    }
                }, navigationIcon = { if (settings && page != "root" || !settings && s.roomId != null) IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                    actions = {
                        if (s.roomId != null && !settings) Box {
                            IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "Действия чата") }
                            DropdownMenu(more, { more = false }) {
                                DropdownMenuItem(text = { Text("Профиль собеседника") }, leadingIcon = { Icon(Icons.Default.Person, null) }, onClick = { more = false; profile = s.peerId })
                                DropdownMenuItem(text = { Text("Обновить") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = { more = false; repo.refresh() })
                                DropdownMenuItem(text = { Text("Удалить чат у себя") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { more = false; deleteChat = true })
                            }
                        }
                        IconButton(onClick = { settings = !settings; page = "root" }) { Icon(Icons.Default.Settings, "Настройки", Modifier.rotate(gear)) }
                    })
            }, bottomBar = {
                if (s.roomId == null || settings) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBarItem(selected = !settings, onClick = { settings = false; repo.back() }, icon = { Icon(if (!settings) Icons.Default.ChatBubble else Icons.Default.ChatBubbleOutline, null) }, label = { Text("Чаты") })
                    NavigationBarItem(selected = settings, onClick = { settings = true; page = "root" }, icon = { Icon(Icons.Default.Tune, null) }, label = { Text("Настройки") })
                }
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Crossfade(targetState = if (settings) "settings:$page" else if (s.roomId != null) "chat" else "rooms", label = "navigation") { destination ->
                        when {
                            destination.startsWith("settings:") -> LeanSettings(repo, s, destination.substringAfter(':')) { page = it }
                            destination == "chat" -> LeanConversation(repo, s) { selectionOverlay = it }
                            else -> LeanRooms(repo, s)
                        }
                    }
                }
            }
    }
    profile?.let { PeerProfileSheet(repo, it) { profile = null } }
    if (deleteChat) AlertDialog(onDismissRequest = { deleteChat = false }, title = { Text("Удалить чат у себя?") }, text = { Text("История будет скрыта на всех ваших устройствах. У собеседника она останется.") }, confirmButton = { TextButton(onClick = { deleteChat = false; repo.deleteCurrentChat() }) { Text("Удалить у меня") } }, dismissButton = { TextButton(onClick = { deleteChat = false }) { Text("Отмена") } })
    s.error?.let { AlertDialog(onDismissRequest = repo::dismissError, title = { Text("Не удалось выполнить действие") }, text = { Text(it) }, confirmButton = { TextButton(onClick = repo::dismissError) { Text("Понятно") } }) }
}

@Composable
internal fun LeanAuthentication(repo: MessengerRepository, s: ChatState) {
    var register by rememberSaveable { mutableStateOf(false) }
    var login by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().imePadding(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Spacer(Modifier.height(20.dp)); LeanMark(112) }
        item { Text("Lean", fontSize = 52.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface) }
        item { Text(if (register) "Место для ваших людей" else "Ближе к своим.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(16.dp)) }
        item { TextField(login, { login = it }, Modifier.fillMaxWidth(), label = { Text("Логин для входа") }, leadingIcon = { Icon(Icons.Default.PersonOutline, null) }, singleLine = true, colors = leanFieldColors(), shape = RoundedCornerShape(24.dp), supportingText = { if (register) Text("3–64 символа: a-z, цифры, _, -, .") }) }
        if (register) item { TextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Публичный @username") }, leadingIcon = { Icon(Icons.Default.AlternateEmail, null) }, singleLine = true, colors = leanFieldColors(), shape = RoundedCornerShape(24.dp), supportingText = { Text("По нему вас смогут найти") }) }
        item { TextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Пароль") }, leadingIcon = { Icon(Icons.Default.LockOutline, null) }, trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showPassword) "Скрыть пароль" else "Показать пароль") } },
            singleLine = true, visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = leanFieldColors(), shape = RoundedCornerShape(24.dp), supportingText = { if (register) Text("Минимум 12 символов, до 72 байт UTF-8") }) }
        item { Button(onClick = { repo.authenticate(BuildConfig.DEFAULT_SERVER, login, username, password, register); password = "" }, enabled = !s.busy && login.isNotBlank() && password.isNotBlank() && (!register || username.isNotBlank()), modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(24.dp)) {
            Text(if (register) "Зарегистрироваться" else "Войти", style = MaterialTheme.typography.titleMedium)
        } }
        item { TextButton(onClick = { register = !register }, enabled = !s.busy) { Text(if (register) "Уже есть аккаунт — войти" else "Создать аккаунт") } }
        if (s.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { Text("Один логин. Любое устройство.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun LeanRooms(repo: MessengerRepository, s: ChatState) {
    var search by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 108.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { TextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = { Text("Поиск чатов") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, colors = leanFieldColors(), shape = RoundedCornerShape(28.dp)) }
            if (s.rooms.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LeanMark(104)
                    Text("Начните первую переписку", style = MaterialTheme.typography.titleLarge)
                    Text("Найдите своих по @username", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(s.rooms.filter { it.name.contains(search, true) }, key = { it.id }) { room ->
                Surface(onClick = { repo.openRoom(room.id) }, shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    ListItem(headlineContent = { Text(room.name, fontWeight = FontWeight.Medium) }, supportingContent = { Text(if (room.invited) "Новое приглашение" else "Открыть переписку", style = MaterialTheme.typography.bodyMedium) }, leadingContent = { ProfileAvatar(repo, room.peerId, room.name, s.profileRevision, 58) }, trailingContent = { if (room.invited) Icon(Icons.Default.MarkChatUnread, null, tint = MaterialTheme.colorScheme.primary) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
            if (s.rooms.isNotEmpty() && s.rooms.none { it.name.contains(search, true) }) item { Text("Ничего не найдено", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Edit, null) }, text = { Text("Написать") }, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp), shape = RoundedCornerShape(24.dp))
    }
    if (adding) ModalBottomSheet(onDismissRequest = { adding = false }) {
        Column(Modifier.fillMaxWidth().imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Новая переписка", style = MaterialTheme.typography.headlineMedium)
            TextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("@username") }, leadingIcon = { Icon(Icons.Default.AlternateEmail, null) }, singleLine = true, colors = leanFieldColors(), shape = RoundedCornerShape(24.dp))
            Button(onClick = { repo.findAndChat(username); adding = false }, enabled = username.isNotBlank() && !s.busy, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Написать") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
