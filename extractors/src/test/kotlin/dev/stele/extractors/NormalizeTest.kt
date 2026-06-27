package dev.stele.extractors

import kotlin.test.Test
import kotlin.test.assertEquals

class NormalizeTest {
    @Test
    fun camelCaseSplits() {
        assertEquals("validate refund", normalize("validateRefund"))
    }

    @Test
    fun screamingSnakeSplits() {
        assertEquals("refund window", normalize("REFUND_WINDOW"))
    }

    @Test
    fun pathAndModuleSeparators() {
        assertEquals("node path util", normalize("node:path/util"))
        assertEquals("user refund flow", normalize("user/refund.flow"))
    }

    @Test
    fun dropsShortAndPureNumericTokens() {
        assertEquals(listOf("foo"), splitIdentifier("a_1_foo_22"))
    }
}
