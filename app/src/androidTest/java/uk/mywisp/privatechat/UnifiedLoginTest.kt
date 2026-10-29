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
class UnifiedLoginTest {
    private class Isolated(base: Context, private val id: String) : ContextWrapper(base) {
        override fun getFilesDir() = File(super.getFilesDir(), id).apply { mkdirs() }
        override fun getCacheDir() = File(super.getCacheDir(), id).apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(id + name, mode)
    }
    private suspend fun idle(repo: MessengerRepository) {
        withTimeout(120_000) { while (repo.state.value.loading || repo.state.value.busy) delay(100) }
    }
    @Test fun existingPasswordDerivativeRemainsCompatible() {
        PasswordSecrets("Test-password-123", PasswordSecrets.encode(ByteArray(32)), "ci.invalid", "alice").use {
            assertEquals("pm2_n18Yqz07LuZnttPV6hqUrPoo3Dvfb5DDiAESp6BRwRA", it.authentication)
        }
    }
    @Test fun oneEntryPointAcceptsExistingPasswordsAndRejectsWrongPasswords() = runBlocking(Dispatchers.IO) {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val login = "unified_" + suffix
        val password = "Test-" + UUID.randomUUID()
        val base = BuildConfig.DEFAULT_SERVER
        val created = Gateway.call(base, "/api/register", JSONObject().put("login", login).put("username", login).put("password", password))
        val repo = MessengerRepository(Isolated(InstrumentationRegistry.getInstrumentation().targetContext, suffix))
        try {
            idle(repo)
            repo.authenticate(base, login, "", "wrong-password", false)
            idle(repo)
            assertNull(repo.state.value.userId)
            assertNotNull(repo.state.value.error)
            repo.authenticate(base, login, "", password, false)
            idle(repo)
            assertNull(repo.state.value.error)
            assertEquals(created.getString("user_id"), repo.state.value.userId)
            repo.logout(); idle(repo)
            repo.authenticate(base, login, "", password, false)
            idle(repo)
            assertNull(repo.state.value.error)
            assertEquals(created.getString("user_id"), repo.state.value.userId)
        } finally { repo.logout(); idle(repo) }
    }
}
