package app.lynli.klang.analytics

enum class AnalyticsConsent { NOT_ASKED, GRANTED, DENIED }

internal data class AnalyticsPreferences(
    val consent: AnalyticsConsent = AnalyticsConsent.NOT_ASKED,
    val epoch: String? = null,
    val firstOpenReported: Boolean = false,
)

internal interface AnalyticsPreferenceStore {
    fun read(): AnalyticsPreferences
    fun write(preferences: AnalyticsPreferences): Boolean
}

internal interface AnalyticsClient {
    fun capture(event: String, properties: Map<String, Any>)

    fun blockTransport()
    fun closeAndDiscard()
}
