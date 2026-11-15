package app.lynli.klang.plugins

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginTemplatesTest {
    @Test fun validationAndRenderingUseTheSameTokens() {
        val template = "Bearer {settings.apiKey} for {title}"
        assertEquals(listOf("settings.apiKey", "title"), PluginTemplates.tokens(template).toList())
        assertEquals("Bearer secret for Homecoming", PluginTemplates.render(template) {
            when (it) { "settings.apiKey" -> "secret"; "title" -> "Homecoming"; else -> error("Unknown token") }
        })
    }

    @Test fun replacementValuesRemainLiteralAndAreNotExpandedAgain() {
        val value = "Cash \$5 \\ {artist}"
        assertEquals(value, PluginTemplates.render("{title}") { value })
    }

    @Test fun emptyAndPlainTemplatesNeedNoValues() {
        assertEquals(emptyList<String>(), PluginTemplates.tokens("no placeholders").toList())
        assertEquals("", PluginTemplates.render("") { error("Unexpected token") })
    }
}
