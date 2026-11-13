package app.lynli.klang.equalizer

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

val EQ_FREQUENCIES = listOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
val FLAT_GAINS = List(EQ_FREQUENCIES.size) { 0f }

data class EqCurve(val gains: List<Float> = FLAT_GAINS, val presetId: String? = "flat") {
    fun withBand(index: Int, db: Float) = copy(gains = gains.mapIndexed { i, gain ->
        if (i == index) db.coerceIn(-12f, 12f) else gain
    }, presetId = null)
}
data class EqPreset(val id: String, val name: String, val gains: List<Float>) {
    fun curve() = EqCurve(gains, id)
}
val BUILTIN_PRESETS = listOf(
    EqPreset("flat", "Flat", FLAT_GAINS),
    EqPreset("bass", "Bass", listOf(5f, 4f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("vocal", "Vocals", listOf(-2f, -2f, -1f, 0f, 1f, 3f, 3f, 2f, 0f, -1f)),
    EqPreset("bright", "Bright", listOf(0f, 0f, 0f, -1f, -1f, 0f, 1f, 2f, 3f, 3f)),
    EqPreset("warm", "Warm", listOf(2f, 2f, 1f, 1f, 0f, 0f, -1f, -1f, -2f, -2f)),
)
data class EqSettings(
    val enabled: Boolean = false,
    val outputTrimDb: Float = 0f,
    val defaultCurve: EqCurve = EqCurve(),
    val songCurves: Map<String, EqCurve> = emptyMap(),
    val deviceCurves: Map<String, EqCurve> = emptyMap(),
    val presets: List<EqPreset> = emptyList(),
) {
    fun resolve(song: String?, device: String?) = songCurves[song] ?: deviceCurves[device] ?: defaultCurve
    fun editActive(song: String?, device: String?, curve: EqCurve): EqSettings = when {
        song != null && song in songCurves -> copy(songCurves = songCurves + (song to curve))
        device != null && device in deviceCurves -> copy(deviceCurves = deviceCurves + (device to curve))
        else -> copy(defaultCurve = curve)
    }
    fun savePreset(name: String, curve: EqCurve): EqPreset = EqPreset(UUID.randomUUID().toString(), name.trim().take(60), curve.gains)
}

/** Versions and validates persisted values; corrupt entries don't disable all presets. */
object EqCodec {
    private fun curve(value: JSONObject): EqCurve {
        val array = value.optJSONArray("gains") ?: JSONArray()
        val gains = FLAT_GAINS.mapIndexed { i, _ -> array.optDouble(i, 0.0).let {
            if (it.isFinite()) it.toFloat().coerceIn(-12f, 12f) else 0f
        } }
        return EqCurve(gains, value.optString("preset").takeIf { it.isNotBlank() })
    }
    private fun json(curve: EqCurve) = JSONObject().put("gains", JSONArray(curve.gains)).apply {
        curve.presetId?.let { put("preset", it) }
    }
    private fun map(value: JSONObject?) = value?.keys()?.asSequence()?.associateWith {
        curve(value.optJSONObject(it) ?: JSONObject())
    }.orEmpty()
    fun decode(raw: String): EqSettings {
        val root = JSONObject(raw)
        val presets = root.optJSONArray("presets") ?: JSONArray()
        return EqSettings(root.optBoolean("enabled"), root.optDouble("trim", 0.0).let { if (it.isFinite()) it.toFloat().coerceIn(-12f, 0f) else 0f }, curve(root.optJSONObject("default") ?: JSONObject()),
            map(root.optJSONObject("songs")), map(root.optJSONObject("devices")),
            (0 until presets.length()).mapNotNull { i -> presets.optJSONObject(i)?.let { value ->
                val id = value.optString("id"); val name = value.optString("name")
                if (id.isBlank() || name.isBlank()) null else EqPreset(id, name.take(60), curve(value).gains)
            } }.distinctBy { it.id })
    }
    fun encode(settings: EqSettings): String = JSONObject().put("version", 1).put("enabled", settings.enabled)
        .put("trim", settings.outputTrimDb).put("default", json(settings.defaultCurve))
        .put("songs", JSONObject().apply { settings.songCurves.forEach { (id, value) -> put(id, json(value)) } })
        .put("devices", JSONObject().apply { settings.deviceCurves.forEach { (id, value) -> put(id, json(value)) } })
        .put("presets", JSONArray().apply { settings.presets.forEach { preset ->
            put(json(preset.curve()).put("id", preset.id).put("name", preset.name))
        } }).toString()
}
