package uk.mywisp.privatechat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.*
import java.io.File
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID

data class ChatRoom(val id: String, val name: String, val invited: Boolean, val peerId: String? = null, val alternateIds: List<String> = emptyList())
data class Attachment(val source: MediaSource, val name: String, val mime: String, val kind: String)
data class ChatMessage(val id: String, val sender: String, val own: Boolean, val time: Long,
    val text: String, val attachment: Attachment? = null, val status: String = "", val trustWarning: String? = null)
data class ChatState(
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null,
    val userId: String? = null, val server: String = "", val rooms: List<ChatRoom> = emptyList(),
    val roomId: String? = null, val roomName: String = "", val peerId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val displayName: String = "", val avatarUrl: String? = null, val profileRevision: Int = 0,
    val sendError: String? = null, val canRetrySend: Boolean = false,
    val alternateRoomIds: List<String> = emptyList(), val historical: Boolean = false)


class MessengerRepository(private val context: Context) {
    internal val diagnostics = DiagnosticJournal(context)
    val appearance = AppearancePreferences(context)
    private val profiles = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, UserProfile>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vault = Vault(context)
    private val pendingActions = java.util.concurrent.atomic.AtomicInteger(0)
    private val actionMutex = Mutex()
    private val roomsMutex = Mutex()
    private val directPrefs = context.getSharedPreferences("direct-chats", Context.MODE_PRIVATE)
    private val canonical = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val knownPeers = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val queueFailures = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var allRooms = emptyList<ChatRoom>()
    private var queueListener: TaskHandle? = null
    private var visibilityJob: Job? = null
    private val visibility = java.util.concurrent.ConcurrentHashMap<String, ChatVisibility>()
    private val visibilityChecks = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private var client: Client? = null
    private var sync: TaskHandle? = null
    private var timeline: Timeline? = null
    private var listener: TaskHandle? = null
    private var sessionData: JSONObject? = null
    private var timelineGeneration = ""
    private val items = mutableListOf<TimelineItem>()
    private val itemLock = Any()

    private fun action(name: String, block: suspend () -> Unit) {
        pendingActions.incrementAndGet()
        // Publish the new operation synchronously: a stale failure must not look
        // like the result of a retry before the IO coroutine gets scheduled.
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch {
            actionMutex.withLock {
                mutable.update { it.copy(busy = true, error = null) }
                diagnostics.record("INFO", "ACTION_START", name)
                try { block(); diagnostics.record("INFO", "ACTION_OK", name) }
                catch (e: CancellationException) {
                    if (e !is TimeoutCancellationException) throw e
                    diagnostics.record("ERROR", "ACTION_TIMEOUT", name, e)
                    mutable.update { it.copy(error = "Операция заняла слишком много времени. Проверьте соединение и повторите попытку.") }
                }
                catch (e: Exception) {
                    diagnostics.record("ERROR", "ACTION_FAILED", name + (if (e is GatewayHttpException) " HTTP ${e.status}" else ""), e)
                    val message = if (e is IllegalArgumentException || e is IllegalStateException) e.message
                        else "Не удалось выполнить действие. Проверьте подключение к серверу."
                    mutable.update { it.copy(error = message?.take(240)) }
                } finally {
                    val remaining = pendingActions.decrementAndGet()
                    mutable.update { it.copy(busy = remaining > 0) }
                }
            }
        }
    }

