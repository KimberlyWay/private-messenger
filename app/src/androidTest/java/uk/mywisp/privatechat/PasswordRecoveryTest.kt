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

@RunWith(AndroidJUnit4::class)
class PasswordRecoveryTest {
    private suspend fun until(label: String, test: () -> Boolean) {
        try { withTimeout(120_000) { while (!test()) delay(100) } }
        catch (e: TimeoutCancellationException) { throw AssertionError(label, e) }
    }
    private class Isolated(base: Context, private val id: String) : ContextWrapper(base) {
        override fun getFilesDir() = File(super.getFilesDir(), id).apply { mkdirs() }
        override fun getCacheDir() = File(super.getCacheDir(), id).apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(id + "_" + name, mode)
    }
    @Test fun standardKdfVectorAndAuthenticatedEnvelopeAreCompatibleWithAndroid() {
        PasswordSecrets("Test-password-123", PasswordSecrets.encode(ByteArray(32)), "ci.invalid", "alice").use { secrets ->
            assertEquals("pm2_n18Yqz07LuZnttPV6hqUrPoo3Dvfb5DDiAESp6BRwRA", secrets.authentication)
            val envelope = secrets.seal("synthetic key", "ci.invalid", "@alice:ci.invalid")
            assertEquals("synthetic key", secrets.open(envelope[0], envelope[1], "ci.invalid", "@alice:ci.invalid"))
            try { secrets.open(envelope[0], envelope[1], "ci.invalid", "@bob:ci.invalid"); fail("Cross-account substitution accepted") }
            catch (_: javax.crypto.AEADBadTagException) {}
        }
    }
    @Test fun unicodePasswordsUseTheSameUtf8Kdf() {
        PasswordSecrets("Пароль-длинный-123🔮", PasswordSecrets.encode(ByteArray(32)), "ci.invalid", "alice").use {
            assertEquals("pm2_eIRR9s6ae1YJB4A6ZdFO9jm-W9cumUg937Yr-IbkOTQ", it.authentication)
        }
    }
    @Test fun legacyAccountMigratesWithoutDiscardingIdentityAndCanLoginWithNewPassword() = runBlocking(Dispatchers.IO) {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val login = "legacy_" + suffix
        val username = "legacyuser_" + suffix
        val oldPassword = "Old-" + UUID.randomUUID()
        val newPassword = "New-" + UUID.randomUUID()
        val base = BuildConfig.DEFAULT_SERVER
        Gateway.call(base, "/api/register", JSONObject().put("login", login).put("username", username).put("password", oldPassword))
        val context = Isolated(InstrumentationRegistry.getInstrumentation().targetContext, suffix)
        val repo = MessengerRepository(context)
        try {
            until("Initial state") { !repo.state.value.loading }
            repo.authenticateLegacy(base, login, oldPassword)
            until("Legacy native session") { repo.state.value.userId != null && !repo.state.value.busy }
            assertNull(repo.state.value.error); assertTrue(repo.state.value.verified)
            val before = requireNotNull(Vault(context).load()).getString("user_id")
            repo.migratePasswordRecovery(oldPassword, newPassword, "", false)
            until("Migrate protection") { !repo.state.value.busy && (repo.state.value.managedRecovery || repo.state.value.error != null) }
            assertNull(repo.state.value.error); assertTrue(repo.state.value.managedRecovery)
            repo.logout()
            until("Logout") { repo.state.value.userId == null && !repo.state.value.busy }
            repo.authenticate(base, login, "", newPassword, false)
            until("Password-only re-login") { repo.state.value.userId != null && !repo.state.value.busy }
            assertNull(repo.state.value.error); assertTrue(repo.state.value.verified); assertTrue(repo.state.value.managedRecovery)
            assertEquals(before, repo.state.value.userId)
        } finally {
            repo.logout(); until("Close") { repo.state.value.userId == null && !repo.state.value.busy }
        }
    }
    @Test fun lostLegacyKeysRequireExplicitConsentBeforeIdentityReset() = runBlocking(Dispatchers.IO) {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val login = "lost_" + suffix
        val username = "lostuser_" + suffix
        val oldPassword = "Old-" + UUID.randomUUID()
        val newPassword = "New-" + UUID.randomUUID()
        val base = BuildConfig.DEFAULT_SERVER
        Gateway.call(base, "/api/register", JSONObject().put("login", login).put("username", username).put("password", oldPassword))
        val context = Isolated(InstrumentationRegistry.getInstrumentation().targetContext, suffix)
        val repo = MessengerRepository(context)
        fun master(): String {
            val data = requireNotNull(Vault(context).load())
            val own = data.getString("user_id")
            return Gateway.call(base, "/_matrix/client/v3/keys/query", JSONObject().put("device_keys", JSONObject().put(own, org.json.JSONArray())), data.getString("access_token"))
                .getJSONObject("master_keys").getJSONObject(own).getJSONObject("keys").toString()
        }
        try {
            until("Start lost-keys test") { !repo.state.value.loading }
            repo.authenticateLegacy(base, login, oldPassword)
            until("First legacy device") { repo.state.value.userId != null && !repo.state.value.busy }
            assertNull(repo.state.value.error); assertTrue(repo.state.value.verified)
            val original = master()
            repo.logout(); until("Drop the original local session") { repo.state.value.userId == null && !repo.state.value.busy }
            repo.authenticateLegacy(base, login, oldPassword)
            until("Fresh legacy login") { repo.state.value.userId != null && !repo.state.value.busy }
            assertNull(repo.state.value.error); assertFalse(repo.state.value.verified)
            repo.migratePasswordRecovery(oldPassword, newPassword, "", false)
            until("Reset is blocked without consent") { repo.state.value.error != null && !repo.state.value.busy }
            assertEquals("Identity must remain unchanged without explicit consent", original, master())
            repo.migratePasswordRecovery(oldPassword, newPassword, "", true)
            until("Explicitly replace lost protection") { !repo.state.value.busy && (repo.state.value.managedRecovery || repo.state.value.error != null) }
            assertNull(repo.state.value.error); assertTrue(repo.state.value.verified); assertTrue(repo.state.value.managedRecovery)
            assertNotEquals(original, master())
            repo.logout(); until("Close replaced identity") { repo.state.value.userId == null && !repo.state.value.busy }
            repo.authenticate(base, login, "", newPassword, false)
            until("Restore new protection on another login") { repo.state.value.userId != null && !repo.state.value.busy }
            assertNull(repo.state.value.error); assertTrue(repo.state.value.verified)
        } finally {
            repo.logout(); until("Close lost-keys test") { repo.state.value.userId == null && !repo.state.value.busy }
        }
    }

}
