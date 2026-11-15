package app.lynli.klang.analytics

import android.content.Context
import android.util.Log
import app.lynli.klang.BuildConfig
import com.posthog.PersonProfiles
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File

internal class PostHogAnalyticsClient private constructor(
    private val sdk: PostHogInterface,
    private val transport: AnalyticsTransport,
    private val httpClient: OkHttpClient,
    private val queueDirectory: File,
    private val distinctId: String,
) : AnalyticsClient {
    override fun capture(event: String, properties: Map<String, Any>) {
        sdk.capture(event = event, distinctId = distinctId, properties = properties)
    }

    override fun blockTransport() {
        // close() flushes, so block the wire and cancel calls before touching SDK state.
        transport.revoke()
        httpClient.dispatcher.cancelAll()
    }

    override fun closeAndDiscard() {
        sdk.optOut()
        sdk.reset()
        sdk.close()
        httpClient.connectionPool.evictAll()
        queueDirectory.deleteRecursively()
        // Each consent period has its own directory. A late SDK write cannot be loaded
        // when analytics is enabled again; stale directories are removed on next start.
    }

    companion object {
        fun create(context: Context, epoch: String): AnalyticsClient? {
            val token = BuildConfig.POSTHOG_PROJECT_TOKEN.takeIf { it.isNotBlank() } ?: return null
            val host = BuildConfig.POSTHOG_HOST.toHttpUrlOrNull()?.takeIf {
                it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
            } ?: return null
            val transport = AnalyticsTransport(host)
            val httpClient = transport.client()
            val queueDirectory = File(context.cacheDir, "klang-analytics/$epoch")
            @Suppress("DEPRECATION")
            val config = PostHogAndroidConfig(projectToken = token, host = host.toString().trimEnd('/')).apply {
                captureApplicationLifecycleEvents = false
                captureDeepLinks = false
                captureScreenViews = false
                capturePushNotificationSubscriptions = false
                capturePushNotificationOpened = false
                captureElementInteractions = false
                captureRageClicks = false
                captureDeadClicks = false
                sessionReplay = false
                surveys = false
                errorTrackingConfig.autoCapture = false
                errorTrackingConfig.captureNativeCrashes = false
                preloadFeatureFlags = false
                remoteConfig = false
                sendFeatureFlagEvent = false
                setDefaultPersonProperties = false
                personProfiles = PersonProfiles.NEVER
                persistOptOut = false
                disableGeoIp = true
                this.httpClient = httpClient
                // Pinned SDK storage hooks isolate pending events across revocations.
                // Verify these hooks when updating the PostHog dependency.
                storagePrefix = File(queueDirectory, "events").absolutePath
                legacyStoragePrefix = File(queueDirectory, "legacy").absolutePath
                replayStoragePrefix = File(queueDirectory, "replay").absolutePath
                logsStoragePrefix = File(queueDirectory, "logs").absolutePath
                // Restrict SDK metadata to the device information disclosed in the UI.
                addBeforeSend(AnalyticsEventSchema::filter)
                logs.addBeforeSend { null }
            }
            return try {
                val sdk = PostHogAndroid.with(context, config)
                if (sdk.isOptOut()) {
                    transport.revoke()
                    httpClient.dispatcher.cancelAll()
                    sdk.close()
                    null
                } else PostHogAnalyticsClient(sdk, transport, httpClient, queueDirectory, epoch)
            } catch (_: Exception) {
                transport.revoke()
                httpClient.dispatcher.cancelAll()
                Log.w("KlangAnalytics", "Analytics unavailable; the app will continue without it")
                null
            }
        }
    }
}
