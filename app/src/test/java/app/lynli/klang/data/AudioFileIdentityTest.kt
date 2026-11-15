package app.lynli.klang.data

import org.junit.Assert.*
import org.junit.Test

class AudioFileIdentityTest {
    @Test fun deletionRecognizesImportedAndLibraryUrisForTheSameFile() {
        val primary = "content://media/external_primary/audio/media/12"
        val external = "content://media/external/audio/media/12"
        assertEquals(setOf(primary, external), audioFileIds(primary))
        assertEquals(audioFileIds(primary), audioFileIds(external))
    }
    @Test fun deletionDoesNotTreatOtherVolumesOrRowsAsTheSameFile() {
        val removable = "content://media/1234-5678/audio/media/12"
        assertEquals(setOf(removable), audioFileIds(removable))
        assertFalse("content://media/external/audio/media/13" in audioFileIds("content://media/external/audio/media/12"))
    }
}
