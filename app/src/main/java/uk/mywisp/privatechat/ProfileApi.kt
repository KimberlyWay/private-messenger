package uk.mywisp.privatechat

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.io.ByteArrayOutputStream

data class UserProfile(val name: String = "", val avatar: String? = null)

/** Matrix profile/media requests always go to the authenticated homeserver. */
internal object ProfileApi {
    fun get(base: String, token: String, user: String): UserProfile {
        val json = Gateway.call(base, "/_matrix/client/v3/profile/" + Gateway.escaped(user), token = token)
        return UserProfile(json.optString("displayname", ""),
            json.optString("avatar_url", "").takeIf { it.startsWith("mxc://") })
    }

    fun name(base: String, token: String, user: String, name: String) {
        Gateway.call(base, "/_matrix/client/v3/profile/${Gateway.escaped(user)}/displayname",
            JSONObject().put("displayname", name), token, "PUT")
    }

    fun avatar(base: String, token: String, user: String, mxc: String?) {
        Gateway.call(base, "/_matrix/client/v3/profile/${Gateway.escaped(user)}/avatar_url",
            JSONObject().put("avatar_url", mxc ?: JSONObject.NULL), token, "PUT")
    }

    fun upload(base: String, token: String, jpeg: ByteArray): String {
        val json = JSONObject(request(base, "/_matrix/media/v3/upload?filename=avatar.jpg", token, jpeg).toString(Charsets.UTF_8))
        return json.getString("content_uri").also { require(it.startsWith("mxc://")) }
    }

    fun download(base: String, token: String, mxc: String): ByteArray {
        val uri = URI(mxc)
        require(uri.scheme == "mxc" && !uri.rawAuthority.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        val media = uri.path.removePrefix("/")
        require(media.isNotBlank() && !media.contains('/'))
        return request(base, "/_matrix/client/v1/media/download/${Gateway.escaped(uri.rawAuthority)}/${Gateway.escaped(media)}", token)
    }

    private fun request(base: String, path: String, token: String, jpeg: ByteArray? = null): ByteArray {
        val c = URL(Gateway.validateBase(base) + path).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 20_000; c.readTimeout = 40_000
        c.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (jpeg != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "image/jpeg")
                c.setFixedLengthStreamingMode(jpeg.size)
                c.outputStream.use { it.write(jpeg) }
            }
            check(c.responseCode in 200..299) { "Не удалось загрузить фото профиля (HTTP ${c.responseCode})" }
            return c.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= 8 * 1024 * 1024) { "Фото профиля слишком большое" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
        } finally { c.disconnect() }
    }
}
