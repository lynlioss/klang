package app.lynli.klang.plugins

import org.json.JSONObject
import java.net.URI
import java.util.Locale

enum class PluginType(val key: String, val label: String) { LYRICS("lyrics", "Lyrics") }

data class PluginButton(val label: String, val url: String, val icon: String)

sealed interface PluginSetting {
    val id: String
    val title: String
    val description: String
    val defaultValue: String
    data class Toggle(override val id: String, override val title: String, override val description: String,
        val default: Boolean) : PluginSetting { override val defaultValue get() = default.toString() }
    data class Range(override val id: String, override val title: String, override val description: String,
        val default: Int, val min: Int, val max: Int, val step: Int, val unit: String) : PluginSetting {
        override val defaultValue get() = default.toString()
    }
    data class Text(override val id: String, override val title: String, override val description: String,
        override val defaultValue: String, val secret: Boolean = false) : PluginSetting
    data class Choice(override val id: String, override val title: String, override val description: String,
        override val defaultValue: String, val options: List<Option>) : PluginSetting
    data class Option(val value: String, val label: String)
}

/** API 1 runs bounded HTTPS requests and declarative response mappings, without executable code. */
data class LyricsEndpoint(val url: String, val query: Map<String, String>, val headers: Map<String, String>,
    val response: LyricsResponseMapping, val condition: String = "always")
data class LyricsResponseMapping(val format: String, val records: String = "", val plain: String = "plainLyrics",
    val synced: String = "syncedLyrics", val lyricsfile: String = "lyricsfile", val instrumental: String = "instrumental",
    val title: String = "", val artist: String = "", val album: String = "", val duration: String = "",
    val durationToleranceMs: Int = 2000)
data class LyricsPluginSpec(val endpoints: List<LyricsEndpoint>, val requestIntervalMs: Int = 300)

data class PluginManifest(
    val id: String, val name: String, val version: String, val type: PluginType,
    val author: String, val summary: String, val description: String, val privacy: String,
    val features: List<String>, val buttons: List<PluginButton>, val settings: List<PluginSetting>,
    val defaultEnabled: Boolean = false, val apiVersion: Int = 1, val repository: String? = null,
    val lyrics: LyricsPluginSpec? = null,
) {
    companion object {
        const val API_VERSION = 1
        fun parse(source: String): PluginManifest {
            require(source.length <= 262_144) { "Plugin manifest is too large" }
            val json = JSONObject(source)
            require(json.optInt("apiVersion", 1) == API_VERSION) { "This plugin needs a different Klang plugin API" }
            val id = json.getString("id")
            require(id.matches(Regex("[a-z][a-z0-9._-]{0,79}"))) { "Invalid plugin ID" }
            val version = json.getString("version")
            PluginVersion.parse(version)
            fun objects(key: String) = json.optJSONArray(key)?.let { array ->
                (0 until array.length()).map { array.getJSONObject(it) }
            }.orEmpty()
            val settings = objects("settings").map { setting ->
                val key = setting.getString("id")
                require(key.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}"))) { "Invalid setting ID" }
                val title = setting.getString("title").also { require(it.isNotBlank() && it.length <= 100) }
                val description = setting.optString("description")
                when (setting.getString("type")) {
                    "boolean" -> PluginSetting.Toggle(key, title, description, setting.getBoolean("default"))
                    "range" -> PluginSetting.Range(key, title, description, setting.getInt("default"),
                        setting.getInt("min"), setting.getInt("max"), setting.getInt("step"), setting.optString("unit")).also {
                        val span = it.max.toLong() - it.min
                        require(span in 1L..100_000L && it.step > 0 && span % it.step == 0L && span / it.step <= 1000 &&
                            it.default in it.min..it.max && (it.default.toLong() - it.min) % it.step == 0L) { "Invalid setting range" }
                    }
                    "text" -> PluginSetting.Text(key, title, description, setting.optString("default"), setting.optBoolean("secret"))
                        .also { require(it.defaultValue.length <= 4096 && '\r' !in it.defaultValue && '\n' !in it.defaultValue) }
                    "choice" -> {
                        val options = setting.getJSONArray("options")
                        val values = (0 until options.length()).map { index -> options.getJSONObject(index).let {
                            PluginSetting.Option(it.getString("value"), it.getString("label"))
                        } }
                        require(values.size in 1..30 && values.distinctBy { it.value }.size == values.size)
                        PluginSetting.Choice(key, title, description, setting.getString("default"), values)
                            .also { require(values.any { option -> option.value == it.defaultValue }) }
                    }
                    else -> error("Unsupported plugin setting type")
                }
            }
            require(settings.size <= 30 && settings.distinctBy { it.id }.size == settings.size)
            require(settings.none { it.id == "cacheLyrics" && it !is PluginSetting.Toggle }) { "cacheLyrics must be a boolean setting" }
            require(settings.none { it.id == "timingOffset" && (it !is PluginSetting.Range || it.min < -30_000 || it.max > 30_000) }) {
                "timingOffset must be a range in milliseconds within -30000 to 30000"
            }
            val buttons = objects("buttons").filter { it.optBoolean("enabled", true) }.map { b ->
                PluginButton(b.getString("label"), httpsUrl(b.getString("url")), b.optString("icon", "website"))
            }.also { require(it.size <= 8) }
            val spec = json.optJSONObject("lyrics")?.let { lyrics ->
                val array = lyrics.getJSONArray("requests")
                require(array.length() in 1..4) { "A lyric plugin must define one to four requests" }
                val endpoints = (0 until array.length()).map { index ->
                    val request = array.getJSONObject(index)
                    fun map(key: String): Map<String, String> = request.optJSONObject(key)?.let { obj ->
                        obj.keys().asSequence().associateWith { obj.getString(it) }
                    }.orEmpty().also { require(it.size <= 30) }
                    val response = request.getJSONObject("response")
                    val format = response.getString("format")
                    require(format in setOf("json", "lrc", "plain", "lyricsfile")) { "Unsupported lyric response format" }
                    val headers = map("headers")
                    require(headers.keys.all { it.matches(Regex("[A-Za-z0-9-]+")) &&
                        it.lowercase(Locale.ROOT) !in setOf("host", "cookie", "content-length", "connection") }) { "Unsupported request header" }
                    val condition = request.optString("when", "always")
                    require(condition in setOf("always", "missing", "noWordTiming"))
                    LyricsEndpoint(httpsUrl(request.getString("url")).also { require('{' !in it && '}' !in it) { "Place request templates in query or headers" } }, map("query"), headers,
                        LyricsResponseMapping(format, response.optString("records"), response.optString("plain", "plainLyrics"),
                            response.optString("synced", "syncedLyrics"), response.optString("lyricsfile", "lyricsfile"),
                            response.optString("instrumental", "instrumental"), response.optString("title"), response.optString("artist"),
                            response.optString("album"), response.optString("duration"), response.optInt("durationToleranceMs", 2000)
                                .also { require(it in 0..10_000) }), condition)
                }
                val settingIds = settings.map { it.id }.toSet()
                val placeholders = Regex("\\{([^{}]+)}")
                endpoints.forEach { endpoint ->
                    (endpoint.query.values + endpoint.headers.values).forEach { template ->
                        require(template.length <= 4096)
                        placeholders.findAll(template).forEach { match ->
                            val token = match.groupValues[1]
                            require(token in setOf("title", "artist", "album", "durationSeconds", "durationMs") ||
                                token.startsWith("settings.") && token.removePrefix("settings.") in settingIds) { "Unknown request placeholder: $token" }
                        }
                    }
                }
                LyricsPluginSpec(endpoints, lyrics.optInt("requestIntervalMs", 300).also { require(it in 100..5000) })
            }
            return PluginManifest(id, json.getString("name").also { require(it.isNotBlank() && it.length <= 100) }, version,
                PluginType.entries.first { it.key == json.getString("type") }, json.getString("author"),
                json.getString("summary"), json.getString("description"), json.optString("privacy"),
                json.optJSONArray("features")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
                buttons, settings, json.optBoolean("defaultEnabled", false), API_VERSION,
                json.optString("repository").takeIf { it.isNotBlank() }?.let { GitHubPluginRepo.parse(it).url }, spec)
        }
    }
}

