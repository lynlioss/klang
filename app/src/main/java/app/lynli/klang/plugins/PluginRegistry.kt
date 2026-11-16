package app.lynli.klang.plugins

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import app.lynli.klang.lyrics.DeclarativeLyricsProvider
import app.lynli.klang.lyrics.LrclibProvider
import app.lynli.klang.lyrics.LyricsProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Only lyrics can be bundled. Service plugins are discovered from separately installed APKs. */
class PluginRegistry(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "plugins/installed.json").also { it.parentFile?.mkdirs() })
    private val loadErrors = mutableListOf<String>()
    val loadError: String? get() = loadErrors.takeIf { it.isNotEmpty() }?.joinToString("\n")
    private var removed = emptySet<String>()
    @Volatile private var installed = emptyList<InstalledPlugin>()
    private var providerConfig: PluginConfiguration? = null
    private var providerEntries = emptyList<InstalledPlugin>()
    private var providers = emptyList<LyricsProvider>()
    init {
        // A plugin is optional. Report malformed manifests without preventing playback startup.
        val bundled = context.assets.list("plugins").orEmpty().filter { it.endsWith(".json") }.sorted().mapNotNull { name ->
            runCatching {
                val source = context.assets.open("plugins/$name").bufferedReader().use { it.readText() }
                val manifest = PluginManifest.parse(source)
                require(manifest.type == PluginType.LYRICS) { "Service plugins must be installed separately" }
                source to manifest
            }.onFailure { recordLoadError(name, it) }.getOrNull()
        }
        val saved = runCatching { JSONObject(file.openRead().bufferedReader().use { it.readText() }) }
            .onFailure { if (file.baseFile.exists() || File("${file.baseFile.path}.bak").exists()) recordLoadError("Saved plugins", it) }.getOrNull()
        removed = saved?.optJSONArray("removed")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet() }.orEmpty()
        val external = saved?.optJSONArray("installed")?.let { a -> (0 until a.length()).mapNotNull { index ->
            runCatching {
                val entry = a.getJSONObject(index)
                val source = entry.getJSONObject("manifest").toString()
                val manifest = PluginManifest.parse(source)
                val repository = GitHubPluginRepo.parse(entry.getString("repository"))
                require((manifest.lyrics != null || manifest.musicImport != null || manifest.extension != null) && manifest.repository == repository.url)
                InstalledPlugin(manifest, repository, source, false)
            }.onFailure { recordLoadError("Installed plugin ${index + 1}", it) }.getOrNull()
        } }.orEmpty().distinctBy { it.manifest.id }
        installed = external + bundled.mapNotNull { (source, manifest) ->
            if (manifest.id in removed || external.any { it.manifest.id == manifest.id }) null
            else InstalledPlugin(manifest, manifest.repository?.let(GitHubPluginRepo::parse), source, true)
        }
        installed = installed.distinctBy { it.manifest.id }
        discoverServices()
    }
    private fun recordLoadError(name: String, error: Throwable) {
        Log.e("KlangPlugins", "Couldn't load $name", error)
        loadErrors += "Couldn't load $name. ${error.message ?: "Invalid plugin data."}"
    }
    val entries: List<InstalledPlugin> get() = installed
    val manifests: List<PluginManifest> get() = installed.map { it.manifest }
    @Synchronized fun lyricsProviders(config: PluginConfiguration): List<LyricsProvider> {
        if (providerConfig != config || providerEntries != entries) {
            providerConfig = config
            providerEntries = entries
            providers = entries.filter { it.manifest.type == PluginType.LYRICS }.map { entry ->
                if (entry.manifest.lyrics != null) DeclarativeLyricsProvider(entry.manifest, config)
                else LrclibProvider().also { require(entry.manifest.id == "lrclib") }
            }
        }
        return providers
    }
    @Synchronized fun discoverServices() {
        val discovered = (listOf(MUSIC_IMPORT_ACTION, EXTENSION_ACTION, MUSIC_SOURCE_ACTION).flatMap { action -> context.packageManager.queryIntentServices(android.content.Intent(action),
            android.content.pm.PackageManager.GET_META_DATA) }).mapNotNull { match -> runCatching {
            val service = match.serviceInfo
            require(service.exported && service.enabled && service.applicationInfo.enabled)
            val resource = service.metaData?.getInt("klang.plugin") ?: error("Missing plugin manifest")
            val source = context.packageManager.getResourcesForApplication(service.packageName)
                .openRawResource(resource).bufferedReader().use { it.readText() }
            val manifest = PluginManifest.parse(source)
            val packageName = manifest.musicSource?.packageName ?: manifest.musicImport?.packageName ?: manifest.extension?.packageName
            val serviceName = manifest.musicSource?.service ?: manifest.musicImport?.service ?: manifest.extension?.service
            require(manifest.apiVersion == PluginManifest.API_VERSION && manifest.repository != null)
            require(packageName == service.packageName && serviceName == service.name)
            InstalledPlugin(manifest, manifest.repository?.let(GitHubPluginRepo::parse), source, false)
        }.getOrNull() }.filter { it.manifest.id !in removed }
        val existingRepositories = installed.associate { it.manifest.id to it.repository }
        installed = (installed.filter { it.manifest.musicImport == null && it.manifest.extension == null && it.manifest.musicSource == null } + discovered.filter {
            existingRepositories[it.manifest.id]?.let { repo -> repo == it.repository } != false
        }).distinctBy { it.manifest.id }
    }
    fun install(candidate: PluginCandidate) {
        val existing = entries.find { it.manifest.id == candidate.manifest.id }
        require(existing == null || existing.repository == candidate.repository) { "This plugin ID belongs to a different repository" }
        require(existing == null || PluginVersion.parse(candidate.manifest.version) > PluginVersion.parse(existing.manifest.version)) {
            "This plugin version is already installed"
        }
        require(existing != null || installed.size < 30) { "You can install up to 30 plugins" }
        val replacement = InstalledPlugin(candidate.manifest, candidate.repository, candidate.source, false)
        val next = if (existing == null) installed + replacement else installed.map {
            if (it.manifest.id == candidate.manifest.id) replacement else it
        }
        val nextRemoved = removed - candidate.manifest.id
        persist(next, nextRemoved)
        removed = nextRemoved
        installed = next
    }
    fun uninstall(id: String) {
        val next = installed.filterNot { it.manifest.id == id }
        val nextRemoved = removed + id
        persist(next, nextRemoved)
        removed = nextRemoved
        installed = next
    }
    @Synchronized fun allowReinstall(id: String) {
        persist(installed, removed - id)
        removed = removed - id
    }
    private fun persist(entries: List<InstalledPlugin>, removedIds: Set<String>) {
        val json = JSONObject().put("removed", JSONArray(removedIds.toList())).put("installed", JSONArray(entries.filterNot { it.bundled }.map {
            JSONObject().put("repository", it.repository!!.url).put("manifest", JSONObject(it.source))
        }))
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}

