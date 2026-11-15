package app.lynli.klang.analytics

import com.posthog.PostHogEvent

/** Keep automatic SDK additions and personal content out of the analytics payload. */
internal object AnalyticsEventSchema {
    fun filter(event: PostHogEvent): PostHogEvent? {
        if (event.event !in allowedEvents) return null
        return event.copy(properties = event.properties?.filterKeys { it in allowedProperties }?.toMutableMap())
    }

    private val allowedEvents = setOf(
        "analytics_enabled", "app_first_opened", "app_opened", "onboarding_completed",
        "music_imported", "playback_started", "audio_deleted", "plugin_installed", "plugin_uninstalled",
        "favorite_toggled", "playlist_created", "playlist_deleted", "sleep_timer_set",
    )

    private val allowedProperties = setOf(
        "android_version", "android_api_level", "device_manufacturer", "device_model", "app_version", "app_version_code",
        "source", "onboarding_complete", "track_count", "queue_size", "source_type", "shuffled", "plugin_type",
        "operation", "is_favorite", "duration_minutes", "is_active",
        "\$lib", "\$lib_version", "\$session_id", "\$process_person_profile", "\$geoip_disable",
    )
}
