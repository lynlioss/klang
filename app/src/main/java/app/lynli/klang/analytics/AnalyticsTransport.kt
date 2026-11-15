package app.lynli.klang.analytics

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** One-way gate per consent period: enabling again must create a new gate and HTTP client. */
internal class AnalyticsTransport(private val host: HttpUrl) : Interceptor {
    @Volatile private var allowed = true

    fun revoke() { allowed = false }

    fun client(): OkHttpClient = OkHttpClient.Builder()
        // Check before DNS and again immediately before transmission.
        .addInterceptor(this)
        .addNetworkInterceptor(this)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        checkRequest(chain.request())
        return chain.proceed(chain.request())
    }

    private fun checkRequest(request: Request) {
        if (!allowed) throw IOException("Analytics consent revoked")
        val url = request.url
        val batchPath = host.encodedPath.trimEnd('/') + "/batch"
        if (request.method != "POST" || url.scheme != host.scheme || url.host != host.host ||
            url.port != host.port || url.encodedPath !in setOf(batchPath, "$batchPath/") || url.query != null) {
            throw IOException("Only analytics batch requests are allowed")
        }
    }
}
