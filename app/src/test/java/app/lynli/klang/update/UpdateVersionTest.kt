package app.lynli.klang.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test fun patchReleaseIsNewer() = assertTrue(isNewerVersion("1.0.2", "1.0.0"))

    @Test fun minorAndMajorAreNewer() {
        assertTrue(isNewerVersion("1.1.0", "1.0.9"))
        assertTrue(isNewerVersion("2.0.0", "1.99.99"))
    }

    @Test fun sameVersionIsNotNewer() = assertFalse(isNewerVersion("1.0.0", "1.0.0"))

    @Test fun olderVersionIsNotNewer() {
        assertFalse(isNewerVersion("0.9.9", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", "1.0.1"))
    }

    @Test fun tagPrefixAndSuffixAreIgnored() {
        assertTrue(isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(isNewerVersion("1.0.1-beta", "1.0.0"))
        assertTrue(isNewerVersion("1.0", "0.9.9"))
    }
}
