package app.lynli.klang

import android.app.Application
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

class KlangApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val projectToken = BuildConfig.POSTHOG_PROJECT_TOKEN
        val host = BuildConfig.POSTHOG_HOST
        if (projectToken.isNullOrBlank()) {
            reportMissingConfiguration("POSTHOG_PROJECT_TOKEN")
            return
        }
        if (host.isNullOrBlank()) {
            reportMissingConfiguration("POSTHOG_HOST")
            return
        }

        PostHogAndroid.setup(
            this,
            PostHogAndroidConfig(apiKey = projectToken, host = host).apply {
                errorTrackingConfig.autoCapture = true
                logs.serviceName = "klang-android"
                logs.environment = if (BuildConfig.DEBUG) "debug" else "release"
                logs.serviceVersion = BuildConfig.VERSION_NAME
            },
        )
        KlangPostHogLogger.applicationStarted()
    }

    private fun reportMissingConfiguration(variable: String) {
        if (BuildConfig.DEBUG) {
            error("$variable variable required by PostHog is missing or un-configured, this causes events to be silently missed. This error stops appearing once $variable is configured")
        }
    }
}
