package uk.mywisp.privatechat

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

object Gateway {
    fun validateBase(input: String): String {
        val uri = URI(input.trim())
        require(uri.scheme == "https" || (BuildConfig.ALLOW_TEST_HTTP && uri.scheme == "http")) { "Укажите HTTPS-адрес сервера" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty() in listOf("", "/")) {
            "Нужен адрес без пути, например https://chat.example.com:8443"
        }
        return input.trim().trimEnd('/')
    }

    fun call(base: String, path: String, body: JSONObject? = null, token: String? = null,
             method: String = if (body != null) "POST" else "GET"): JSONObject {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000
        connection.readTimeout = 40_000
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            check(status in 200..299) { json.optString("detail", "Сервер вернул HTTP $status") }
            return json
        } finally { connection.disconnect() }
    }

    fun escaped(value: String): String = URLEncoder.encode(value, "UTF-8")
}
