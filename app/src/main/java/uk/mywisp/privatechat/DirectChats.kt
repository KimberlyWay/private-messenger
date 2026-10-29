package uk.mywisp.privatechat

import org.json.JSONArray
import org.json.JSONObject

/** Collapse only known personal peers; unknown rooms retain separate rows. No room is deleted. */
internal fun collapseDirectChats(rooms: List<ChatRoom>, canonical: Map<String, String>): List<ChatRoom> =
    rooms.groupBy { it.peerId ?: it.id }.values.map { group ->
        val peer = group.first().peerId
        val chosen = group.firstOrNull { it.id == peer?.let { canonical[it] } }
            ?: group.sortedWith(compareBy<ChatRoom> { it.invited }.thenBy { it.id }).first()
        chosen.copy(alternateIds = group.map { it.id }.filter { it != chosen.id }.sorted())
    }.sortedBy { it.name.lowercase() }

internal object DirectChats {
    fun resolve(base: String, token: String, username: String, candidates: List<String>): JSONObject = try {
        Gateway.call(base, "/api/chats", JSONObject().put("username", username)
            .put("candidate_room_ids", JSONArray(candidates.take(8))), token)
    } catch (e: GatewayHttpException) {
        if (e.status == 404) error("Обновите сервер мессенджера: для личных чатов нужен новый шлюз. Новая комната не создана.")
        throw e
    }

    fun markDirect(base: String, token: String, own: String, peer: String, roomId: String) {
        val path = "/_matrix/client/v3/user/${Gateway.escaped(own)}/account_data/m.direct"
        val data = try { Gateway.call(base, path, token = token) }
        catch (e: GatewayHttpException) { if (e.status == 404) JSONObject() else throw e }
        val previous = data.optJSONArray(peer)
        val ids = mutableListOf(roomId)
        if (previous != null) for (i in 0 until previous.length()) {
            previous.optString(i).takeIf { it.isNotBlank() && it !in ids }?.let(ids::add)
        }
        data.put(peer, JSONArray(ids))
        Gateway.call(base, path, data, token, "PUT")
    }
}
