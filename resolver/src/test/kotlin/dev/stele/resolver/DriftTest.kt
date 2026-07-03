package dev.stele.resolver

import kotlin.test.Test
import kotlin.test.assertEquals

class DriftTest {

    @Test
    fun `verdict and reason parse from a well-formed reply`() {
        val checker = DriftChecker(StaticLlmClient("VERDICT: VIOLATION\nREASON: deleteDocument() never checks status.\n"))
        val f = checker.check("Document", "completed documents cannot be deleted", "func deleteDocument() {}", listOf("a.ts"))
        assertEquals(DriftVerdict.VIOLATION, f.verdict)
        assertEquals("deleteDocument() never checks status.", f.reason)
    }

    @Test
    fun `rambling replies degrade to UNVERIFIABLE, keyword anywhere still counts`() {
        val checker = DriftChecker(StaticLlmClient("Well, looking at this code, it complies. COMPLIANT."))
        assertEquals(DriftVerdict.COMPLIANT, checker.check("C", "r", "code", emptyList()).verdict)

        val vague = DriftChecker(StaticLlmClient("I cannot tell from this."))
        assertEquals(DriftVerdict.UNVERIFIABLE, vague.check("C", "r", "code", emptyList()).verdict)
    }

    @Test
    fun `empty code slice short-circuits to UNVERIFIABLE without an LLM call`() {
        val checker = DriftChecker(StaticLlmClient("VERDICT: VIOLATION")) // must not be consulted
        val f = checker.check("C", "rule", "", emptyList())
        assertEquals(DriftVerdict.UNVERIFIABLE, f.verdict)
    }
}
