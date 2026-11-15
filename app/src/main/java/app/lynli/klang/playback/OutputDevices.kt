package app.lynli.klang.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRoute2Info
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class OutputRoute(val id: String, val name: String, val type: Int, val system: Boolean)
internal data class AudioOutputDevice(val id: Int, val name: String, val type: Int, val address: String = "")
internal data class OutputSelection(val routeIds: Set<String>, val undiscovered: List<AudioOutputDevice> = emptyList())

internal fun MediaRoute2Info.outputRoute() = OutputRoute(id, name.toString(), type, isSystemRoute)
internal fun AudioDeviceInfo.outputDevice() = AudioOutputDevice(id, productName.toString(), type, address)

/** The service reports the AudioTrack's route, which can differ from the router's default route. */
internal object PlaybackOutput {
    private val mutableDevices = MutableStateFlow<List<AudioOutputDevice>>(emptyList())
    val devices = mutableDevices.asStateFlow()
    fun update(devices: List<AudioOutputDevice>) { mutableDevices.value = devices }
}

internal fun currentAudioOutputs(audioManager: AudioManager, observed: List<AudioOutputDevice>): List<AudioOutputDevice> {
    val connected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.toSet()
    val routed = observed.filter { it.id in connected }
    if (routed.isNotEmpty()) return routed
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    return audioManager.getAudioDevicesForAttributes(attributes).map { it.outputDevice() }
}

private fun mediaRouteType(deviceType: Int): Int = when (deviceType) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> MediaRoute2Info.TYPE_BUILTIN_SPEAKER
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MediaRoute2Info.TYPE_BLUETOOTH_A2DP
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> MediaRoute2Info.TYPE_BLE_HEADSET
    AudioDeviceInfo.TYPE_BLE_HEARING_AID -> MediaRoute2Info.TYPE_HEARING_AID
    else -> deviceType // Local route types share AudioDeviceInfo's type constants.
}

/** Match actual media outputs without mistaking every connected device for an active device. */
internal fun outputSelection(routes: List<OutputRoute>, reportedIds: Set<String>, devices: List<AudioOutputDevice>): OutputSelection {
    if (devices.isEmpty() || routes.any { !it.system && it.id in reportedIds }) return OutputSelection(reportedIds)
    val ids = mutableSetOf<String>()
    val undiscovered = mutableListOf<AudioOutputDevice>()
    for (device in devices) {
        val candidates = routes.filter { it.system && it.type == mediaRouteType(device.type) }
        val match = candidates.singleOrNull { device.address.isNotBlank() && it.id.contains(device.address, ignoreCase = true) }
            ?: candidates.singleOrNull { it.name.trim().equals(device.name.trim(), ignoreCase = true) }
            ?: candidates.singleOrNull()
            ?: candidates.singleOrNull { it.id in reportedIds }
        if (match != null) ids += match.id else undiscovered += device
    }
    return OutputSelection(ids, undiscovered)
}

internal fun isBluetoothOutput(type: Int) = type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_HEARING_AID)
