package app.lynli.klang.analytics

import android.content.Context
import android.os.Build
import app.lynli.klang.BuildConfig
import java.io.File

internal class KlangAnalytics(context: Context) {
    private val application = context.applicationContext
    private val appPreferences = application.getSharedPreferences("klang", Context.MODE_PRIVATE)
    private val store = AndroidAnalyticsPreferenceStore(application)
    init {
        // Old consent periods (including an interrupted revocation) must never be replayed.
        val activeEpoch = store.read().takeIf { it.consent == AnalyticsConsent.GRANTED }?.epoch
        File(application.cacheDir, "klang-analytics").listFiles()?.filter { it.name != activeEpoch }
            ?.forEach { it.deleteRecursively() }
    }
    private val controller = ConsentAnalytics(store, deviceProperties = {
        mapOf(
            "android_version" to Build.VERSION.RELEASE,
            "android_api_level" to Build.VERSION.SDK_INT,
            "device_manufacturer" to Build.MANUFACTURER,
            "device_model" to Build.MODEL,
            "app_version" to BuildConfig.VERSION_NAME,
            "app_version_code" to BuildConfig.VERSION_CODE,
        )
    }, clientFactory = { epoch ->
        PostHogAnalyticsClient.create(application, epoch)
    })

    val consent get() = controller.consent

    fun setConsent(enabled: Boolean, source: String): Boolean =
        controller.setConsent(enabled, source, appPreferences.getBoolean("onboardingComplete", false))

    fun appOpened() = controller.appOpened(appPreferences.getBoolean("onboardingComplete", false))

    fun capture(event: String, properties: Map<String, Any> = emptyMap()) = controller.capture(event, properties)
}

private class AndroidAnalyticsPreferenceStore(context: Context) : AnalyticsPreferenceStore {
    private val prefs = context.getSharedPreferences("klang_analytics", Context.MODE_PRIVATE)

    override fun read(): AnalyticsPreferences {
        val consent = runCatching {
            AnalyticsConsent.valueOf(prefs.getString("consent", "NOT_ASKED")!!)
        }.getOrDefault(AnalyticsConsent.NOT_ASKED)
        val epoch = prefs.getString("epoch", null)?.takeIf {
            runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false)
        }
        return AnalyticsPreferences(consent, epoch, prefs.getBoolean("firstOpenReported", false))
    }

    override fun write(preferences: AnalyticsPreferences): Boolean = prefs.edit()
        .putString("consent", preferences.consent.name)
        .putString("epoch", preferences.epoch)
        .putBoolean("firstOpenReported", preferences.firstOpenReported)
        .commit()
}
