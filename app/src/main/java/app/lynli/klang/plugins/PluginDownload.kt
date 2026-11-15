package app.lynli.klang.plugins

import app.lynli.klang.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Install only a published stable release, never a mutable default branch or a repository archive. */
class PluginDownload {
    suspend fun latest(repo: GitHubPluginRepo, newerThan: String? = null): PluginCandidate? = runInterruptible(Dispatchers.IO) {
        val release = JSONObject(get("https://api.github.com/repos/${repo.slug}/releases/latest", 1_000_000))
        require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "Publish a stable plugin release first" }
        val version = release.getString("tag_name").removePrefix("v")
        val parsed = PluginVersion.parse(version)
        // Most launches only need release metadata. Download the manifest when there is an update.
        if (newerThan != null && parsed <= PluginVersion.parse(newerThan)) return@runInterruptible null
        val assets = release.getJSONArray("assets")
        fun asset(name: String): JSONObject = (0 until assets.length()).map(assets::getJSONObject)
            .firstOrNull { it.getString("name") == name } ?: throw IOException("The latest release needs $name")
        fun url(asset: JSONObject): String = httpsUrl(asset.getString("browser_download_url")).also {
            val uri = URI(it)
            require(uri.host == "github.com" && uri.path.lowercase(java.util.Locale.ROOT)
                .startsWith("/${repo.slug}/releases/download/")) { "Unexpected plugin download location" }
        }
        val source = get(url(asset("plugin.json")), 262_144)
        val checksum = get(url(asset("plugin.json.sha256")), 256).trim().split(Regex("\\s+")).first()
        require(checksum.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid plugin checksum" }
        val actual = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
        require(actual.equals(checksum, ignoreCase = true)) { "Plugin download failed its checksum check" }
        val manifest = PluginManifest.parse(source)
        require(manifest.lyrics != null) { "This release does not define a lyric provider" }
        require(manifest.repository == repo.url) { "The plugin repository does not match this link" }
        require(release.getString("tag_name") == "v${manifest.version}") { "The release tag must match the plugin version" }
        PluginCandidate(manifest, repo, source, httpsUrl(release.getString("html_url")))
    }
    private fun get(url: String, limit: Int): String {
        var current = url
        repeat(6) {
            httpsUrl(current)
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", if (current.contains("api.github.com")) "application/vnd.github+json" else "application/octet-stream")
                connection.setRequestProperty("User-Agent", "Klang/${BuildConfig.VERSION_NAME}")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val next = URI(current).resolve(connection.getHeaderField("Location") ?: throw IOException("Invalid download redirect"))
                    require(next.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "Unexpected plugin download redirect" }
                    current = httpsUrl(next.toString())
                } else {
                    if (status == 404) throw IOException("No stable plugin release found. Check the repository link.")
                    if (status == 403 || status == 429) throw IOException("GitHub is limiting requests. Try again later.")
                    if (status !in 200..299) throw IOException("Couldn't download the plugin ($status)")
                    return connection.inputStream.use { input ->
                        val bytes = input.readNBytes(limit + 1)
                        if (bytes.size > limit) throw IOException("Plugin download is too large")
                        bytes.toString(Charsets.UTF_8)
                    }
                }
            } finally { connection.disconnect() }
        }
        throw IOException("Too many plugin download redirects")
    }
}
