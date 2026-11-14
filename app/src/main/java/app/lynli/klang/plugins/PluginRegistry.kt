package app.lynli.klang.plugins

import android.content.Context
import app.lynli.klang.lyrics.LrclibProvider
import app.lynli.klang.lyrics.LyricsProvider

/** Bundled manifests describe UI; registered implementations supply capabilities. */
class PluginRegistry(context: Context) {
    val manifests: List<PluginManifest> = context.assets.list("plugins").orEmpty().filter { it.endsWith(".json") }.sorted().map { name ->
        context.assets.open("plugins/$name").bufferedReader().use { PluginManifest.parse(it.readText()) }
    }.also { require(it.map(PluginManifest::id).distinct().size == it.size) }
    val lyricsProviders: List<LyricsProvider> = listOf(LrclibProvider()).also { providers ->
        require(providers.all { provider -> manifests.any { it.id == provider.id && it.type == PluginType.LYRICS } })
    }
}

class PluginStore(context: Context, private val manifests: List<PluginManifest>) {
    private val prefs = context.getSharedPreferences("klang_plugins", Context.MODE_PRIVATE)
    fun read() = PluginConfiguration(
        prefs.getStringSet("enabled", manifests.filter { it.defaultEnabled }.map { it.id }.toSet()).orEmpty()
            .filter { id -> manifests.any { it.id == id } }.toSet(),
        manifests.flatMap { manifest -> manifest.settings.map { setting ->
            val key = "${manifest.id}.${setting.id}"
            key to (prefs.getString(key, setting.defaultValue) ?: setting.defaultValue)
        } }.toMap())
    fun enable(id: String, enabled: Boolean): PluginConfiguration {
        require(manifests.any { it.id == id })
        val ids = read().enabled.toMutableSet().apply { if (enabled) add(id) else remove(id) }
        prefs.edit().putStringSet("enabled", ids).apply()
        return read()
    }
    fun setting(id: String, key: String, value: String): PluginConfiguration {
        val definition = manifests.first { it.id == id }.settings.first { it.id == key }
        val normalized = when (definition) {
            is PluginSetting.Toggle -> { require(value == "true" || value == "false"); value }
            is PluginSetting.Range -> {
                val number = value.toInt().coerceIn(definition.min, definition.max)
                (definition.min + ((number - definition.min) / definition.step) * definition.step).toString()
            }
        }
        prefs.edit().putString("$id.$key", normalized).apply()
        return read()
    }
}
