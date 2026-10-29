package uk.mywisp.privatechat

import org.json.JSONObject

internal object PasswordRecovery {
    const val TYPE = "uk.mywisp.password_recovery.v1"
    fun path(user: String) = "/_matrix/client/v3/user/${Gateway.escaped(user)}/account_data/$TYPE"
    fun envelope(secrets: PasswordSecrets, key: String, server: String, user: String): JSONObject {
        val sealed = secrets.seal(key, server, user)
        return JSONObject().put("version", 1).put("nonce", sealed[0]).put("ciphertext", sealed[1])
    }
    fun get(base: String, token: String, user: String): JSONObject? = try {
        Gateway.call(base, path(user), token = token)
    } catch (e: GatewayHttpException) { if (e.status == 404) null else throw e }
    fun open(secrets: PasswordSecrets, envelope: JSONObject, server: String, user: String): String {
        require(envelope.optInt("version") == 1) { "Неизвестная версия копии ключей. Обновите приложение." }
        return secrets.open(envelope.getString("nonce"), envelope.getString("ciphertext"), server, user)
    }
    fun validatePassword(password: String) {
        require(password.length >= 12 && password.toByteArray().size <= 72 && !password.contains('\u0000')) {
            "Пароль: минимум 12 символов и не более 72 байт UTF-8"
        }
    }
}
