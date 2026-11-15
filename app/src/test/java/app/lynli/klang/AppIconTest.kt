package app.lynli.klang

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIconTest {
    @Test fun unknownStoredValuesFallBackToLight() {
        assertEquals(AppIcon.Light, AppIcon.from(null))
        assertEquals(AppIcon.Light, AppIcon.from(""))
        assertEquals(AppIcon.Light, AppIcon.from("purple"))
    }

    @Test fun storedValuesMapToVariants() {
        assertEquals(AppIcon.Light, AppIcon.from("light"))
        assertEquals(AppIcon.Dark, AppIcon.from("dark"))
    }
}
