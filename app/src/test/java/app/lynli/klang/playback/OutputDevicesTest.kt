package app.lynli.klang.playback

import android.media.AudioDeviceInfo
import android.media.MediaRoute2Info
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputDevicesTest {
    private val phone = OutputRoute("phone", "Phone", MediaRoute2Info.TYPE_BUILTIN_SPEAKER, true)
    private val headphones = OutputRoute("bluetooth", "Space Travel 2", MediaRoute2Info.TYPE_BLUETOOTH_A2DP, true)
    private val bluetooth = AudioOutputDevice(8, "Space Travel 2", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    @Test fun actualHeadphonesOverrideTheRouterPhoneDefault() {
        val selection = outputSelection(listOf(phone, headphones), setOf(phone.id), listOf(bluetooth))
        assertEquals(setOf(headphones.id), selection.routeIds)
        assertTrue(selection.undiscovered.isEmpty())
    }

    @Test fun connectedHeadphonesAreNotSelectedWhenSoundUsesThePhone() {
        val speaker = AudioOutputDevice(2, "This phone", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val selection = outputSelection(listOf(phone, headphones), setOf(headphones.id), listOf(speaker))
        assertEquals(setOf(phone.id), selection.routeIds)
    }

    @Test fun devicesWithTheSameNameAreMatchedByTheirAddress() {
        val first = headphones.copy(id = "bluetooth:AA:BB:CC:DD:EE:01")
        val second = headphones.copy(id = "bluetooth:AA:BB:CC:DD:EE:02")
        val active = bluetooth.copy(address = "AA:BB:CC:DD:EE:02")
        val selection = outputSelection(listOf(phone, first, second), setOf(first.id), listOf(active))
        assertEquals(setOf(second.id), selection.routeIds)
    }

    @Test fun aMissingRouterEntryStillShowsTheActualDevice() {
        val selection = outputSelection(listOf(phone), setOf(phone.id), listOf(bluetooth))
        assertTrue(selection.routeIds.isEmpty())
        assertEquals(listOf(bluetooth), selection.undiscovered)
    }

    @Test fun wiredAndLeHeadphonesMatchTheirLocalRoutes() {
        for ((deviceType, routeType) in listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES to MediaRoute2Info.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET to MediaRoute2Info.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET to MediaRoute2Info.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER to MediaRoute2Info.TYPE_BLE_HEADSET)) {
            val route = headphones.copy(type = routeType)
            val selection = outputSelection(listOf(phone, route), setOf(phone.id), listOf(bluetooth.copy(type = deviceType)))
            assertEquals(setOf(route.id), selection.routeIds)
        }
    }

    @Test fun remoteSessionsKeepTheirSelectedOutput() {
        val remote = OutputRoute("cast", "Living room", MediaRoute2Info.TYPE_REMOTE_SPEAKER, false)
        assertEquals(setOf(remote.id), outputSelection(listOf(phone, headphones, remote), setOf(remote.id), listOf(bluetooth)).routeIds)
    }

    @Test fun anUnavailableAudioRouteFallsBackToTheRouter() {
        assertEquals(setOf(headphones.id), outputSelection(listOf(phone, headphones), setOf(headphones.id), emptyList()).routeIds)
    }
}
