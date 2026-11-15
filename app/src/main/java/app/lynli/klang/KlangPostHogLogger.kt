package app.lynli.klang

import com.posthog.PostHog

/** Emits only the app outcomes intentionally exported to PostHog Logs. */
internal object KlangPostHogLogger {
    fun applicationStarted() {
        PostHog.logger.info("application_started")
    }

    fun playbackFailed(errorCode: String) {
        PostHog.logger.error("playback_failed", mapOf("error_code" to errorCode))
    }

    fun libraryScanFailed(error: Throwable) {
        PostHog.logger.error("library_scan_failed", mapOf("error_type" to error.javaClass.simpleName))
    }

    fun updateInstallationFailed(error: Throwable) {
        PostHog.logger.error("update_installation_failed", mapOf("error_type" to error.javaClass.simpleName))
    }
}
