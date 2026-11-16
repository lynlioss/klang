package app.lynli.klang.data

import org.junit.Assert.*
import org.junit.Test

class UnifiedLibraryTest {
    private data class Entry(val id: String, val offline: String? = null)
    private fun merge(device: List<Entry>, sources: List<Entry>) = mergeMusicEntries(device, sources, Entry::id, Entry::offline)

    @Test fun downloadsKeepTheSourceIdentityAcrossMediaStoreAliases() {
        val source = Entry("klang-source://spotify/song", "content://media/external_primary/audio/media/23")
        val local = Entry("content://media/external/audio/media/23")
        val another = Entry("content://media/external/audio/media/24")
        assertEquals(listOf(another, source), merge(listOf(local, another), listOf(source)))
    }
    @Test fun missingOfflineFilesAndUnrelatedLocalSongsStayVisible() {
        val local = Entry("content://media/external/audio/media/23")
        val source = Entry("klang-source://community/song")
        assertEquals(listOf(local, source), merge(listOf(local), listOf(source, source)))
        assertEquals(listOf(local), merge(listOf(local), emptyList()))
    }
    @Test fun providersNeverShareIdentitiesAndDuplicatePlaylistReferencesMergeOnce() {
        val first = Entry("klang-source://one/song")
        val other = Entry("klang-source://two/song")
        assertEquals(listOf(first, other), merge(emptyList(), listOf(first, first, other)))
    }
}
