package app.lynli.klang.plugins

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** API 1 validation shared by source browsing and Media3 playback. */
internal object MusicSourceProtocol {
    fun identity(providerId: String, entityId: String): String {
        require(providerId.matches(Regex("[a-z][a-z0-9._-]{0,79}")))
        require(entityId.length in 1..1024)
        val path = URLEncoder.encode(entityId, StandardCharsets.UTF_8).replace("+", "%20")
        return "klang-source://$providerId/$path"
    }
    fun entityId(identity: String): String {
        val uri = URI(identity)
        require(uri.scheme == "klang-source" && uri.userInfo == null && uri.query == null && uri.fragment == null)
        return URLDecoder.decode(uri.rawPath.removePrefix("/"), StandardCharsets.UTF_8)
    }
    fun page(result: JSONObject): JSONObject {
        val items = result.getJSONArray("items")
        require(items.length() <= 1000) { "The source returned too many items in one page." }
        val offset = result.getInt("offset"); val total = result.getInt("total")
        require(offset in 0..100_000 && total in 0..1_000_000)
        if (!result.isNull("nextOffset")) require(result.getInt("nextOffset") > offset && result.getInt("nextOffset") < total) {
            "The source returned invalid pagination."
        }
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            require(item.getString("kind") in setOf("track", "album", "artist", "playlist", "folder"))
            require(item.getString("id").length in 1..1024 && item.getString("title").length in 1..1000)
        }
        return result
    }
    fun stream(result: JSONObject): Pair<String, Map<String, String>> {
        val url = result.getString("url"); val uri = URI(url)
        require(url.length <= 16_384 && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "The source returned an invalid stream address."
        }
        val json = result.optJSONObject("headers")
        val headers = json?.keys()?.asSequence()?.associateWith { json.getString(it) }.orEmpty()
        require(headers.size <= 20 && headers.all { (key, value) -> key.matches(Regex("[A-Za-z0-9-]+")) &&
            key.lowercase() !in setOf("host", "content-length", "connection") && value.length <= 8192 && '\r' !in value && '\n' !in value }) {
            "The source returned invalid playback headers."
        }
        return url to headers
    }
}
