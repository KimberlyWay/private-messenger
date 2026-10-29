package uk.mywisp.privatechat

import org.json.JSONObject

internal data class ChatVisibility(val cutoff: Long, val hidden: Boolean, val updated: Long)
internal object ChatVisibilityApi {
    const val TYPE = "uk.mywisp.chat_visibility.v1"
    fun path(user: String) = "/_matrix/client/v3/user/${Gateway.escaped(user)}/account_data/$TYPE"
    fun parse(text: String?): Map<String, ChatVisibility> {
        if (text.isNullOrBlank()) return emptyMap()
        val raw = JSONObject(text)
        val root = raw.optJSONObject("content") ?: raw
        return root.keys().asSequence().mapNotNull { id ->
            root.optJSONObject(id)?.let { id to ChatVisibility(it.optLong("cutoff"), it.optBoolean("hidden"), it.optLong("updated")) }
        }.toMap()
    }
    fun encode(items: Map<String, ChatVisibility>): JSONObject = JSONObject().also { root ->
        items.forEach { (id, item) -> root.put(id, JSONObject().put("cutoff", item.cutoff).put("hidden", item.hidden).put("updated", item.updated)) }
    }
    fun latestTime(base: String, token: String, room: String): Long {
        val filter = Gateway.escaped("{\"types\":[\"m.room.encrypted\"]}")
        val page = Gateway.call(base, "/_matrix/client/v3/rooms/${Gateway.escaped(room)}/messages?dir=b&limit=1&filter=$filter", token = token)
        return page.optJSONArray("chunk")?.optJSONObject(0)?.optLong("origin_server_ts") ?: 0L
    }
}
