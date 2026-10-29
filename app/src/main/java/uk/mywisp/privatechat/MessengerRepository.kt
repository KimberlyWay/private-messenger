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

data class ChatRoom(val id: String, val name: String, val invited: Boolean, val peerId: String? = null, val alternateIds: List<String> = emptyList())
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
    val verification: VerificationPrompt? = null,
    val displayName: String = "", val avatarUrl: String? = null, val profileRevision: Int = 0,
    val cryptoReady: Boolean = false, val sendError: String? = null, val canRetrySend: Boolean = false,
    val alternateRoomIds: List<String> = emptyList(), val historical: Boolean = false, val managedRecovery: Boolean = false, val backupStatus: String = "not_configured")

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
    private var backupJob: Job? = null
    private var visibilityJob: Job? = null
    private val visibility = java.util.concurrent.ConcurrentHashMap<String, ChatVisibility>()
    private val visibilityChecks = java.util.concurrent.ConcurrentHashMap<String, Long>()
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
                        else "Не удалось выполнить действие. Проверьте подключение к серверу и настройки шифрования."
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
        val cleanLogin = login.trim().lowercase(java.util.Locale.ROOT)
        val config = Gateway.call(base, "/api/auth/parameters", JSONObject().put("login", cleanLogin))
        require(config.getString("scheme") == "pm-password-v2") { "Обновите сервер мессенджера" }
        val serverName = config.getString("server_name")
        if (register) PasswordRecovery.validatePassword(password)
        val salts = if (register) listOf(PasswordSecrets.randomSalt()) else config.getJSONArray("salts").let { a ->
            require(a.length() in 1..2) { "Некорректные параметры входа" }; (0 until a.length()).map(a::getString)
        }
        var authenticated = false
        for (salt in salts) {
            PasswordSecrets(password, salt, serverName, cleanLogin).use { secrets ->
                val body = JSONObject().put("login", cleanLogin).put("password", secrets.authentication)
                    .put("credential_version", 2).put("credential_salt", salt)
                if (register) Gateway.call(base, "/api/register", body.put("username", username))
                val response = try { Gateway.call(base, "/api/login", body) }
                catch (e: GatewayHttpException) { if (e.status == 401 && !register) return@use else throw e }
                response.put("homeserver", base).put("directory", UUID.randomUUID().toString())
                    .put("db_key", Vault.encode(ByteArray(32).also { SecureRandom().nextBytes(it) }))
                    .put("login", cleanLogin).put("credential_version", 2).put("credential_salt", salt).put("server_name", serverName)
                connect(response, secrets.authentication)
                if (register) client?.setDisplayName(username.trim().removePrefix("@"))
                val user = requireNotNull(client).userId()
                val envelope = PasswordRecovery.get(base, response.getString("access_token"), user)
                if (envelope != null) {
                    val key = try { PasswordRecovery.open(secrets, envelope, serverName, user) }
                    catch (e: Exception) { diagnostics.record("ERROR", "RECOVERY_ENVELOPE_REJECTED", error = e); error("Не удалось проверить копию ключей. Не сбрасывайте защиту: попробуйте доверенное устройство.") }
                    requireNotNull(client).encryption().recover(key)
                    response.put("password_recovery_enabled", true)
                    vault.save(response)
                    refreshSecurity(); setupVerificationController(); retryEncryptedEvents()
                    mutable.update { it.copy(managedRecovery = true, backupStatus = "ready") }
                    diagnostics.record("INFO", "PASSWORD_RECOVERY_OK")
                } else if (register) {
                    createManagedRecovery(secrets, serverName)
                } else {
                    error("Для аккаунта ещё не завершена загрузка копии ключей. Откройте профиль на прежнем устройстве и завершите настройку.")
                }
                require(state.value.verified) { "Копия ключей загружена, но устройство ещё не подтверждено. Обновите состояние." }
                runCatching { refreshProfile() }
                authenticated = true
            }
            if (authenticated) break
        }
        require(authenticated) { "Неверный логин или пароль. Для аккаунта из старой версии используйте отдельный вход ниже." }
    }

    fun authenticateLegacy(server: String, login: String, password: String) = action("authenticateLegacy") {
        val base = Gateway.validateBase(server)
        val response = Gateway.call(base, "/api/login", JSONObject().put("login", login).put("password", password))
        response.put("homeserver", base).put("directory", UUID.randomUUID().toString())
            .put("db_key", Vault.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })).put("login", login.trim().lowercase(java.util.Locale.ROOT))
        connect(response, password)
    }

    private suspend fun createManagedRecovery(secrets: PasswordSecrets, serverName: String, key: String? = null) {
        val data = requireNotNull(sessionData)
        val current = requireNotNull(client)
        require(state.value.verified) { "Сначала подтвердите своё устройство или восстановите прежние ключи" }
        val recovery = key ?: current.encryption().enableRecovery(true, null, object : EnableRecoveryProgressListener {
            override fun onUpdate(status: EnableRecoveryProgress) {}
        })
        val envelope = PasswordRecovery.envelope(secrets, recovery, serverName, current.userId())
        data.put("pending_recovery_envelope", envelope)
        vault.save(data)
        uploadPendingRecovery()
    }

    private fun uploadPendingRecovery() {
        val data = sessionData ?: return
        val envelope = data.optJSONObject("pending_recovery_envelope") ?: return
        if (data.has("pending_credential_salt")) {
            val account = Gateway.call(state.value.server, "/api/auth/account", token = data.getString("access_token"))
            require(account.optInt("credential_version") == 2 && account.optString("salt") == data.optString("pending_credential_salt")) {
                "Сначала завершите перенос аккаунта новым паролем на этом устройстве"
            }
            data.put("credential_version", 2).put("credential_salt", data.getString("pending_credential_salt"))
            data.remove("pending_credential_salt")
        }
        Gateway.call(state.value.server, PasswordRecovery.path(requireNotNull(client).userId()), envelope, data.getString("access_token"), "PUT")
        data.remove("pending_recovery_envelope"); data.put("password_recovery_enabled", true)
        vault.save(data)
        mutable.update { it.copy(managedRecovery = true, backupStatus = "ready") }
        diagnostics.record("INFO", "RECOVERY_ENVELOPE_UPLOADED")
    }

    fun retryRecoveryUpload() = action("retryRecoveryUpload") { uploadPendingRecovery(); scheduleBackup() }

    fun migratePasswordRecovery(oldPassword: String, newPassword: String, existingKey: String, resetLostKeys: Boolean) = action("migratePasswordRecovery") {
        PasswordRecovery.validatePassword(newPassword)
        require(newPassword != oldPassword) { "Выберите новый пароль: старый использовался для входа напрямую на сервере" }
        val current = requireNotNull(client)
        val data = requireNotNull(sessionData)
        val account = Gateway.call(state.value.server, "/api/auth/account", token = data.getString("access_token"))
        val login = account.getString("login")
        val serverName = account.getString("server_name")
        if (data.has("pending_credential_salt") && data.has("pending_recovery_envelope")) {
            val pendingSalt = data.getString("pending_credential_salt")
            val resumed = PasswordSecrets(newPassword, pendingSalt, serverName, login).use { secrets ->
                try {
                    val proof = Gateway.call(state.value.server, "/api/login", JSONObject().put("login", login)
                        .put("password", secrets.authentication).put("credential_version", 2).put("credential_salt", pendingSalt))
                    Gateway.call(state.value.server, "/_matrix/client/v3/logout", JSONObject(), proof.getString("access_token"))
                    true
                } catch (e: GatewayHttpException) { if (e.status == 401) false else throw e }
            }
            if (resumed) { uploadPendingRecovery(); scheduleBackup(); return@action }
        }
        require(account.getInt("credential_version") == 1) { "Защищённый вход уже настроен. Войдите новым паролем." }
        // Verify the old password before any rotation or explicit identity reset.
        val proof = Gateway.call(state.value.server, "/api/login", JSONObject().put("login", login).put("password", oldPassword))
        Gateway.call(state.value.server, "/_matrix/client/v3/logout", JSONObject(), proof.getString("access_token"))
        if (existingKey.isNotBlank()) current.encryption().recover(existingKey.trim())
        refreshSecurity()
        if (!state.value.verified) {
            require(resetLostKeys) { "Нужны прежние ключи или подтверждение другого устройства. Если ключи утрачены, отдельно подтвердите создание новой защиты без восстановления старой истории." }
            val handle = current.encryption().resetIdentity()
            if (handle != null) {
                try {
                    val auth = handle.authType() as? CrossSigningResetAuthType.Uiaa
                        ?: error("Для этого аккаунта требуется подтверждение в браузере")
                    handle.reset(AuthData.Password(AuthDataPasswordDetails(current.userId(), oldPassword, auth.info.session)))
                } finally { handle.close() }
            }
            withTimeout(60_000) { current.encryption().waitForE2eeInitializationTasks() }
            refreshSecurity()
        }
        require(state.value.verified) { "Устройство ещё не подтверждено. Перенос не выполнен." }
        val recovery = if (current.encryption().recoveryState() == RecoveryState.DISABLED) {
            current.encryption().enableRecovery(true, null, object : EnableRecoveryProgressListener { override fun onUpdate(status: EnableRecoveryProgress) {} })
        } else current.encryption().resetRecoveryKey()
        val salt = PasswordSecrets.randomSalt()
        PasswordSecrets(newPassword, salt, serverName, login).use { secrets ->
            // Persist the already encrypted envelope before changing the login credential.
            data.put("pending_recovery_envelope", PasswordRecovery.envelope(secrets, recovery, serverName, current.userId()))
            data.put("pending_credential_salt", salt).put("login", login).put("server_name", serverName)
            vault.save(data)
            Gateway.call(state.value.server, "/api/auth/upgrade", JSONObject().put("old_password", oldPassword)
                .put("new_auth_secret", secrets.authentication).put("salt", salt), data.getString("access_token"))
            data.put("credential_version", 2).put("credential_salt", salt); data.remove("pending_credential_salt")
            vault.save(data)
            uploadPendingRecovery()
        }
        setupVerificationController(); refreshSecurity(); retryEncryptedEvents(); scheduleBackup()
    }

    private fun scheduleBackup() {
        if (!state.value.managedRecovery) return
        backupJob?.cancel()
        backupJob = scope.launch {
            mutable.update { it.copy(backupStatus = "syncing") }
            delay(1000)
            try {
                val current = client ?: return@launch
                withTimeout(120_000) { current.encryption().waitForBackupUploadSteadyState(null) }
                if (client === current) mutable.update { it.copy(backupStatus = "ready") }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                diagnostics.record("WARNING", "KEY_BACKUP_UPLOAD_FAILED", error = e)
                mutable.update { it.copy(backupStatus = "error") }
            }
        }
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
        vault.save(data)
        canonical.clear(); knownPeers.clear(); allRooms = emptyList(); visibility.clear(); visibilityChecks.clear()
        visibility.putAll(ChatVisibilityApi.parse(directPrefs.getString("visibility|" + built.userId(), null)))
        val ownPrefix = built.userId() + "|"
        directPrefs.all.forEach { (key, value) ->
            if (key.startsWith(ownPrefix) && value is String) canonical[key.removePrefix(ownPrefix)] = value
        }
        mutable.update { ChatState(loading = false, busy = it.busy, userId = built.userId(), server = base,
            managedRecovery = data.optBoolean("password_recovery_enabled"),
            backupStatus = if (data.optBoolean("password_recovery_enabled")) "syncing" else "not_configured") }
        // Match the initialization order used by the native SDK integration test.
        // A failed first sync does not discard the already persisted device session.
        try { withTimeout(30_000) { built.syncOnceV2(SyncSettingsV2(timeoutMs = 0u)) } }
        catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
        }
        sync = built.syncV2(SyncSettingsV2(), object : SyncListenerV2 {
            override fun onUpdate(response: SyncResponseV2) {
                scope.launch { try { refreshRooms(); refreshSecurity(); scheduleBackup() } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    diagnostics.record("WARNING", "SYNC_STATE_REFRESH_FAILED", error = e)
                } }
            }
        })
        val ready = withTimeoutOrNull(60_000) { built.encryption().waitForE2eeInitializationTasks(); true } == true
        mutable.update { it.copy(cryptoReady = ready) }
        diagnostics.record(if (ready) "INFO" else "WARNING", "CRYPTO_INITIALIZATION", "ready=$ready verified=${built.encryption().verificationState()}")
        setupVerificationController()
        refreshRooms()
        refreshSecurity()
        runCatching { refreshProfile() }
        if (data.has("pending_recovery_envelope") && data.optInt("credential_version") == 2) uploadPendingRecovery()
        scheduleBackup()
    }

    private suspend fun setupVerificationController() {
        if (verificationController != null) return
        val current = client ?: return
        if (current.encryption().userIdentity(current.userId(), false) != null) {
            verificationController = current.getSessionVerificationController().also { it.setDelegate(verificationDelegate) }
        }
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

    private fun refreshSecurity() {
        val encryption = client?.encryption() ?: return
        mutable.update { it.copy(recovery = encryption.recoveryState().name,
            verified = encryption.verificationState() == VerificationState.VERIFIED) }
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
        require(room.isEncrypted()) { "Этот чат не зашифрован. Отправка запрещена." }
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
                            QueueWedgeError.CrossVerificationRequired -> "Подтвердите это устройство в профиле или восстановите ключи. Сообщение не отправлено."
                            is QueueWedgeError.InsecureDevices -> "У собеседника есть неподтверждённое устройство. Ему нужно восстановить ключи или подтвердить сеанс."
                            is QueueWedgeError.IdentityViolations -> "Ключи собеседника изменились. Повторно сверьте устройства."
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
                        scheduleBackup()
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
        queueListener?.cancel(); queueListener = null
        listener?.cancel(); listener = null
        timeline?.close(); timeline = null
        synchronized(itemLock) { items.clear() }
    }

    private suspend fun sendingRoom(): Room {
        require(!state.value.historical) { "Откройте основной чат, чтобы отправить сообщение" }
        require(state.value.cryptoReady) { "Шифрование ещё настраивается. Нажмите обновление и дождитесь завершения." }
        refreshSecurity()
        require(state.value.verified) { "Это устройство не подтверждено после входа. В профиле восстановите ключи или подтвердите его с другого устройства. Повторная проверка собеседника этого не исправит." }
        val room = client?.getRoom(requireNotNull(state.value.roomId)) ?: error("Выберите чат")
        require(room.isEncrypted()) { "Отправка без сквозного шифрования запрещена" }
        state.value.peerId?.let { peer ->
            client?.encryption()?.userIdentity(peer, true)?.let {
                check(!it.hasVerificationViolation()) { "Ключи собеседника изменились. Повторно проверьте его устройство." }
            }
        }
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
    private fun retryEncryptedEvents() {
        val sessions = synchronized(itemLock) {
            items.mapNotNull {
                val kind = (it.asEvent()?.content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.UnableToDecrypt
                (kind?.msg as? EncryptedMessage.MegolmV1AesSha2)?.sessionId
            }.distinct()
        }
        if (sessions.isNotEmpty()) timeline?.retryDecryption(sessions)
    }
    fun refresh() = action("refresh") {
        val ready = withTimeoutOrNull(30_000) { client?.encryption()?.waitForE2eeInitializationTasks(); true } == true
        mutable.update { it.copy(cryptoReady = ready) }
        refreshSecurity()
        setupVerificationController()
        timeline?.fetchMembers()
        retryEncryptedEvents()
        refreshRooms(); refreshSecurity(); profiles.clear(); runCatching { refreshProfile() }
    }
    fun createRecovery() = action("createRecovery") {
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
    fun recover(key: String) = action("recover") {
        requireNotNull(client).encryption().recover(key.trim())
        setupVerificationController(); timeline?.fetchMembers(); retryEncryptedEvents(); refreshSecurity()
        if (state.value.verified) client?.enableAllSendQueues(true)
    }
    fun logout() = action("logout") {
        val c = client ?: return@action
        visibilityJob?.cancel(); visibilityJob = null
        backupJob?.cancelAndJoin(); backupJob = null
        if (state.value.managedRecovery) withTimeout(120_000) { c.encryption().waitForBackupUploadSteadyState(null) }
        c.logout()
        closeActiveRoom(); sync?.cancel(); sync = null
        verificationController?.setDelegate(null); verificationController?.close(); verificationController = null
        c.close(); client = null; vault.clear(); sessionData = null; profiles.clear(); canonical.clear(); knownPeers.clear(); queueFailures.clear(); allRooms = emptyList()
        File(context.cacheDir, "profile-photos").deleteRecursively()
        mutable.value = ChatState(loading = false)
    }

    fun requestOwnVerification() = action("requestOwnVerification") {
        require(state.value.cryptoReady) { "Дождитесь настройки шифрования" }
        val current = requireNotNull(client)
        current.encryption().userIdentity(current.userId(), true)
        setupVerificationController()
        verificationPeer = current.userId()
        requireNotNull(verificationController) { "Ключи аккаунта ещё не загружены. Обновите состояние." }
            .requestUserVerification(current.userId())
        mutable.update { it.copy(verification = VerificationPrompt("waiting", "Подтверждение устройства", "Примите запрос на другом устройстве этого аккаунта и сравните символы.")) }
    }

    fun requestVerification() = action("requestVerification") {
        require(state.value.cryptoReady) { "Шифрование ещё настраивается. Дождитесь завершения и обновите состояние." }
        refreshSecurity()
        require(state.value.verified) { "Сначала подтвердите своё устройство в профиле или восстановите ключи. Проверка собеседника не заменяет восстановление своего сеанса." }
        val peer = requireNotNull(state.value.peerId) { "Собеседник ещё не присоединился" }
        val identity = withTimeout(30_000) { requireNotNull(client).encryption().userIdentity(peer, true) }
        requireNotNull(identity) { "У собеседника ещё не настроены ключи шифрования. Ему нужно открыть профиль и проверить состояние устройства." }
        if (identity.isVerified()) {
            mutable.update { it.copy(verification = VerificationPrompt("done", "Собеседник уже проверен", "Повторная проверка сейчас не требуется.")) }
            return@action
        }
        setupVerificationController()
        verificationPeer = peer
        requireNotNull(verificationController) { "Ключи ещё настраиваются. Обновите состояние или восстановите ключи в настройках." }.requestUserVerification(peer)
        mutable.update { it.copy(verification = VerificationPrompt("waiting", "Проверка собеседника", "Ожидаем подтверждения на втором устройстве…")) }
    }
    fun acceptVerification() = action("acceptVerification") {
        val request = requireNotNull(verificationRequest)
        verificationController?.acknowledgeVerificationRequest(request.senderProfile.userId, request.flowId)
        verificationController?.acceptVerificationRequest()
    }
    fun approveVerification() = action("approveVerification") { verificationController?.approveVerification() }
    fun cancelVerification() = action("cancelVerification") {
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
