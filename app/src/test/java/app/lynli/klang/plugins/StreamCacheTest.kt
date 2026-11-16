package app.lynli.klang.plugins

import org.junit.Assert.*
import org.junit.Test

class StreamCacheTest {
    @Test fun expiringUrlsAreDiscardedBeforeStartup() {
        val cache = StreamCache<String>(2)
        cache.put("song", "https://audio.test/file", 100_000)
        assertNotNull(cache.get("song", 69_999))
        assertNull(cache.get("song", 70_000))
    }
    @Test fun recentTracksSurviveBoundedEviction() {
        val cache = StreamCache<String>(2)
        cache.put("a", "A", 100_000); cache.put("b", "B", 100_000)
        assertEquals("A", cache.get("a", 0))
        cache.put("c", "C", 100_000)
        assertNull(cache.get("b", 0))
        assertEquals("A", cache.get("a", 0)); assertEquals("C", cache.get("c", 0))
    }
    @Test fun accountAndPlaybackChangesHaveSeparateCacheKeysAndInvalidationClearsAll() {
        val cache = StreamCache<String>(2)
        cache.put("song:account-a:youtube", "url-a", 100_000)
        assertNull(cache.get("song:account-b:youtube", 0))
        assertNull(cache.get("song:account-a:qobuz", 0))
        cache.clear()
        assertNull(cache.get("song:account-a:youtube", 0))
    }
    @Test fun audioCacheIdentityTracksAccountAndEncodingInsteadOfTemporaryUrl() {
        val first = org.json.JSONObject().put("url", "https://audio.test/file?expire=100").put("cacheKey", "recording:251:version1")
        val refreshed = org.json.JSONObject(first.toString()).put("url", "https://audio.test/file?expire=200")
        assertEquals(sourceAudioKey("song|account-a", first), sourceAudioKey("song|account-a", refreshed))
        assertNotEquals(sourceAudioKey("song|account-a", first), sourceAudioKey("song|account-b", first))
        assertNotEquals(sourceAudioKey("song|account-a", first), sourceAudioKey("song|account-a", org.json.JSONObject(first.toString()).put("cacheKey", "recording:140:version1")))
        first.remove("cacheKey"); refreshed.remove("cacheKey")
        assertNotEquals(sourceAudioKey("song|account-a", first), sourceAudioKey("song|account-a", refreshed))
    }
}
