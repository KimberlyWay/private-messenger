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
import uniffi.matrix_sdk_crypto.CollectStrategy
import uniffi.matrix_sdk_crypto.DecryptionSettings
import uniffi.matrix_sdk_crypto.TrustRequirement
import java.io.File
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID

data class ChatRoom(val id: String, val name: String, val invited: Boolean)
data class Attachment(val source: MediaSource, val name: String, val mime: String, val kind: String)
data class ChatMessage(val id: String, val sender: String, val own: Boolean, val time: Long,
    val text: String, val attachment: Attachment? = null, val status: String = "", val trustWarning: String? = null)
data class VerificationPrompt(val stage: String, val title: String, val content: String)
data class ChatState(
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null,
    val userId: String? = null, val server: String = "", val rooms: List<ChatRoom> = emptyList(),
    val roomId: String? = null, val roomName: String = "", val peerId: String? = null,
    val messages: List<ChatMessage> = emptyList(), val recovery: String = "UNKNOWN",
    val verified: Boolean = false, val recoveryKey: String? = null,
    val verification: VerificationPrompt? = null)

class MessengerRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vault = Vault(context)
    private val actionMutex = Mutex()
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private var client: Client? = null
    private var sync: TaskHandle? = null
    private var timeline: Timeline? = null
    private var listener: TaskHandle? = null
    private var verificationController: SessionVerificationController? = null
    private var verificationRequest: SessionVerificationRequestDetails? = null
    private var verificationPeer: String = ""
    private var sessionData: JSONObject? = null
    private var timelineGeneration = ""
    private val items = mutableListOf<TimelineItem>()
    private val itemLock = Any()

    private fun action(block: suspend () -> Unit) {
        scope.launch {
            actionMutex.withLock {
                mutable.update { it.copy(busy = true, error = null) }
                try { block() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val message = if (e is IllegalArgumentException || e is IllegalStateException) e.message
                        else "Не удалось выполнить действие. Проверьте подключение к серверу и настройки шифрования."
                    mutable.update { it.copy(error = message?.take(240)) }
                } finally { mutable.update { it.copy(busy = false) } }
            }
        }
    }

    fun authenticate(server: String, login: String, username: String, password: String, register: Boolean) = action {
        val base = Gateway.validateBase(server)
        if (register) Gateway.call(base, "/api/register", JSONObject()
            .put("login", login).put("username", username).put("password", password))
        val response = Gateway.call(base, "/api/login", JSONObject().put("login", login).put("password", password))
        // Never accept a different homeserver suggested by an account gateway.
        response.put("homeserver", base)
        response.put("directory", UUID.randomUUID().toString())
        response.put("db_key", Vault.encode(ByteArray(32).also { SecureRandom().nextBytes(it) }))
        connect(response, password)
        vault.save(response)
        if (register) client?.setDisplayName(username.trim().removePrefix("@"))
    }

    private suspend fun connect(data: JSONObject, password: String? = null) {
        closeActiveRoom()
        sync?.cancel()
        verificationController?.setDelegate(null); verificationController?.close(); verificationController = null
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
            .autoEnableCrossSigning(true).autoEnableBackups(true)
            .roomKeyRecipientStrategy(CollectStrategy.IDENTITY_BASED_STRATEGY)
            .decryptionSettings(DecryptionSettings(TrustRequirement.CROSS_SIGNED_OR_LEGACY))
            .userAgent("PrivateMessenger/" + BuildConfig.VERSION_NAME)
            .build()
        if (password != null) {
            // The gateway authenticates the private login. Use native login for the real
            // device so the SDK can bootstrap device keys and authenticated cross-signing.
            Gateway.call(base, "/_matrix/client/v3/logout", JSONObject(), data.getString("access_token"))
            built.login(data.getString("user_id"), password, "Private Messenger Android", null)
            val session = built.session()
            data.put("access_token", session.accessToken).put("device_id", session.deviceId).put("user_id", session.userId)
        } else {
            built.restoreSession(Session(data.getString("access_token"), null, data.getString("user_id"),
                data.getString("device_id"), base, null, SlidingSyncVersion.NONE))
        }
        client = built
        sessionData = data
        mutable.update { ChatState(loading = false, busy = it.busy, userId = built.userId(), server = base) }
        sync = built.syncV2(SyncSettingsV2(), object : SyncListenerV2 {
            override fun onUpdate(response: SyncResponseV2) {
                scope.launch { runCatching { refreshRooms(); refreshSecurity() } }
            }
        })
        withTimeoutOrNull(30_000) { built.encryption().waitForE2eeInitializationTasks() }
        setupVerificationController()
        refreshRooms()
        refreshSecurity()
    }

    private suspend fun setupVerificationController() {
        if (verificationController != null) return
        val current = client ?: return
        if (current.encryption().userIdentity(current.userId(), false) != null) {
            verificationController = current.getSessionVerificationController().also { it.setDelegate(verificationDelegate) }
        }
    }

    private fun refreshRooms() {
        val rooms = client?.rooms().orEmpty().filter { it.membership() in listOf(Membership.JOINED, Membership.INVITED) }
            .map { ChatRoom(it.id(), it.displayName() ?: it.rawName() ?: "Личная переписка", it.membership() == Membership.INVITED) }
        mutable.update { it.copy(rooms = rooms) }
    }

    private fun refreshSecurity() {
        val encryption = client?.encryption() ?: return
        mutable.update { it.copy(recovery = encryption.recoveryState().name,
            verified = encryption.verificationState() == VerificationState.VERIFIED) }
    }

    fun findAndChat(username: String) = action {
        val c = requireNotNull(client)
        val data = requireNotNull(sessionData)
        val result = Gateway.call(state.value.server, "/api/users/" + Gateway.escaped(username.trim().removePrefix("@")),
            token = data.getString("access_token"))
        val target = result.getString("user_id")
        require(target != c.userId()) { "Выберите другого пользователя" }
        val existing = c.getDmRoom(target)
        val id = existing?.id() ?: c.createRoom(CreateRoomParameters(name = null, isEncrypted = true,
            isDirect = true, visibility = RoomVisibility.Private, preset = RoomPreset.PRIVATE_CHAT, invite = listOf(target)))
        withTimeout(30_000) { c.awaitRoomRemoteEcho(id) }
        openRoomInternal(id)
    }

    fun openRoom(id: String) = action { openRoomInternal(id) }

    private suspend fun openRoomInternal(id: String) {
        val c = requireNotNull(client)
        var room = c.getRoom(id) ?: error("Комната ещё не синхронизирована")
        if (room.membership() == Membership.INVITED) room.join()
        withTimeout(30_000) {
            while (room.membership() != Membership.JOINED) { delay(500); room = c.getRoom(id) ?: room }
        }
        require(room.isEncrypted()) { "Этот чат не зашифрован. Отправка запрещена." }
        closeActiveRoom()
        val currentTimeline = room.timeline()
        timeline = currentTimeline
        val generation = UUID.randomUUID().toString().also { timelineGeneration = it }
        val peer = room.activeHumanMemberIds().firstOrNull { it != c.userId() }
        mutable.update { it.copy(roomId = id, roomName = room.displayName() ?: "Личная переписка", peerId = peer, messages = emptyList()) }
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
        val content = (event.content as? TimelineItemContent.MsgLike)?.content?.kind ?: return null
        val id = when (val value = event.eventOrTransactionId) {
            is EventOrTransactionId.EventId -> value.eventId
            is EventOrTransactionId.TransactionId -> value.transactionId
        }
        val status = if (event.isRemote) "" else if (event.localSendState?.javaClass?.simpleName?.contains("Fail") == true)
            "Не отправлено" else "Отправляется…"
        if (content is MsgLikeKind.UnableToDecrypt) return ChatMessage(id, event.sender, event.isOwn, event.timestamp.toLong(),
            "Не удалось расшифровать. Восстановите ключи и проверьте устройства.")
        val msg = (content as? MsgLikeKind.Message)?.content ?: return null
        val attachment = when (val type = msg.msgType) {
            is MessageType.Image -> Attachment(type.content.source, type.content.filename, type.content.info?.mimetype ?: "image/jpeg", "image")
            is MessageType.Video -> Attachment(type.content.source, type.content.filename, type.content.info?.mimetype ?: "video/mp4",
                if (type.content.filename.startsWith("circle_")) "circle" else "video")
            is MessageType.Audio -> Attachment(type.content.source, type.content.filename, type.content.info?.mimetype ?: "audio/mp4", "voice")
            else -> null
        }
        // Matrix v1 backups lack authenticated sender provenance. Preserve the
        // SDK warning for recovered legacy sessions and unverified identities.
        val warning = when (event.lazyProvider.getShields(true)) {
            ShieldState.None -> null
            is ShieldState.Red -> "Подлинность отправителя не подтверждена. История из резервной копии может иметь этот статус."
            is ShieldState.Grey -> "Сверьте эмодзи с собеседником: его личность ещё не подтверждена."
        }
        return ChatMessage(id, event.sender, event.isOwn, event.timestamp.toLong(), msg.body, attachment, status, warning)
    }

    fun back() { closeActiveRoom(); mutable.update { it.copy(roomId = null, peerId = null, messages = emptyList()) } }
    private fun closeActiveRoom() {
        timelineGeneration = ""
        listener?.cancel(); listener = null
        timeline?.close(); timeline = null
        synchronized(itemLock) { items.clear() }
    }

    private suspend fun sendingRoom(): Room {
        val room = client?.getRoom(requireNotNull(state.value.roomId)) ?: error("Выберите чат")
        require(room.isEncrypted()) { "Отправка без сквозного шифрования запрещена" }
        state.value.peerId?.let { peer ->
            client?.encryption()?.userIdentity(peer, true)?.let {
                check(!it.hasVerificationViolation()) { "Ключи собеседника изменились. Повторно проверьте его устройство." }
            }
        }
        return room
    }

    fun sendText(text: String, queued: () -> Unit = {}) = action {
        require(text.isNotBlank() && text.length <= 16_000) { "Сообщение: от 1 до 16 000 символов" }
        sendingRoom()
        requireNotNull(timeline).send(messageEventContentNew(MessageType.Text(TextMessageContent(text, null))))
        withContext(Dispatchers.Main) { queued() }
    }

    fun sendPicked(uri: Uri) = action {
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

    fun sendRecorded(file: File, kind: String, duration: Long) = action {
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

    fun older() = action { timeline?.paginateBackwards(30u) }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    private fun retryEncryptedEvents() {
        val sessions = synchronized(itemLock) {
            items.mapNotNull {
                val kind = (it.asEvent()?.content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.UnableToDecrypt
                (kind?.msg as? EncryptedMessage.MegolmV1AesSha2)?.sessionId
            }.distinct()
        }
        if (sessions.isNotEmpty()) timeline?.retryDecryption(sessions)
    }
    fun refresh() = action {
        client?.enableAllSendQueues(true)
        setupVerificationController()
        timeline?.fetchMembers()
        retryEncryptedEvents()
        refreshRooms(); refreshSecurity()
    }
    fun createRecovery() = action {
        val encryption = requireNotNull(client).encryption()
        require(encryption.recoveryState() == RecoveryState.DISABLED && encryption.verificationState() == VerificationState.VERIFIED) {
            "Сначала подтвердите устройство или введите существующий ключ восстановления. Существующие ключи не сбрасываются."
        }
        val key = encryption.enableRecovery(true, null, object : EnableRecoveryProgressListener {
            override fun onUpdate(status: EnableRecoveryProgress) {}
        })
        mutable.update { it.copy(recoveryKey = key) }; refreshSecurity()
    }
    fun hideRecovery() { mutable.update { it.copy(recoveryKey = null) } }
    fun recover(key: String) = action {
        requireNotNull(client).encryption().recover(key.trim())
        setupVerificationController(); timeline?.fetchMembers(); retryEncryptedEvents(); refreshSecurity()
    }
    fun logout() = action {
        val c = client ?: return@action
        c.logout()
        closeActiveRoom(); sync?.cancel(); sync = null
        verificationController?.setDelegate(null); verificationController?.close(); verificationController = null
        c.close(); client = null; vault.clear(); sessionData = null
        mutable.value = ChatState(loading = false)
    }

    fun requestVerification() = action {
        val peer = requireNotNull(state.value.peerId) { "Собеседник ещё не присоединился" }
        verificationPeer = peer
        requireNotNull(verificationController) { "Ключи ещё настраиваются. Обновите состояние или восстановите ключи в настройках." }.requestUserVerification(peer)
        mutable.update { it.copy(verification = VerificationPrompt("waiting", "Проверка собеседника", "Ожидаем подтверждения на втором устройстве…")) }
    }
    fun acceptVerification() = action {
        val request = requireNotNull(verificationRequest)
        verificationController?.acknowledgeVerificationRequest(request.senderProfile.userId, request.flowId)
        verificationController?.acceptVerificationRequest()
    }
    fun approveVerification() = action { verificationController?.approveVerification() }
    fun cancelVerification() = action {
        verificationController?.cancelVerification()
        mutable.update { it.copy(verification = null) }
    }
    fun closeVerification() { mutable.update { it.copy(verification = null) } }

    private val verificationDelegate = object : SessionVerificationControllerDelegate {
        override fun didReceiveVerificationRequest(details: SessionVerificationRequestDetails) {
            verificationRequest = details
            verificationPeer = details.senderProfile.userId
            mutable.update { it.copy(verification = VerificationPrompt("request", "Запрос проверки", details.senderProfile.userId)) }
        }
        override fun didAcceptVerificationRequest() {
            scope.launch { runCatching { verificationController?.startSasVerification() } }
        }
        override fun didStartSasVerification() {}
        override fun didReceiveVerificationData(data: SessionVerificationData) {
            val value = when (data) {
                is SessionVerificationData.Emojis -> data.emojis.joinToString("  ") { it.symbol() }
                is SessionVerificationData.Decimals -> data.values.joinToString("  ")
            }
            mutable.update { it.copy(verification = VerificationPrompt("compare", "Сравните на двух устройствах", "$verificationPeer\n\n$value")) }
        }
        override fun didFail() { mutable.update { it.copy(verification = VerificationPrompt("done", "Проверка не выполнена", "Попробуйте ещё раз. Не подтверждайте несовпадающие символы.")) } }
        override fun didCancel() { mutable.update { it.copy(verification = null) } }
        override fun didFinish() {
            mutable.update { it.copy(verification = VerificationPrompt("done", "Проверка завершена", "Криптографическая проверка устройств выполнена.")) }
            refreshSecurity()
        }
    }

    init {
        scope.launch {
            try {
                vault.load()?.let { connect(it) }
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Не удалось восстановить сеанс. Войдите снова; для истории потребуется ключ восстановления.") }
            } finally { mutable.update { it.copy(loading = false) } }
        }
    }

    companion object { const val MAX_MEDIA = 50L * 1024 * 1024 }
}
