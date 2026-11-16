package app.lynli.klang.plugins

import org.junit.Assert.*
import org.junit.Test

class ExtensionConnectionStateTest {
    @Test fun disconnectedPluginOffersRecoveryAndReleasesPlaybackUi() {
        val state = ExtensionConnectionState.unavailable("Jam")
        assertTrue(state.getBoolean("hostDisconnected"))
        assertFalse(state.optBoolean("interceptControls"))
        assertFalse(state.optJSONObject("badge")?.optBoolean("visible") == true)
        assertFalse(state.has("trackAction"))
        val panel = state.getJSONObject("panel")
        assertEquals("Jam", panel.getString("title"))
        val items = panel.getJSONArray("items")
        assertEquals("error", items.getJSONObject(0).getString("type"))
        assertEquals("host.retry", items.getJSONObject(1).getString("action"))
    }
}
