package uk.mywisp.privatechat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercise the actual repository/UI data path; native clients alone miss invite and queue bugs. */
@RunWith(AndroidJUnit4::class)
class ConversationFlowTest {
    private class AccountContext(base: Context, private val key: String) : ContextWrapper(base) {
        override fun getFilesDir() = File(super.getFilesDir(), key).apply { mkdirs() }
        override fun getCacheDir() = File(super.getCacheDir(), key).apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(key + "_" + name, mode)
    }
    private suspend fun until(label: String, test: () -> Boolean) {
        try { withTimeout(120_000) { while (!test()) delay(100) } }
        catch (e: TimeoutCancellationException) { throw AssertionError(label, e) }
    }
    private fun noError(vararg repos: MessengerRepository) {
        repos.forEach { assertNull("Repository action failed: " + it.state.value.error, it.state.value.error) }
    }

    @Test fun cloudHistoryAndAttachmentsSurviveLoginOnMultipleDevices() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        val context = instrumentation.targetContext
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val leftContext = AccountContext(context, "conversation_a_$suffix")
        val rightContext = AccountContext(context, "conversation_b_$suffix")
        val alice = MessengerRepository(leftContext)
        val bob = MessengerRepository(rightContext)
        val secondBob = MessengerRepository(AccountContext(context, "conversation_b2_$suffix"))
        val aliceName = "flowa_$suffix"
        val bobName = "flowb_$suffix"
        val password = "Test-" + UUID.randomUUID()
        try {
            until("Initial repository state") { !alice.state.value.loading && !bob.state.value.loading }
            alice.authenticate(BuildConfig.DEFAULT_SERVER, "private_" + aliceName, aliceName, password, true)
            bob.authenticate(BuildConfig.DEFAULT_SERVER, "private_" + bobName, bobName, password, true)
            until("Register both accounts") { alice.state.value.userId != null && bob.state.value.userId != null && !alice.state.value.busy && !bob.state.value.busy }
            noError(alice, bob)
            alice.findAndChat(bobName)
            until("Create initial direct room") { alice.state.value.roomId != null && !alice.state.value.busy }
            noError(alice)
            val id = requireNotNull(alice.state.value.roomId)
            until("Invitation must remain visible without /members permission") { bob.state.value.rooms.any { it.id == id && it.invited } }
            repeat(3) { alice.findAndChat(bobName) }
            bob.findAndChat(aliceName)
            until("The recipient's search opens the same invited room") { bob.state.value.roomId == id && !bob.state.value.busy }
            noError(alice, bob)
            val text = "first-contact-" + UUID.randomUUID()
            alice.sendText(text)
            until("Bob receives the first cloud message") {
                bob.state.value.messages.any { it.text == text && !it.own } || alice.state.value.sendError != null || alice.state.value.error != null
            }
            noError(alice, bob); assertNull(alice.state.value.sendError)
            assertTrue(bob.state.value.messages.any { it.text == text })
            val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
            until("Incoming message creates an Android notification") { notificationManager.activeNotifications.any { it.tag == "message:$id" } }
            until("Both clients report actual successful sync") { alice.state.value.connection == ConnectionStatus.CONNECTED && bob.state.value.connection == ConnectionStatus.CONNECTED }
            try {
                shell("svc wifi disable"); shell("svc data disable")
                until("No network is shown as offline") { bob.state.value.connection == ConnectionStatus.OFFLINE }
            } finally { shell("svc wifi enable"); shell("svc data enable") }
            until("Network restoration reconnects without app restart") { bob.state.value.connection == ConnectionStatus.CONNECTED && alice.state.value.connection == ConnectionStatus.CONNECTED }
            bob.setForeground(true)
            val resumed = "after-network-" + UUID.randomUUID()
            alice.sendText(resumed)
            until("Live incoming event after reconnect") { bob.state.value.messages.any { it.text == resumed } }
            noError(alice, bob)
            val reply = "reply-" + UUID.randomUUID()
            bob.sendText(reply)
            until("Alice receives the reply") { alice.state.value.messages.any { it.text == reply && !it.own } || bob.state.value.error != null || bob.state.value.sendError != null }
            noError(alice, bob); assertNull(bob.state.value.sendError)
            assertEquals(listOf(id), alice.state.value.rooms.map { it.id })
            assertEquals(listOf(id), bob.state.value.rooms.map { it.id })
            // History is server-backed: the first device and its keys are unnecessary.
            val picture = File(leftContext.cacheDir, "cloud-picture.png")
            val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            picture.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val imageBytes = picture.readBytes()
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "cloud-$suffix.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PrivateMessengerTest")
            }
            val imageUri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            requireNotNull(context.contentResolver.openOutputStream(imageUri)).use { it.write(imageBytes) }
            alice.sendPicked(imageUri)
            until("Photo is persisted remotely") { bob.state.value.messages.any { it.attachment?.kind == "image" } }
            noError(alice, bob)
            until("Upload action completes") { !alice.state.value.busy }
            context.contentResolver.delete(imageUri, null, null)
            alice.setAvatar(android.net.Uri.fromFile(picture))
            until("Profile photo is archived") { !alice.state.value.busy && alice.state.value.avatarUrl != null }
            noError(alice)
            picture.delete()
            bob.logout()
            until("Bob logs out") { bob.state.value.userId == null && !bob.state.value.busy }
            bob.authenticate(BuildConfig.DEFAULT_SERVER, "private_" + bobName, "", password, false)
            until("New Bob device signs in") { bob.state.value.userId != null && !bob.state.value.busy }
            noError(bob)
            bob.findAndChat(aliceName)
            until("Re-login reuses the canonical room") { bob.state.value.roomId == id && !bob.state.value.busy }
            until("History loads on a fresh device") { bob.state.value.messages.any { it.text == text } && bob.state.value.messages.any { it.text == reply } }
            until("Photo history on a new device") { bob.state.value.messages.any { it.attachment?.kind == "image" } }
            val receivedPhoto = requireNotNull(bob.state.value.messages.first { it.attachment?.kind == "image" }.attachment)
            assertArrayEquals(imageBytes, bob.download(receivedPhoto).readBytes())
            val fresh = "fresh-device-" + UUID.randomUUID()
            bob.sendText(fresh)
            until("The restored device can send") { alice.state.value.messages.any { it.text == fresh } || bob.state.value.error != null || bob.state.value.sendError != null }
            noError(alice, bob); assertNull(bob.state.value.sendError)
            assertFalse(bob.diagnostics.export().contains(password))
            assertFalse(bob.diagnostics.export().contains(fresh))
            val token = requireNotNull(Vault(rightContext).load()).getString("access_token")
            assertFalse(bob.diagnostics.export().contains(token))
            secondBob.authenticate(BuildConfig.DEFAULT_SERVER, "private_" + bobName, "", password, false)
            until("Another phone signs in with the same password") { secondBob.state.value.userId != null && !secondBob.state.value.busy }
            noError(secondBob)
            secondBob.findAndChat(aliceName)
            until("Same conversation on the other phone") { secondBob.state.value.roomId == id && !secondBob.state.value.busy }
            until("Concurrent phone sees the existing history") {
                secondBob.state.value.messages.any { it.text == text } && secondBob.state.value.messages.any { it.attachment?.kind == "image" }
            }
            assertArrayEquals(imageBytes, secondBob.download(requireNotNull(secondBob.state.value.messages.first { it.attachment?.kind == "image" }.attachment)).readBytes())
            alice.saveProfileDetails("На своей волне", "flower")
            until("Public profile changes commit") { !alice.state.value.busy }
            noError(alice)
            val peerProfile = secondBob.userProfile(requireNotNull(alice.state.value.userId), true)
            assertEquals("На своей волне", peerProfile.status)
            assertEquals("flower", peerProfile.shape)
            assertTrue("Avatar history is available to the peer", peerProfile.photos.isNotEmpty())
            val original = bob.state.value.messages.first { it.text == text }
            bob.messagePreference(id, listOf(original.id), "hidden")
            until("Selective deletion is synchronized across account devices") {
                bob.state.value.messages.none { it.id == original.id } && secondBob.state.value.messages.none { it.id == original.id } && !bob.state.value.busy
            }
            noError(bob)
            assertTrue("Other participant retains the message", alice.state.value.messages.any { it.id == original.id })
            bob.messagePreference(id, listOf(original.id), "hidden", false)
            until("Undo restores on both devices") { bob.state.value.messages.any { it.id == original.id } && secondBob.state.value.messages.any { it.id == original.id } && !bob.state.value.busy }
            bob.messagePreference(id, listOf(original.id), "starred")
            until("Stars synchronize") { secondBob.state.value.messages.any { it.id == original.id && it.starred } && !bob.state.value.busy }
            val replyText = "reply-relation-" + UUID.randomUUID()
            bob.sendText(replyText, original)
            until("Real reply relation reaches the other client") { alice.state.value.messages.any { it.text == replyText && it.replyToId == original.id } }
            bob.forwardMessages(id, listOf(original.id), id)
            until("Forward uses a new event from the forwarding user") { alice.state.value.messages.any { it.text == text && !it.own } && !bob.state.value.busy }
            noError(alice, bob)
            bob.deleteForEveryone(id, listOf(original.id))
            until("Deleting another sender's message is rejected") { !bob.state.value.busy }
            assertNotNull("An incoming event cannot be globally redacted through the repository", bob.state.value.error)
            assertTrue(alice.state.value.messages.any { it.id == original.id })
            alice.deleteForEveryone(id, listOf(original.id))
            until("Own redaction is synchronized to both peers") { alice.state.value.messages.none { it.id == original.id } && bob.state.value.messages.none { it.id == original.id } && !alice.state.value.busy }
            noError(alice)
            bob.deleteCurrentChat()
            until("Delete removes the chat from the account list") { bob.state.value.roomId == null && bob.state.value.rooms.isEmpty() && !bob.state.value.busy }
            noError(bob)
            until("Deletion closes the open conversation on the other phone") { secondBob.state.value.roomId == null && secondBob.state.value.rooms.isEmpty() }
            val afterDelete = "after-delete-" + UUID.randomUUID()
            alice.sendText(afterDelete)
            until("A new incoming message restores the same chat") { bob.state.value.rooms.any { it.id == id } }
            bob.findAndChat(aliceName)
            until("Open restored chat") { bob.state.value.roomId == id && !bob.state.value.busy }
            until("New message appears") { bob.state.value.messages.any { it.text == afterDelete } }
            assertFalse("Deleted history remains hidden", bob.state.value.messages.any { it.text == text || it.text == reply })
        } finally {
            alice.logout(); bob.logout(); secondBob.logout()
            until("Test accounts close") { alice.state.value.userId == null && bob.state.value.userId == null && secondBob.state.value.userId == null && !alice.state.value.busy && !bob.state.value.busy && !secondBob.state.value.busy }
        }
    }

    @Test fun collapseKeepsOldRoomsAccessibleWithoutDeletingHistory() {
        val rows = listOf(ChatRoom("!old", "Alice", false, "@alice:ci.invalid"),
            ChatRoom("!new", "Alice", true, "@alice:ci.invalid"), ChatRoom("!unknown", "Other", true))
        val result = collapseDirectChats(rows, mapOf("@alice:ci.invalid" to "!new"))
        assertEquals(2, result.size)
        val primary = result.single { it.peerId == "@alice:ci.invalid" }
        assertEquals("!new", primary.id)
        assertEquals(listOf("!old"), primary.alternateIds)
    }
}
