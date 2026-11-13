package app.lynli.klang.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataTest {
    @Test fun androidUnknownSentinelFallsBackToReadableMetadata() {
        assertEquals("Unknown artist", usable("<unknown>", "Unknown artist"))
        assertEquals("Filename title", usable(null, "Filename title"))
        assertEquals("Unknown album", usable("  ", "Unknown album"))
    }
    @Test fun embeddedMetadataIsPreservedIncludingUnicode() {
        assertEquals("HESTER - KYOTO", usable("HESTER - KYOTO", "KYOTO"))
        assertEquals("坂本龍一", usable("坂本龍一", "Unknown artist"))
    }
}
