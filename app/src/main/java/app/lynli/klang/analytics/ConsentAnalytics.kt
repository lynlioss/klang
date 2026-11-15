package app.lynli.klang.analytics

import java.util.UUID

/** Owns consent independently of the SDK. Unconsented actions are dropped, never buffered. */
internal class ConsentAnalytics(
    private val store: AnalyticsPreferenceStore,
    private val deviceProperties: () -> Map<String, Any>,
    private val clientFactory: (epoch: String) -> AnalyticsClient?,
    private val newEpoch: () -> String = { UUID.randomUUID().toString() },
) {
    @Volatile private var preferences = store.read()
    private var client: AnalyticsClient? = null
    val consent: AnalyticsConsent get() = preferences.consent

    init {
        // Both values are written together after an explicit Yes or settings switch action.
        val epoch = preferences.epoch
        if (preferences.consent == AnalyticsConsent.GRANTED && epoch != null) {
            client = clientFactory(epoch)
        } else if (preferences.consent == AnalyticsConsent.GRANTED) {
            preferences = preferences.copy(consent = AnalyticsConsent.NOT_ASKED)
        }
    }

    @Synchronized
    fun setConsent(enabled: Boolean, source: String, onboardingComplete: Boolean): Boolean {
        if (!enabled) {
            preferences = preferences.copy(consent = AnalyticsConsent.DENIED, epoch = null)
            val previous = client
            client = null
            previous?.blockTransport()
            // Persist before the SDK cleanup, which can take longer or be interrupted.
            val saved = store.write(preferences)
            previous?.closeAndDiscard()
            return saved
        }
        if (consent == AnalyticsConsent.GRANTED) {
            if (source == "onboarding" && onboardingComplete) onboardingCompleted()
            return true
        }
        val next = preferences.copy(consent = AnalyticsConsent.GRANTED, epoch = newEpoch())
        if (!store.write(next)) return false
        preferences = next
        client = clientFactory(next.epoch!!)
        capture("analytics_enabled", mapOf("source" to source))
        if (source == "onboarding" && onboardingComplete) onboardingCompleted()
        appOpened(onboardingComplete)
        return true
    }

    @Synchronized
    fun appOpened(onboardingComplete: Boolean) {
        if (consent != AnalyticsConsent.GRANTED || client == null) return
        capture("app_opened", mapOf("onboarding_complete" to onboardingComplete))
    }

    @Synchronized
    fun onboardingCompleted() {
        if (consent != AnalyticsConsent.GRANTED || client == null || preferences.onboardingCompletionReported) return
        val reportFirstOpen = !preferences.firstOpenReported
        val next = preferences.copy(firstOpenReported = true, onboardingCompletionReported = true)
        if (!store.write(next)) return
        preferences = next
        if (reportFirstOpen) capture("app_first_opened")
        // Report the completed setup only after consent, without recording earlier interactions.
        capture("onboarding_completed")
    }

    @Synchronized
    fun capture(event: String, properties: Map<String, Any> = emptyMap()) {
        if (consent != AnalyticsConsent.GRANTED) return
        val activeClient = client ?: return
        activeClient.capture(event, deviceProperties() + properties)
    }
}
