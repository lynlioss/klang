package app.lynli.klang.plugins

import org.json.JSONArray
import org.json.JSONObject

/** A disconnected extension loses its playback lease but keeps a visible recovery panel. */
internal object ExtensionConnectionState {
    fun unavailable(name: String): JSONObject = JSONObject().put("hostDisconnected", true)
        .put("panel", JSONObject().put("title", name).put("items", JSONArray()
            .put(JSONObject().put("type", "error").put("label", "$name disconnected. Restart the plugin to try again."))
            .put(JSONObject().put("type", "button").put("label", "Restart plugin").put("action", "host.retry"))))
}