const val EXTENSION_ACTION = "io.github.lynlioss.klang.EXTENSION_V1"

const val MUSIC_SOURCE_ACTION = "io.github.lynlioss.klang.MUSIC_SOURCE_V1"

const val MUSIC_IMPORT_ACTION = "io.github.lynlioss.klang.MUSIC_IMPORT_V1"

data class InstalledPlugin(val manifest: PluginManifest, val repository: GitHubPluginRepo?, val source: String, val bundled: Boolean)
data class PluginCandidate(val manifest: PluginManifest, val repository: GitHubPluginRepo, val source: String, val releaseUrl: String,
    val apkUrl: String? = null, val apkChecksum: String? = null)
data class PluginCatalog(val installed: List<InstalledPlugin> = emptyList(), val updates: Map<String, PluginCandidate> = emptyMap(),
    val checking: Boolean = false, val busyId: String? = null, val error: String? = null, val checked: Boolean = false,
    val updateNoticeDismissed: Boolean = false)

class PluginStore(context: Context, private val manifests: () -> List<PluginManifest>) {
    private val prefs = context.getSharedPreferences("klang_plugins", Context.MODE_PRIVATE)
    fun read(): PluginConfiguration {
        val definitions = manifests()
        val known = prefs.getStringSet("knownPlugins", if (prefs.contains("enabled"))
            definitions.filter { it.type == PluginType.LYRICS }.map { it.id }.toSet() else emptySet()).orEmpty()
        val savedEnabled = prefs.getStringSet("enabled", definitions.filter { it.defaultEnabled }.map { it.id }.toSet()).orEmpty()
        val newDefaults = definitions.filter { it.id !in known && it.defaultEnabled }.map { it.id }
        if (definitions.any { it.id !in known }) prefs.edit()
            .putStringSet("enabled", (savedEnabled + newDefaults).toSet())
            .putStringSet("knownPlugins", (known + definitions.map { it.id }).toSet()).apply()
        return PluginConfiguration(
            prefs.getStringSet("enabled", definitions.filter { it.defaultEnabled }.map { it.id }.toSet()).orEmpty()
                .filter { id -> definitions.any { it.id == id } }.toSet(),
            definitions.flatMap { manifest -> manifest.settings.map { setting ->
                val key = "${manifest.id}.${setting.id}"
                val saved = prefs.getString(key, setting.defaultValue) ?: setting.defaultValue
                key to runCatching { normalize(setting, saved) }.getOrDefault(setting.defaultValue)
            } }.toMap())
    }
    fun enable(id: String, enabled: Boolean): PluginConfiguration {
        require(manifests().any { it.id == id })
        val ids = read().enabled.toMutableSet().apply { if (enabled) add(id) else remove(id) }
        prefs.edit().putStringSet("enabled", ids).apply()
        return read()
    }
    fun remove(id: String) {
        val edit = prefs.edit().putStringSet("enabled", prefs.getStringSet("enabled", read().enabled).orEmpty() - id)
        // Setting IDs never contain dots; another plugin may have this ID as its prefix.
        prefs.all.keys.filter { it.substringBeforeLast('.') == id }.forEach(edit::remove)
        edit.apply()
    }
    fun setting(id: String, key: String, value: String): PluginConfiguration {
        val definition = manifests().first { it.id == id }.settings.first { it.id == key }
        prefs.edit().putString("$id.$key", normalize(definition, value)).apply()
        return read()
    }
    private fun normalize(definition: PluginSetting, value: String): String = when (definition) {
        is PluginSetting.Toggle -> { require(value == "true" || value == "false"); value }
        is PluginSetting.Range -> {
            val number = value.toInt().coerceIn(definition.min, definition.max)
            (definition.min + ((number - definition.min) / definition.step) * definition.step).toString()
        }
        is PluginSetting.Text -> value.also { require(it.length <= 4096 && '\r' !in it && '\n' !in it) }
        is PluginSetting.Choice -> value.also { require(definition.options.any { option -> option.value == it }) }
    }
}
