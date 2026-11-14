package app.lynli.klang.plugins

import org.json.JSONObject

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
}

data class PluginManifest(
    val id: String, val name: String, val version: String, val type: PluginType,
    val author: String, val summary: String, val description: String, val privacy: String,
    val features: List<String>, val buttons: List<PluginButton>, val settings: List<PluginSetting>,
    val defaultEnabled: Boolean = false,
) {
    companion object {
        fun parse(source: String): PluginManifest {
            val json = JSONObject(source)
            fun objects(key: String): List<JSONObject> = json.optJSONArray(key)?.let { array ->
                (0 until array.length()).map { array.getJSONObject(it) }
            }.orEmpty()
            val settings = objects("settings").map { setting ->
                val id = setting.getString("id")
                val title = setting.getString("title")
                val description = setting.optString("description")
                when (setting.getString("type")) {
                    "boolean" -> PluginSetting.Toggle(id, title, description, setting.getBoolean("default"))
                    "range" -> PluginSetting.Range(id, title, description, setting.getInt("default"),
                        setting.getInt("min"), setting.getInt("max"), setting.getInt("step"), setting.optString("unit")).also {
                        require(it.min < it.max && it.step > 0 && it.default in it.min..it.max)
                    }
                    else -> error("Unsupported plugin setting")
                }
            }
            require(settings.map { it.id }.distinct().size == settings.size)
            return PluginManifest(json.getString("id"), json.getString("name"), json.getString("version"),
                PluginType.entries.first { it.key == json.getString("type") }, json.getString("author"),
                json.getString("summary"), json.getString("description"), json.optString("privacy"),
                json.optJSONArray("features")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
                objects("buttons").map { b -> PluginButton(b.getString("label"), b.getString("url"), b.optString("icon", "website")) }
                    .also { buttons -> require(buttons.all { it.url.startsWith("https://") }) }, settings,
                json.optBoolean("defaultEnabled", false))
        }
    }
}

data class PluginConfiguration(val enabled: Set<String> = emptySet(), val values: Map<String, String> = emptyMap()) {
    fun value(manifest: PluginManifest, setting: PluginSetting): String = values["${manifest.id}.${setting.id}"] ?: setting.defaultValue
    fun boolean(manifest: PluginManifest, id: String): Boolean = manifest.settings.find { it.id == id }?.let { value(manifest, it).toBoolean() } ?: false
    fun integer(manifest: PluginManifest, id: String): Int = manifest.settings.find { it.id == id }?.let { value(manifest, it).toIntOrNull() } ?: 0
}
