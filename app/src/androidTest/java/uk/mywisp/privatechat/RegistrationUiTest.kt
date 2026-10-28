package uk.mywisp.privatechat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Checks the actual registration screen and repository, not just the HTTP API. */
@RunWith(AndroidJUnit4::class)
class RegistrationUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun chosenPrivateLoginAndPublicUsernameCreateARealAccount() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val username = "ui_$suffix"
        val repo = (ui.activity.application as ChatApplication).repository
        try {
            ui.waitUntil(30_000) { !repo.state.value.loading }
            ui.onNodeWithText("Создать аккаунт").performScrollTo().performClick()
            ui.onNodeWithText("Адрес сервера").performScrollTo().performTextInput("http://10.0.2.2:8088")
            ui.onNodeWithText("Логин для входа").performScrollTo().performTextInput("private_ui_$suffix")
            ui.onNodeWithText("Публичный @username").performScrollTo().performTextInput(username)
            ui.onNodeWithText("Пароль").performScrollTo().performTextInput("Test-" + UUID.randomUUID())
            ui.onNodeWithText("Зарегистрироваться").performScrollTo().performClick()
            ui.waitUntil(60_000) { !repo.state.value.busy && repo.state.value.userId != null }
            ui.onNodeWithText("Начните первую переписку").assertIsDisplayed()
            ui.onNodeWithContentDescription("Настройки").performClick()
            ui.onNodeWithText("@$username:ci.invalid").assertIsDisplayed()
            check(Vault(ui.activity).load()?.getString("user_id") == "@$username:ci.invalid") {
                "The native SDK session must be saved after registration"
            }
        } finally {
            repo.logout()
            ui.waitUntil(30_000) { repo.state.value.userId == null && !repo.state.value.busy }
        }
    }
}
