package app.lynli.klang.analytics

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class AnalyticsTransportTest {
    @Test fun revokedTransportBlocksBeforeDnsOrEndpointContact() {
        val gate = AnalyticsTransport("https://analytics.invalid/".toHttpUrl())
        var dnsCalls = 0
        val client = gate.client().newBuilder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                dnsCalls++
                throw AssertionError("No DNS lookup is allowed after consent was revoked")
            }
        }).build()
        gate.revoke()
        assertThrows(IOException::class.java) { client.newCall(batch("https://analytics.invalid/batch")).execute() }
        assertEquals(0, dnsCalls)
    }

    @Test fun consentAllowsOnlyTheBatchEndpoint() {
        MockWebServer().use { server ->
            server.start()
            val client = AnalyticsTransport(server.url("/")).client()
            server.enqueue(MockResponse().setBody("{}"))
            client.newCall(batch(server.url("/batch").toString())).execute().use { assertTrue(it.isSuccessful) }
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/batch", request.path)
            listOf("/flags", "/config", "/s/", "/i/v1/logs", "/batch?extra=1").forEach { path ->
                assertThrows(IOException::class.java) { client.newCall(batch(server.url(path).toString())).execute() }
            }
            assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(server.url("/batch")).get().build()).execute()
            }
            assertThrows(IOException::class.java) {
                client.newCall(batch("https://another.invalid/batch")).execute()
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun redirectsCannotContactAnotherEndpoint() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/flags")))
            val client = AnalyticsTransport(server.url("/")).client()
            client.newCall(batch(server.url("/batch").toString())).execute().use { assertEquals(307, it.code) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun alreadyQueuedRequestsAreBlockedWhenTheSdkFlushesAfterRevocation() {
        MockWebServer().use { server ->
            server.start()
            val gate = AnalyticsTransport(server.url("/"))
            val executor = QueuedExecutor()
            val client = gate.client().newBuilder().dispatcher(okhttp3.Dispatcher(executor)).build()
            var failed = false
            client.newCall(batch(server.url("/batch").toString())).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { failed = true }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    fail("Pending telemetry must not reach the endpoint")
                }
            })
            gate.revoke()
            client.dispatcher.cancelAll()
            executor.drain()
            assertTrue(failed)
            assertEquals(0, server.requestCount)
        }
    }

    private fun batch(url: String) = Request.Builder().url(url).post("{}".toRequestBody()).build()

    private class QueuedExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { tasks += command }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated()
    }
}
