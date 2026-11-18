package com.artemis.uisdk.chat

import com.artemis.uisdk.ArtemisThemeConfig
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AdaptiveCardContractsTest {
    @Test fun parsesSupportedCardsAndFindsNestedActions() {
        val card = JsonParser.parseString("""{"type":"AdaptiveCard","version":"1.5","body":[{"type":"Input.Text","id":"name"},{"type":"ActionSet","actions":[{"type":"Action.Submit","id":"submit"}]}]}""")
        val parsed = AdaptiveCardContracts.parse(card)
        assertNotNull(parsed)
        assertEquals(setOf("submit"), AdaptiveCardContracts.actionIds(parsed!!))
        assertNotNull(AdaptiveCardContracts.parse(JsonParser.parseString(com.google.gson.Gson().toJson(card.toString()))))
    }

    @Test fun rejectsMalformedOrUnsupportedCards() {
        listOf(
            """{"type":"AdaptiveCard","version":"1.6"}""",
            """{"type":"AdaptiveCard","body":[{"type":"Unrecognized"}]}""",
            """{"type":"AdaptiveCard","body":[{"type":"Input.Text"}]}""",
            """{"type":"AdaptiveCard","actions":[{"type":"Action.OpenUrl"}]}""",
            "not json",
        ).forEach { assertNull(AdaptiveCardContracts.parse(com.google.gson.JsonPrimitive(it))) }
    }

    @Test fun confinesPayloadAndBlocksUnsafeLinks() {
        val card = AdaptiveCardContracts.parse(JsonParser.parseString("""{"type":"AdaptiveCard","body":[{"type":"TextBlock","text":"</script><script>alert(1)</script>"},{"type":"Image","url":"javascript:alert(1)"}]}"""))!!
        val runtime = JsonParser.parseString("""{"js":"trusted","css":"","markdown":""}""").asJsonObject
        val document = AdaptiveCardContracts.document(card, runtime, ArtemisThemeConfig(), emptySet())
        assertTrue(document.contains("default-src 'none'"))
        assertTrue(document.contains("\\u003c/script>"))
        assertFalse(document.contains("javascript:alert(1)"))
        assertNull(AdaptiveCardContracts.safeHttp("javascript:alert(1)"))
        assertNull(AdaptiveCardContracts.safeHttp("https://user:pass@example.com"))
        assertEquals("https://example.com/a", AdaptiveCardContracts.safeHttp("https://example.com/a"))
    }
}
