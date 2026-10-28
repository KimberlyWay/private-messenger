package uk.mywisp.privatechat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.*
import uniffi.matrix_sdk_crypto.CollectStrategy
import uniffi.matrix_sdk_crypto.DecryptionSettings
import uniffi.matrix_sdk_crypto.TrustRequirement
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Runs against the real isolated Synapse + PostgreSQL + registration gateway. */
@RunWith(AndroidJUnit4::class)
class EncryptionIntegrationTest {
    private val base = "http://10.0.2.2:8088"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clients = mutableListOf<Client>()
    private val tasks = mutableListOf<TaskHandle>()

    private suspend fun client(login: String, password: String): Pair<Client, JSONObject> {
        val session = Gateway.call(base, "/api/login", JSONObject().put("login", login).put("password", password))
        val root = File(context.cacheDir, "integration/" + UUID.randomUUID()).apply { mkdirs() }
        val c = ClientBuilder().homeserverUrl(base).disableWellKnownLookup(true)
            .sqliteStore(SqliteStoreBuilder(root.path, root.path).key(ByteArray(32).also { SecureRandom().nextBytes(it) }))
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NONE)
            .autoEnableCrossSigning(true).autoEnableBackups(true)
            .roomKeyRecipientStrategy(CollectStrategy.IDENTITY_BASED_STRATEGY)
            .decryptionSettings(DecryptionSettings(TrustRequirement.CROSS_SIGNED)).build()
        clients += c
        Gateway.call(base, "/_matrix/client/v3/logout", JSONObject(), session.getString("access_token"))
        c.login(session.getString("user_id"), password, "Encryption integration", null)
        val native = c.session()
        session.put("access_token", native.accessToken).put("device_id", native.deviceId)
        android.util.Log.i("EncryptionTest", "Starting first sync: ${c.userId()}")
        withTimeout(30_000) { c.syncOnceV2(SyncSettingsV2(timeoutMs = 0u)) }
        tasks += c.syncV2(SyncSettingsV2(), object : SyncListenerV2 { override fun onUpdate(response: SyncResponseV2) {} })
        android.util.Log.i("EncryptionTest", "First sync done; waiting for crypto initialization")
        try { withTimeout(60_000) { c.encryption().waitForE2eeInitializationTasks() } }
        catch (e: TimeoutCancellationException) { throw AssertionError("Crypto initialization timed out for ${c.userId()}; verification=${c.encryption().verificationState()}", e) }
        android.util.Log.i("EncryptionTest", "Crypto initialized: ${c.encryption().verificationState()}")
        return c to session
    }

    private suspend fun waitUntil(label: String, test: () -> Boolean) {
        android.util.Log.i("EncryptionTest", label)
        try { withTimeout(90_000) { while (!test()) delay(500) } }
        catch (e: TimeoutCancellationException) { throw AssertionError("Timed out: $label", e) }
    }

    private fun changedItems(diffs: List<TimelineDiff>): List<TimelineItem> = diffs.flatMap {
        when (it) {
            is TimelineDiff.Append -> it.values
            is TimelineDiff.Reset -> it.values
            is TimelineDiff.PushBack -> listOf(it.value)
            is TimelineDiff.PushFront -> listOf(it.value)
            is TimelineDiff.Insert -> listOf(it.value)
            is TimelineDiff.Set -> listOf(it.value)
            else -> emptyList()
        }
    }

    private fun verificationData(data: SessionVerificationData): String = when (data) {
        is SessionVerificationData.Emojis -> data.emojis.joinToString("|") { it.symbol() }
        is SessionVerificationData.Decimals -> data.values.joinToString("|")
    }

    private suspend fun verifyPeers(alice: Client, bob: Client) = coroutineScope {
        val request = CompletableDeferred<SessionVerificationRequestDetails>()
        val leftData = CompletableDeferred<String>()
        val rightData = CompletableDeferred<String>()
        val leftDone = CompletableDeferred<Unit>()
        val rightDone = CompletableDeferred<Unit>()
        val left = alice.getSessionVerificationController()
        val right = bob.getSessionVerificationController()
        fun delegate(isLeft: Boolean) = object : SessionVerificationControllerDelegate {
            override fun didReceiveVerificationRequest(details: SessionVerificationRequestDetails) { request.complete(details) }
            override fun didAcceptVerificationRequest() {
                if (isLeft) launch { runCatching { left.startSasVerification() }.onFailure { leftData.completeExceptionally(it) } }
            }
            override fun didStartSasVerification() {}
            override fun didReceiveVerificationData(data: SessionVerificationData) {
                (if (isLeft) leftData else rightData).complete(verificationData(data))
            }
            override fun didFinish() { (if (isLeft) leftDone else rightDone).complete(Unit) }
            override fun didFail() { failure("SAS failed") }
            override fun didCancel() { failure("SAS cancelled") }
            private fun failure(reason: String) {
                val error = AssertionError(reason)
                listOf(leftData, rightData).forEach { it.completeExceptionally(error) }
                listOf(leftDone, rightDone).forEach { it.completeExceptionally(error) }
                request.completeExceptionally(error)
            }
        }
        left.setDelegate(delegate(true)); right.setDelegate(delegate(false))
        try {
            withTimeout(90_000) {
                left.requestUserVerification(bob.userId())
                val details = request.await()
                assertEquals(alice.userId(), details.senderProfile.userId)
                right.acknowledgeVerificationRequest(details.senderProfile.userId, details.flowId)
                right.acceptVerificationRequest()
                // Test-only approval after an exact comparison on both endpoints.
                // The application requires a human comparison via an independent channel.
                assertEquals(leftData.await(), rightData.await())
                left.approveVerification(); right.approveVerification()
                leftDone.await(); rightDone.await()
            }
            assertTrue(alice.encryption().userIdentity(bob.userId(), true)!!.isVerified())
            assertTrue(bob.encryption().userIdentity(alice.userId(), true)!!.isVerified())
        } finally {
            left.setDelegate(null); right.setDelegate(null)
            left.close(); right.close()
        }
    }

    @Test
    fun messagesAndFilesAreCiphertextOnServerAndRecoverOnNewDevice() = runBlocking(Dispatchers.IO) {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val aliceLogin = "login_a_$suffix"
        val bobLogin = "login_b_$suffix"
        val aliceName = "alice_$suffix"
        val bobName = "bob_$suffix"
        val password = "Test-" + UUID.randomUUID()
        for ((login, name) in listOf(aliceLogin to aliceName, bobLogin to bobName)) {
            Gateway.call(base, "/api/register", JSONObject().put("login", login).put("username", name).put("password", password))
        }
        try {
            val (alice, aliceSession) = client(aliceLogin, password)
            val (bob, bobSession) = client(bobLogin, password)
            waitUntil("initial devices cross-signed") { alice.encryption().verificationState() == VerificationState.VERIFIED && bob.encryption().verificationState() == VerificationState.VERIFIED }
            val recovery = alice.encryption().enableRecovery(true, null, object : EnableRecoveryProgressListener {
                override fun onUpdate(status: EnableRecoveryProgress) {}
            })
            assertTrue(recovery.isNotBlank())
            val roomId = alice.createRoom(CreateRoomParameters(name = null, isEncrypted = true, isDirect = true,
                visibility = RoomVisibility.Private, preset = RoomPreset.PRIVATE_CHAT, invite = listOf(bob.userId())))
            withTimeout(60_000) { alice.awaitRoomRemoteEcho(roomId) }
            waitUntil("Bob receives invitation") { bob.getRoom(roomId)?.membership() == Membership.INVITED }
            bob.getRoom(roomId)!!.join()
            waitUntil("Bob joins room") { bob.getRoom(roomId)?.membership() == Membership.JOINED }
            val aliceRoom = alice.getRoom(roomId)!!
            val bobRoom = bob.getRoom(roomId)!!
            assertTrue(aliceRoom.isEncrypted()); assertTrue(bobRoom.isEncrypted())
            android.util.Log.i("EncryptionTest", "Comparing SAS on both endpoints")
            verifyPeers(alice, bob)
            val received = AtomicReference<EventTimelineItem?>()
            val imageSource = AtomicReference<MediaSource?>()
            val marker = "end-to-end-secret-" + UUID.randomUUID()
            val incoming = bobRoom.timeline()
            tasks += incoming.addListener(object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    for (item in changedItems(diff)) {
                        val event = item.asEvent() ?: continue
                        val kind = (event.content as? TimelineItemContent.MsgLike)?.content?.kind
                        val msg = (kind as? MsgLikeKind.Message)?.content ?: continue
                        if (msg.body == marker) received.set(event)
                        (msg.msgType as? MessageType.Image)?.let { imageSource.set(it.content.source) }
                    }
                }
            })
            val outgoing = aliceRoom.timeline()
            outgoing.send(messageEventContentNew(MessageType.Text(TextMessageContent(marker, null))))
            waitUntil("Bob decrypts text") { received.get() != null }
            val eventId = (received.get()!!.eventOrTransactionId as EventOrTransactionId.EventId).eventId
            val raw = Gateway.call(base, "/_matrix/client/v3/rooms/${Gateway.escaped(roomId)}/event/${Gateway.escaped(eventId)}",
                token = bobSession.getString("access_token"))
            assertEquals("m.room.encrypted", raw.getString("type"))
            assertFalse("Server must not receive plaintext", raw.toString().contains(marker))
            val bytes = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6fV0AAAAASUVORK5CYII=", android.util.Base64.DEFAULT)
            outgoing.sendAttachment(UploadParameters(UploadSource.Data(bytes, "test.png"), null, null, null, null),
                AttachmentKind.Image(ImageInfo(1u, 1u, "image/png", bytes.size.toULong(), null, null, null, false), null)).join()
            waitUntil("Bob receives encrypted image") { imageSource.get() != null }
            val source = imageSource.get()!!
            assertTrue("Encrypted media contains its client-side key", source.toJson().contains("key"))
            assertArrayEquals(bytes, bob.getMediaContent(source))
            val handle = bob.getMediaFile(source, "test.png", "image/png", true, context.cacheDir.path)
            val opened = File(context.cacheDir, "verified-media-${UUID.randomUUID()}.png")
            try { assertTrue(handle.persist(opened.path)) } finally { handle.close() }
            assertArrayEquals(bytes, opened.readBytes())
            val parts = source.url().removePrefix("mxc://").split('/', limit = 2)
            val connection = URL(base + "/_matrix/client/v1/media/download/" + Gateway.escaped(parts[0]) + "/" + Gateway.escaped(parts[1])).openConnection() as HttpURLConnection
            connection.setRequestProperty("Authorization", "Bearer " + bobSession.getString("access_token"))
            val encrypted = try { connection.inputStream.use { it.readBytes() } } finally { connection.disconnect() }
            assertFalse("Media bytes stored on VPS must be encrypted", encrypted.contentEquals(bytes))
            alice.encryption().waitForBackupUploadSteadyState(null)
            val (restored, _) = client(aliceLogin, password)
            withTimeout(120_000) { restored.encryption().recover(recovery) }
            waitUntil("restored device verified") { restored.encryption().verificationState() == VerificationState.VERIFIED }
            waitUntil("restored room available") { restored.getRoom(roomId) != null }
            val recoveredText = AtomicReference<String?>()
            val history = restored.getRoom(roomId)!!.timeline()
            tasks += history.addListener(object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    for (item in changedItems(diff)) {
                        val msg = ((item.asEvent()?.content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.Message)?.content
                        if (msg?.body == marker) recoveredText.set(marker)
                    }
                }
            })
            history.paginateBackwards(30u)
            waitUntil("restored device decrypts history") { recoveredText.get() == marker }
            assertEquals(marker, recoveredText.get())
        } finally {
            tasks.forEach { runCatching { it.cancel() } }
            clients.forEach { runCatching { it.close() } }
        }
    }
}
