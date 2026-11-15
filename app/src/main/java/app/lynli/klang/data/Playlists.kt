package app.lynli.klang.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Ordered, unique MediaStore IDs. Missing files are retained so a later rescan can find them. */
data class SavedPlaylist(
    val id: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val coverFile: String? = null,
    val lastPlayedAt: Long = 0,
) {
    fun add(ids: List<String>) = copy(trackIds = (trackIds + ids).distinct())
    fun remove(id: String) = copy(trackIds = trackIds.filterNot { it == id })
    fun move(id: String, offset: Int): SavedPlaylist {
        val index = trackIds.indexOf(id)
        val target = index + offset
        if (index < 0 || target !in trackIds.indices) return this
        return copy(trackIds = trackIds.toMutableList().apply { add(target, removeAt(index)) })
    }
    /** Reorder available songs while retaining missing/hidden IDs in their original slots. */
    fun reorder(ids: List<String>): SavedPlaylist {
        val ordered = ids.distinct().filter { it in trackIds }
        val included = ordered.toSet()
        val iterator = ordered.iterator()
        return copy(trackIds = trackIds.map { if (it in included) iterator.next() else it })
    }
}

internal object PlaylistCodec {
    fun encode(playlists: List<SavedPlaylist>): String = JSONArray().apply {
        playlists.forEach { playlist -> put(JSONObject().put("id", playlist.id).put("name", playlist.name)
            .put("tracks", JSONArray(playlist.trackIds)).put("cover", playlist.coverFile)
            .put("lastPlayedAt", playlist.lastPlayedAt)) }
    }.toString()
    fun decode(value: String): List<SavedPlaylist> {
        val array = JSONArray(value)
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val item = array.getJSONObject(i)
                val ids = item.getJSONArray("tracks")
                SavedPlaylist(item.getString("id"), item.getString("name"),
                    (0 until ids.length()).map { ids.getString(it) }.distinct(),
                    item.optString("cover").takeIf { it.isNotBlank() && it != "null" },
                    item.optLong("lastPlayedAt", 0).coerceAtLeast(0))
                    .takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
            }.getOrNull()
        }.distinctBy { it.id }
    }
}

class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("klang_playlists", Context.MODE_PRIVATE)
    fun read(): List<SavedPlaylist> = PlaylistCodec.decode(prefs.getString("items", "[]") ?: "[]")
    fun write(playlists: List<SavedPlaylist>) = prefs.edit().putString("items", PlaylistCodec.encode(playlists)).apply()
    fun create(name: String) = SavedPlaylist(UUID.randomUUID().toString(), name.trim())
}