internal fun httpsUrl(value: String): String {
    val uri = URI(value)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null &&
        (uri.port == -1 || uri.port == 443)) { "Plugin URLs must use HTTPS" }
    return value
}

data class PluginVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<PluginVersion> {
    override fun compareTo(other: PluginVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    companion object {
        fun parse(value: String): PluginVersion {
            require(value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) { "Use a stable major.minor.patch plugin version" }
            val parts = value.split('.').map(String::toInt)
            return PluginVersion(parts[0], parts[1], parts[2])
        }
    }
}

data class GitHubPluginRepo(val owner: String, val name: String) {
    val slug get() = "$owner/$name"
    val url get() = "https://github.com/$slug"
    companion object {
        fun parse(value: String): GitHubPluginRepo {
            val uri = URI(value.trim())
            require(uri.scheme == "https" && uri.host?.lowercase() == "github.com" && uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null) {
                "Enter a GitHub repository link, such as https://github.com/owner/plugin"
            }
            val parts = uri.path.trim('/').split('/')
            require(parts.size == 2 && parts.all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it != "." && it != ".." }) { "Use the repository link, without a file or branch path" }
            return GitHubPluginRepo(parts[0].lowercase(Locale.ROOT), parts[1].removeSuffix(".git").lowercase(Locale.ROOT).also { require(it.isNotEmpty()) })
        }
    }
}

data class PluginConfiguration(val enabled: Set<String> = emptySet(), val values: Map<String, String> = emptyMap()) {
    fun value(manifest: PluginManifest, setting: PluginSetting): String = values["${manifest.id}.${setting.id}"] ?: setting.defaultValue
    fun boolean(manifest: PluginManifest, id: String): Boolean = manifest.settings.find { it.id == id }?.let { value(manifest, it).toBoolean() } ?: false
    fun integer(manifest: PluginManifest, id: String): Int = manifest.settings.find { it.id == id }?.let { value(manifest, it).toIntOrNull() } ?: 0
}
