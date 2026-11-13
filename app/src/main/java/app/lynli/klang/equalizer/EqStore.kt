package app.lynli.klang.equalizer

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared in-process state: the service owns audio, while screens edit persisted settings. */
class EqStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(runCatching {
        EqCodec.decode(prefs.getString("settings", "{}")!!)
    }.getOrDefault(EqSettings()))
    val settings = mutableSettings.asStateFlow()
    private val mutableRuntime = MutableStateFlow(EqRuntime())
    val runtime = mutableRuntime.asStateFlow()
    fun update(edit: (EqSettings) -> EqSettings) {
        val next = edit(mutableSettings.value)
        mutableSettings.value = next
        prefs.edit().putString("settings", EqCodec.encode(next)).apply()
    }
    fun runtime(value: EqRuntime) { mutableRuntime.value = value }
    fun curve() = settings.value.resolve(runtime.value.songId, runtime.value.deviceId)
    fun editCurve(curve: EqCurve) = update { it.editActive(runtime.value.songId, runtime.value.deviceId, curve) }
    companion object {
        @Volatile private var instance: EqStore? = null
        fun get(context: Context): EqStore = instance ?: synchronized(this) {
            instance ?: EqStore(context.applicationContext).also { instance = it }
        }
    }
}

data class EqRuntime(val songId: String? = null, val deviceId: String? = null, val deviceName: String = "This phone")
