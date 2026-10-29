package uk.mywisp.privatechat

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Render the actual Compose screens, including the separate dialog window. */
@RunWith(AndroidJUnit4::class)
class LeanDesignTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        ui.waitForIdle()
        Thread.sleep(500)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val resolver = instrumentation.targetContext.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lean-design")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "design").apply { mkdirs() }
                File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }
    @Test fun renderLightDarkChatSelectionSettingsAndLogin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS").use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val repo = (ui.activity.application as ChatApplication).repository
        ui.waitUntil(30_000) { !repo.state.value.loading }
        for (theme in listOf("light", "dark")) {
            ui.runOnUiThread { ui.activity.setContent { key(theme) { PrivateTheme(Appearance(theme = theme, accent = "lavender")) { LeanAuthentication(repo, ChatState(loading = false)) } } } }
            ui.onNodeWithText("Логин для входа").performTextInput("alex.name")
            ui.onNodeWithText("Пароль").performTextInput("Sample-password-123")
            capture("login_$theme")
        }
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        try {
            repo.authenticate(BuildConfig.DEFAULT_SERVER, "design_$suffix", "design_$suffix", "Test-" + UUID.randomUUID(), true)
            ui.waitUntil(120_000) { repo.state.value.userId != null && !repo.state.value.busy }
            check(repo.state.value.error == null)
            repo.saveProfile("Алекс")
            ui.waitUntil(30_000) { !repo.state.value.busy }
            repo.saveProfileDetails("На своей волне 🌊", "flower")
            ui.waitUntil(30_000) { !repo.state.value.busy }
            check(repo.state.value.error == null)
            val now = System.currentTimeMillis()
            val state = repo.state.value.copy(connection = ConnectionStatus.CONNECTED, roomId = "!preview:ci.invalid", roomName = "Алекс", peerId = repo.state.value.userId,
                messages = listOf(
                    ChatMessage("\$preview1", "@alex:ci.invalid", false, now - 120_000, "Давай встретимся у воды 🌊"),
                    ChatMessage("\$preview2", repo.state.value.userId!!, true, now - 60_000, "Давай! В 18:30 подойдёт?", read = true),
                    ChatMessage("\$preview3", "@alex:ci.invalid", false, now, "Да, до встречи!")))
            for (theme in listOf("light", "dark")) {
                ui.runOnUiThread { ui.activity.setContent { key("chat:$theme") { PrivateTheme(Appearance(theme = theme, accent = "lavender")) { LeanContent(repo, ui.activity, state) } } } }
                capture("chat_$theme")
                ui.onNodeWithText("Давай! В 18:30 подойдёт?").performTouchInput { longClick() }
                ui.onNodeWithText("Удалить").assertExists()
                capture("message_menu_$theme")
                ui.runOnUiThread { ui.activity.setContent { key("settings:$theme") { PrivateTheme(Appearance(theme = theme, accent = "lavender")) { LeanContent(repo, ui.activity, state.copy(roomId = null, messages = emptyList())) } } } }
                ui.onNodeWithContentDescription("Настройки").performClick()
                capture("settings_$theme")
                ui.onNodeWithText("Фото, имя и личный статус").performClick()
                capture("profile_$theme")
            }
        } finally {
            repo.logout()
            ui.waitUntil(60_000) { !repo.state.value.busy && repo.state.value.userId == null }
        }
    }
}
