package app.lynli.klang.analytics

import com.posthog.PostHogEvent
import org.junit.Assert.*
import org.junit.Test

class AnalyticsEventSchemaTest {
    @Test fun appEventsKeepOnlyDisclosedDeviceInfoAndCategoricalUsageProperties() {
        val event = PostHogEvent("music_imported", "anonymous-consent-period", mutableMapOf(
            "android_version" to "16", "android_api_level" to 36, "device_manufacturer" to "Google",
            "device_model" to "Pixel", "app_version" to "1.0.12", "track_count" to 3,
            "file_name" to "private.mp3", "url" to "https://private.example/song", "email" to "person@example.com",
            "\$locale" to "en-GB", "\$network_carrier" to "Carrier", "\$screen_name" to "MainActivity",
            "\$device_id" to "old-sdk-id", "\$geoip_disable" to true, "\$process_person_profile" to false,
        ))
        val filtered = AnalyticsEventSchema.filter(event)!!
        assertEquals(setOf("android_version", "android_api_level", "device_manufacturer", "device_model",
            "app_version", "track_count", "\$geoip_disable", "\$process_person_profile"), filtered.properties!!.keys)
        assertEquals("anonymous-consent-period", filtered.distinctId)
        assertTrue(event.properties!!.containsKey("file_name"))
    }

    @Test fun automaticScreensCrashesAndOtherUnexpectedEventsAreDropped() {
        listOf("\$screen", "\$exception", "\$autocapture", "Deep Link Opened", "Application Installed", "arbitrary_event")
            .forEach { assertNull(AnalyticsEventSchema.filter(PostHogEvent(it, "anonymous"))) }
    }
}
