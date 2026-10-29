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

    @Test fun firstContactBothDirectionsUsesOneRoomAndDeliversWithoutManualSas() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
            assertTrue(alice.state.value.cryptoReady && alice.state.value.verified)
            assertTrue(bob.state.value.cryptoReady && bob.state.value.verified)
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
            until("Bob decrypts the first message without manual SAS") {
                bob.state.value.messages.any { it.text == text && !it.own } || alice.state.value.sendError != null || alice.state.value.error != null
            }
            noError(alice, bob); assertNull(alice.state.value.sendError)
            assertTrue(bob.state.value.messages.any { it.text == text })
            val reply = "reply-" + UUID.randomUUID()
            bob.sendText(reply)
            until("Alice receives the reply") { alice.state.value.messages.any { it.text == reply && !it.own } || bob.state.value.error != null || bob.state.value.sendError != null }
            noError(alice, bob); assertNull(bob.state.value.sendError)
            assertEquals(listOf(id), alice.state.value.rooms.map { it.id })
            assertEquals(listOf(id), bob.state.value.rooms.map { it.id })
            until("Both sides back up their keys before reinstall") { alice.state.value.backupStatus == "ready" && bob.state.value.backupStatus == "ready" }
            // A fresh native login must recover keys using the human password, not a manually copied key.
            bob.logout()
            until("Bob logs out") { bob.state.value.userId == null && !bob.state.value.busy }
            bob.authenticate(BuildConfig.DEFAULT_SERVER, "private_" + bobName, "", password, false)
            until("New Bob device signs in") { bob.state.value.userId != null && !bob.state.value.busy }
            noError(bob)
            assertTrue("Password recovery must verify the new device without resetting the identity", bob.state.value.verified)
            assertTrue(bob.state.value.managedRecovery)
            bob.findAndChat(aliceName)
            until("Re-login reuses the canonical room") { bob.state.value.roomId == id && !bob.state.value.busy }
            until("History decrypts on a fresh device") { bob.state.value.messages.any { it.text == text } && bob.state.value.messages.any { it.text == reply } }
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
            noError(secondBob); assertTrue(secondBob.state.value.verified)
            secondBob.findAndChat(aliceName)
            until("Same conversation on the other phone") { secondBob.state.value.roomId == id && !secondBob.state.value.busy }
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
