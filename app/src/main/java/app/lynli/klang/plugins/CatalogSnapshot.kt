package app.lynli.klang.plugins

import org.json.JSONArray
import org.json.JSONObject

/** Retain already-scrolled cached pages only while their occurrence order and account still match. */
internal fun refreshedCatalogSnapshot(previous: JSONObject?, fresh: JSONObject): JSONObject {
    if (previous == null || previous.optString("accountRevision") != fresh.optString("accountRevision")) return fresh
    fun rows(page: JSONObject) = page.optJSONArray("items")?.let { rows -> (0 until rows.length()).map(rows::getJSONObject) }.orEmpty()
    fun identity(row: JSONObject) = row.optString("id") + ":" + row.optString("uid")
    val incoming = rows(fresh)
    val existing = rows(previous)
    if (existing.size <= incoming.size || existing.take(incoming.size).map(::identity) != incoming.map(::identity)) return fresh
    return JSONObject(fresh.toString()).put("items", JSONArray(incoming + existing.drop(incoming.size)))
        .put("nextOffset", previous.opt("nextOffset") ?: JSONObject.NULL)
}
