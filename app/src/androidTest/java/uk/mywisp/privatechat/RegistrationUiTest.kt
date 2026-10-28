package uk.mywisp.privatechat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Checks the actual registration screen and repository, not just the HTTP API. */
@RunWith(AndroidJUnit4::class)
class RegistrationUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun node(label: String): SemanticsNodeInteraction {
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        return ui.onNodeWithText(label)
    }


    @Test fun chosenPrivateLoginAndPublicUsernameCreateARealAccount() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val username = "ui_$suffix"
        val repo = (ui.activity.application as ChatApplication).repository
        try {
            ui.waitUntil(30_000) { !repo.state.value.loading }
            ui.onNodeWithText("Адрес сервера").assertDoesNotExist()
            node("Создать аккаунт").performClick()
            ui.onNodeWithText("Адрес сервера").assertDoesNotExist()
            node("Логин для входа").performTextInput("private_ui_$suffix")
            node("Публичный @username").performTextInput(username)
            node("Пароль").performTextInput("Test-" + UUID.randomUUID())
            node("Зарегистрироваться").performClick()
            ui.waitUntil(60_000) { !repo.state.value.busy && repo.state.value.userId != null }
            ui.onNodeWithText("Начните первую переписку").assertIsDisplayed()
            ui.onNodeWithContentDescription("Настройки").performClick()
            node("@$username").assertIsDisplayed()
            check(Vault(ui.activity).load()?.getString("user_id") == "@$username:ci.invalid") {
                "The native SDK session must be saved after registration"
            }
            val chosenName = "Профиль $suffix"
            node("Отображаемое имя").performTextReplacement(chosenName)
            node("Сохранить имя").performClick()
            ui.waitUntil(30_000) { repo.state.value.displayName == chosenName && !repo.state.value.busy }
            val session = requireNotNull(Vault(ui.activity).load())
            val profile = ProfileApi.get(BuildConfig.DEFAULT_SERVER, session.getString("access_token"), session.getString("user_id"))
            check(profile.name == chosenName) { "Display name must be stored on the real server" }

            val photo = File(ui.activity.cacheDir, "avatar-test.png")
            val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            try {
                photo.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                repo.setAvatar(Uri.fromFile(photo))
                ui.waitUntil(30_000) { repo.state.value.avatarUrl != null && !repo.state.value.busy }
                val remote = ProfileApi.get(BuildConfig.DEFAULT_SERVER, session.getString("access_token"), session.getString("user_id"))
                check(remote.avatar == repo.state.value.avatarUrl) { "Avatar must be published on the server" }
                val file = requireNotNull(runBlocking { repo.profilePhoto(session.getString("user_id")) })
                val decoded = requireNotNull(BitmapFactory.decodeFile(file.path))
                check(decoded.width == decoded.height && decoded.width <= 512)
                decoded.recycle()
                repo.removeAvatar()
                ui.waitUntil(30_000) { repo.state.value.avatarUrl == null && !repo.state.value.busy }
                check(ProfileApi.get(BuildConfig.DEFAULT_SERVER, session.getString("access_token"), session.getString("user_id")).avatar == null)
            } finally { photo.delete(); bitmap.recycle() }

            node("Тёмная").performClick()
            node("Океан").performClick()
            check(AppearancePreferences(ui.activity).state.value == Appearance("dark", "ocean")) {
                "Appearance must survive reopening the preferences"
            }
        } finally {
            repo.appearance.update(theme = "system", accent = "dynamic")
            repo.logout()
            ui.waitUntil(30_000) { repo.state.value.userId == null && !repo.state.value.busy }
        }
    }
}
