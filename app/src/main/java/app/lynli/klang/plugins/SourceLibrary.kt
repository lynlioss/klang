package app.lynli.klang.plugins

import app.lynli.klang.data.Track
import org.json.JSONObject

/** Source entities use the same collection surfaces as device albums and playlists. */
data class SourceCollection(val providerId: String, val id: String, val kind: String,
    val title: String, val subtitle: String, val artwork: String?, val total: Int,
    val section: String, val payload: String) {
    val identity: String get() = MusicSourceProtocol.identity(providerId, id)
    val editable: Boolean get() = JSONObject(payload).optBoolean("editable")
}

data class SourceConnection(val providerId: String, val name: String, val badge: String,
    val connected: Boolean, val profile: String? = null, val notice: String? = null)

internal data class SourceLibrary(val tracks: List<Track> = emptyList(),
    val favorites: Set<String> = emptySet(), val collections: List<SourceCollection> = emptyList(),
    val home: List<SourceCollection> = emptyList(), val connections: List<SourceConnection> = emptyList(),
    val syncing: Boolean = false)

internal data class SourceSearchPage(val tracks: List<Track>, val collections: List<SourceCollection>,
    val nextOffsets: Map<String, Int>, val notices: List<String>)

internal fun sourceCollection(providerId: String, item: JSONObject) = SourceCollection(providerId,
    item.getString("id"), item.getString("kind"), item.getString("title"), item.optString("subtitle"),
    item.optString("artwork").takeIf { it.startsWith("https://") }, item.optInt("total"),
    item.optString("section"), item.toString())