    fun authenticate(server: String, login: String, username: String, password: String, register: Boolean) = action("authenticate") {
        val base = Gateway.validateBase(server)
        val config = Gateway.call(base, "/api/config")
        require(config.optString("chat_mode") == "cloud-v1") { "Обновите сервер для облачных чатов до версии 0.5.0" }
        val cleanLogin = login.trim().lowercase(java.util.Locale.ROOT)
        val parameters = Gateway.call(base, "/api/auth/parameters", JSONObject().put("login", cleanLogin))
        require(parameters.getString("scheme") == "pm-password-v2") { "Обновите сервер мессенджера" }
        val serverName = parameters.getString("server_name")
        if (register) require(password.length >= 12 && password.toByteArray().size <= 72 && !password.contains('\u0000')) {
            "Пароль: минимум 12 символов и не более 72 байт UTF-8"
        }
        val salts = if (register) listOf(PasswordSecrets.randomSalt()) else parameters.getJSONArray("salts").let { a ->
            require(a.length() in 1..2) { "Некорректные параметры входа" }
            (0 until a.length()).map(a::getString)
        }
        var response: JSONObject? = null
        for (salt in salts) {
            PasswordSecrets(password, salt, serverName, cleanLogin).use { secrets ->
                val body = JSONObject().put("login", cleanLogin).put("password", secrets.authentication)
                    .put("credential_version", 2).put("credential_salt", salt)
                if (register) Gateway.call(base, "/api/register", body.put("username", username))
                response = try { Gateway.call(base, "/api/login", body) }
                catch (e: GatewayHttpException) { if (e.status == 401 && !register) null else throw e }
            }
            if (response != null) break
        }
        // Compatibility is automatic, behind the same login form. Never retry
        // network/rate-limit/server errors as another credential format.
        if (response == null && !register) {
            response = try { Gateway.call(base, "/api/login", JSONObject().put("login", cleanLogin).put("password", password)) }
            catch (e: GatewayHttpException) {
                if (e.status == 401) error("Неверный логин или пароль") else throw e
            }
        }
        val data = requireNotNull(response) { "Неверный логин или пароль" }
        data.put("homeserver", base).put("directory", UUID.randomUUID().toString())
            .put("db_key", Vault.encode(ByteArray(32).also { SecureRandom().nextBytes(it) }))
        connect(data)
        if (register) client?.setDisplayName(username.trim().removePrefix("@"))
        runCatching { refreshProfile() }
    }

