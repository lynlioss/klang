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
    @Test fun changingAccountsDropsOnlineMembershipButKeepsOwnedOfflineFiles() {
        assertTrue(retainSourceSong(false, true, "account-a", "account-a"))
        assertFalse(retainSourceSong(false, true, "account-a", "account-b"))
        assertFalse(retainSourceSong(false, false, "account-a", "account-a"))
        assertFalse(retainSourceSong(false, true, null, "account-a"))
        assertTrue(retainSourceSong(true, false, "account-a", "account-b"))
    }
    @Test fun multipleCatalogEntriesLinkedToOneFileProduceOneSong() {
        val local = Entry("content://media/external/audio/media/23")
        val first = Entry("klang-source://spotify/song", local.id)
        val duplicate = Entry("klang-source://spotify/album-copy", local.id)
        assertEquals(listOf(first), merge(listOf(local), listOf(first, duplicate)))
    }
}
