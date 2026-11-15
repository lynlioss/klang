package app.lynli.klang.analytics

import org.junit.Assert.*
import org.junit.Test

class ConsentAnalyticsTest {
    @Test fun noSdkOrHistoryBeforeConsentIncludingAfterRestart() {
        val fixture = Fixture()
        fixture.analytics.appOpened(onboardingComplete = false)
        fixture.analytics.capture("playback_started")
        assertTrue(fixture.clients.isEmpty())
        fixture.analytics.setConsent(false, "onboarding", onboardingComplete = false)
        fixture.restart()
        fixture.analytics.appOpened(onboardingComplete = true)
        fixture.analytics.capture("playlist_created")
        assertEquals(AnalyticsConsent.DENIED, fixture.analytics.consent)
        assertTrue(fixture.clients.isEmpty())
    }

    @Test fun explicitYesPersistsBeforeSdkAndRecordsConsentingActivationOnce() {
        val fixture = Fixture()
        fixture.analytics.capture("music_imported", mapOf("track_count" to 5))
        assertTrue(fixture.analytics.setConsent(true, "onboarding", onboardingComplete = false))
        assertEquals(listOf("analytics_enabled", "app_first_opened", "onboarding_started", "app_opened"),
            fixture.clients.single().events.map { it.first })
        fixture.analytics.capture("onboarding_completed")
        fixture.restart()
        fixture.analytics.appOpened(onboardingComplete = true)
        assertEquals(listOf("app_opened"), fixture.clients.last().events.map { it.first })
        assertEquals(fixture.clients.first().epoch, fixture.clients.last().epoch)
        assertTrue(fixture.store.preferences.firstOpenReported)
    }

    @Test fun refusalToPersistYesCannotStartTheSdk() {
        val fixture = Fixture()
        fixture.store.savesSucceed = false
        assertFalse(fixture.analytics.setConsent(true, "settings", onboardingComplete = true))
        assertEquals(AnalyticsConsent.NOT_ASKED, fixture.analytics.consent)
        assertTrue(fixture.clients.isEmpty())
    }

    @Test fun grantedWithoutProofOfExplicitConsentFailsClosed() {
        val fixture = Fixture(AnalyticsPreferences(consent = AnalyticsConsent.GRANTED))
        fixture.analytics.appOpened(onboardingComplete = true)
        assertEquals(AnalyticsConsent.NOT_ASKED, fixture.analytics.consent)
        assertTrue(fixture.clients.isEmpty())
    }

    @Test fun revokeBlocksCloseTimeFlushAndReenableCannotReplayOldConsentPeriod() {
        val fixture = Fixture()
        fixture.analytics.setConsent(true, "onboarding", onboardingComplete = false)
        val previous = fixture.clients.single()
        fixture.analytics.capture("playback_started")
        assertTrue(fixture.analytics.setConsent(false, "settings", onboardingComplete = true))
        assertTrue(previous.blocked)
        assertTrue(previous.closed)
        val previousCount = previous.events.size
        fixture.analytics.capture("playlist_created")
        assertEquals(previousCount, previous.events.size)
        fixture.analytics.setConsent(true, "settings", onboardingComplete = true)
        val next = fixture.clients.last()
        assertNotEquals(previous.epoch, next.epoch)
        assertEquals(listOf("analytics_enabled", "app_opened"), next.events.map { it.first })
        assertTrue(next.events.none { it.first == "playback_started" || it.first == "playlist_created" })
    }

    @Test fun enablingForExistingUsersDoesNotInventAnInstallOrOnboardingCompletion() {
        val fixture = Fixture()
        fixture.analytics.setConsent(true, "settings", onboardingComplete = true)
        assertEquals(listOf("analytics_enabled", "app_opened"), fixture.clients.single().events.map { it.first })
    }

    @Test fun everyConsentedActionIncludesTheCurrentAndroidAndDeviceVersion() {
        val fixture = Fixture()
        fixture.analytics.setConsent(true, "settings", onboardingComplete = true)
        fixture.analytics.capture("music_imported", mapOf("track_count" to 3))
        val properties = fixture.clients.single().events.last().second
        assertEquals("16", properties["android_version"])
        assertEquals(36, properties["android_api_level"])
        assertEquals("Pixel", properties["device_model"])
        assertEquals(3, properties["track_count"])
    }

    @Test fun missingBuildConfigurationDoesNotBufferActionsForLater() {
        val store = MemoryStore()
        val analytics = ConsentAnalytics(store, { emptyMap() }, { null })
        assertTrue(analytics.setConsent(true, "onboarding", onboardingComplete = false))
        analytics.appOpened(onboardingComplete = false)
        analytics.capture("playback_started")
        assertFalse(store.preferences.firstOpenReported)
    }

    private class MemoryStore(var preferences: AnalyticsPreferences = AnalyticsPreferences()) : AnalyticsPreferenceStore {
        var savesSucceed = true
        override fun read() = preferences
        override fun write(preferences: AnalyticsPreferences): Boolean {
            if (!savesSucceed) return false
            this.preferences = preferences
            return true
        }
    }

    private class FakeClient(val epoch: String, private val store: MemoryStore) : AnalyticsClient {
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        var blocked = false
        var closed = false
        override fun capture(event: String, properties: Map<String, Any>) {
            if (!blocked) events += event to properties
        }
        override fun blockTransport() { blocked = true }
        override fun closeAndDiscard() {
            assertTrue(blocked)
            assertEquals(AnalyticsConsent.DENIED, store.read().consent)
            // A flush attempted by the SDK's close cannot reach the transport anymore.
            capture("close_time_flush", emptyMap())
            closed = true
        }
    }

    private class Fixture(initial: AnalyticsPreferences = AnalyticsPreferences()) {
        val store = MemoryStore(initial)
        val clients = mutableListOf<FakeClient>()
        var analytics = controller()
        private fun controller() = ConsentAnalytics(store,
            deviceProperties = { mapOf("android_version" to "16", "android_api_level" to 36, "device_model" to "Pixel") },
            clientFactory = { epoch ->
                assertEquals(AnalyticsConsent.GRANTED, store.read().consent)
                assertEquals(epoch, store.read().epoch)
                FakeClient(epoch, store).also { clients += it }
            })
        fun restart() { analytics = controller() }
    }
}