    private suspend fun connect(data: JSONObject) {
        closeActiveRoom()
        sync?.cancel()
        client?.close()
        val base = Gateway.validateBase(data.getString("homeserver"))
        val directory = File(context.filesDir, "matrix/" + data.getString("directory")).apply { mkdirs() }
        val cache = File(context.cacheDir, "matrix/" + data.getString("directory")).apply { mkdirs() }
        val built = ClientBuilder()
            .homeserverUrl(base)
            .disableWellKnownLookup(true)
            .sqliteStore(SqliteStoreBuilder(directory.path, cache.path)
                .key(Base64.decode(data.getString("db_key"), Base64.NO_WRAP)))
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NONE)
            .autoEnableCrossSigning(false).autoEnableBackups(false)
            .userAgent("PrivateMessenger/" + BuildConfig.VERSION_NAME)
            .build()
        built.restoreSession(Session(data.getString("access_token"), null, data.getString("user_id"),
            data.getString("device_id"), base, null, SlidingSyncVersion.NONE))
        client = built
        sessionData = data
        vault.save(data)
        canonical.clear(); knownPeers.clear(); allRooms = emptyList(); visibility.clear(); visibilityChecks.clear()
        visibility.putAll(ChatVisibilityApi.parse(directPrefs.getString("visibility|" + built.userId(), null)))
        val ownPrefix = built.userId() + "|"
        directPrefs.all.forEach { (key, value) ->
            if (key.startsWith(ownPrefix) && value is String) canonical[key.removePrefix(ownPrefix)] = value
        }
        mutable.update { ChatState(loading = false, busy = it.busy, userId = built.userId(), server = base) }
        // Cloud messages do not wait for E2EE initialization or device approval.
        try { withTimeout(30_000) { built.syncOnceV2(SyncSettingsV2(timeoutMs = 0u)) } }
        catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
        }
        sync = built.syncV2(SyncSettingsV2(), object : SyncListenerV2 {
            override fun onUpdate(response: SyncResponseV2) {
                scope.launch { try { refreshRooms() } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    diagnostics.record("WARNING", "SYNC_STATE_REFRESH_FAILED", error = e)
                } }
            }
        })
        refreshRooms()
        runCatching { refreshProfile() }
    }

    private suspend fun refreshRooms() {
        if (!roomsMutex.tryLock()) return
        try {
            val current = client ?: return
            val rows = mutableListOf<ChatRoom>()
            ChatVisibilityApi.parse(current.accountData(ChatVisibilityApi.TYPE)).forEach { (id, remote) ->
                if (remote.updated > (visibility[id]?.updated ?: -1)) visibility[id] = remote
            }
            for (room in current.rooms().filter { it.membership() in listOf(Membership.JOINED, Membership.INVITED) }) {
                if (room.isEncrypted()) continue // Pre-cloud history requires old device keys.
                val invited = room.membership() == Membership.INVITED
                // Invited users cannot request the full /members state. Use stripped invite state.
                val peer = try {
                    if (invited) room.inviter()?.userId
                    else room.activeHumanMemberIds().filter { it != current.userId() }.singleOrNull()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    knownPeers[room.id()]
                }
                if (peer != null) knownPeers[room.id()] = peer
                rows += ChatRoom(room.id(), room.displayName() ?: room.rawName() ?: peer?.substringBefore(':') ?: "Личная переписка", invited, peer)
            }
            if (client !== current) return
            allRooms = rows
            var visibilityChanged = false
            for (row in rows) {
                val marker = visibility[row.id] ?: continue
                if (!marker.hidden || row.invited || System.currentTimeMillis() - (visibilityChecks[row.id] ?: 0) < 2500) continue
                visibilityChecks[row.id] = System.currentTimeMillis()
                try {
                    val latest = ChatVisibilityApi.latestTime(state.value.server, requireNotNull(sessionData).getString("access_token"), row.id)
                    if (client !== current) return
                    if (latest > marker.cutoff) {
                        visibility[row.id] = marker.copy(hidden = false, updated = maxOf(System.currentTimeMillis(), marker.updated + 1))
                        visibilityChanged = true
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    diagnostics.record("WARNING", "HIDDEN_CHAT_CHECK_FAILED", error = e)
                }
            }
            if (visibilityChanged) persistVisibility()
            mutable.update { it.copy(rooms = collapseDirectChats(rows.filter { visibility[it.id]?.hidden != true }, canonical)) }
            val active = state.value.roomId
            if (active != null) {
                if (visibility[active]?.hidden == true) back()
                else synchronized(itemLock) { mutable.update { it.copy(messages = items.mapNotNull(::message)) } }
            }
            ensureVisibilityChecks()
        } finally { roomsMutex.unlock() }
    }

    fun findAndChat(username: String) = action("findAndChat") {
        val data = requireNotNull(sessionData)
        val result = Gateway.call(state.value.server, "/api/users/" + Gateway.escaped(username.trim().removePrefix("@")),
            token = data.getString("access_token"))
        openDirect(result.getString("user_id"))
    }

    private suspend fun openDirect(peer: String) {
        val current = requireNotNull(client)
        val data = requireNotNull(sessionData)
        require(peer != current.userId()) { "Выберите другого пользователя" }
        val candidates = allRooms.filter { it.peerId == peer && !it.invited }.map { it.id }
        val result = DirectChats.resolve(state.value.server, data.getString("access_token"),
            peer.substringBefore(':').removePrefix("@"), candidates)
        require(result.getString("user_id") == peer) { "Сервер вернул другого собеседника" }
        val id = result.getString("room_id")
        canonical[peer] = id; knownPeers[id] = peer
        directPrefs.edit().putString(current.userId() + "|" + peer, id).apply()
        if (current.getRoom(id)?.membership() != Membership.JOINED) {
            Gateway.call(state.value.server, "/_matrix/client/v3/join/" + Gateway.escaped(id), JSONObject(), data.getString("access_token"))
        }
        withTimeout(30_000) { current.awaitRoomRemoteEcho(id) }
        DirectChats.markDirect(state.value.server, data.getString("access_token"), current.userId(), peer, id)
        visibility[id]?.takeIf { it.hidden }?.let { marker ->
            visibility[id] = marker.copy(hidden = false, updated = maxOf(System.currentTimeMillis(), marker.updated + 1))
            persistVisibility()
        }
        openRoomInternal(id)
        refreshRooms()
    }

    fun openRoom(id: String) = action("openRoom") {
        val peer = knownPeers[id]
        if (peer != null) openDirect(peer) else openRoomInternal(id)
    }

    private fun persistVisibility() {
        val data = sessionData ?: return
        val own = requireNotNull(client).userId()
        val json = ChatVisibilityApi.encode(visibility)
        directPrefs.edit().putString("visibility|" + own, json.toString()).apply()
        Gateway.call(state.value.server, ChatVisibilityApi.path(own), json, data.getString("access_token"), "PUT")
    }

    private fun ensureVisibilityChecks() {
        if (visibility.none { it.value.hidden } || visibilityJob?.isActive == true) return
        val current = client ?: return
        visibilityJob = scope.launch {
            while (client === current && visibility.any { it.value.hidden }) {
                delay(3000)
                try { refreshRooms() } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    diagnostics.record("WARNING", "CHAT_VISIBILITY_REFRESH_FAILED", error = e)
                }
            }
        }
    }

    fun deleteCurrentChat() = action("deleteCurrentChat") {
        val id = requireNotNull(state.value.roomId)
        val peer = state.value.peerId
        val ids = (allRooms.filter { it.peerId == peer && peer != null }.map { it.id } + id).distinct()
        val token = requireNotNull(sessionData).getString("access_token")
        val updated = visibility.toMutableMap()
        for (roomId in ids) {
            val invited = allRooms.firstOrNull { it.id == roomId }?.invited == true
            val cutoff = if (invited) 0L else ChatVisibilityApi.latestTime(state.value.server, token, roomId)
            updated[roomId] = ChatVisibility(cutoff, true, System.currentTimeMillis())
        }
        val json = ChatVisibilityApi.encode(updated)
        val own = requireNotNull(client).userId()
        Gateway.call(state.value.server, ChatVisibilityApi.path(own), json, token, "PUT")
        visibility.putAll(updated)
        directPrefs.edit().putString("visibility|" + own, json.toString()).apply()
        back(); refreshRooms()
        diagnostics.record("INFO", "CHAT_DELETED_FOR_ACCOUNT")
    }

    fun openHistoricalRoom(id: String) = action("openHistoricalRoom") {
        require(id in state.value.alternateRoomIds) { "Выберите прежнюю переписку" }
        openRoomInternal(id, historical = true)
    }

    fun openMainConversation() = action("openMainConversation") { openDirect(requireNotNull(state.value.peerId)) }

    private suspend fun openRoomInternal(id: String, historical: Boolean = false) {
        val c = requireNotNull(client)
        var room = c.getRoom(id) ?: error("Комната ещё не синхронизирована")
        if (room.membership() == Membership.INVITED) room.join()
        withTimeout(30_000) {
            while (room.membership() != Membership.JOINED) { delay(500); room = c.getRoom(id) ?: room }
        }
        require(!room.isEncrypted()) { "Откройте облачный чат через поиск собеседника" }
        closeActiveRoom()
        val currentTimeline = room.timeline()
        timeline = currentTimeline
        val generation = UUID.randomUUID().toString().also { timelineGeneration = it }
        val peer = knownPeers[id] ?: room.activeHumanMemberIds().filter { it != c.userId() }.singleOrNull()
        val alternatives = allRooms.filter { it.peerId == peer && it.id != id }.map { it.id }.sorted()
        mutable.update { it.copy(roomId = id, roomName = room.displayName() ?: "Личная переписка", peerId = peer,
            messages = emptyList(), historical = historical, alternateRoomIds = alternatives, sendError = null, canRetrySend = false) }
        queueListener = room.subscribeToSendQueueUpdates(object : SendQueueListener {
            override fun onUpdate(update: RoomSendQueueUpdate) {
                if (timelineGeneration != generation) return
                when (update) {
                    is RoomSendQueueUpdate.SendError -> {
                        val message = when (update.error) {
                            QueueWedgeError.ExpiredAccessToken -> "Сеанс истёк. Требуется повторный вход."
                            else -> "Отправка приостановлена. Проверьте подключение и повторите попытку."
                        }
                        diagnostics.record("ERROR", "SEND_QUEUE_FAILED", update.error.javaClass.simpleName + " retryable=${update.isRecoverable}")
                        queueFailures[update.transactionId] = message
                        mutable.update { it.copy(sendError = message, canRetrySend = update.isRecoverable,
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
