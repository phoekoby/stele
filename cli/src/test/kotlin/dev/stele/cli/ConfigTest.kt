package dev.stele.cli

import dev.stele.cli.config.ConfigLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigTest {

    @Test
    fun `parses a partial stele yml with defaults`() {
        val cfg = ConfigLoader.parse(
            """
            llm: { provider: anthropic, model: claude-sonnet-4-6, batch: 4 }
            sources:
              - { type: symbols, path: "." }
              - { type: web, urls: ["https://a", "https://b"] }
            review: { acceptAbove: 0.7 }
            """.trimIndent(),
        )
        assertEquals("anthropic", cfg.llm.provider)
        assertEquals(4, cfg.llm.batch)
        assertEquals(2, cfg.sources.size)
        assertEquals(listOf("https://a", "https://b"), cfg.sources[1].urls)
        assertEquals(0.7, cfg.review.acceptAbove)
        assertEquals("http://localhost:11434", cfg.llm.ollamaUrl) // default kept
    }

    @Test
    fun `unknown keys are tolerated`() {
        val cfg = ConfigLoader.parse("future: { whatever: 1 }\nllm: { provider: ollama }")
        assertEquals("ollama", cfg.llm.provider)
    }

    @Test
    fun `starter config is valid and every source type is a known connector`() {
        val cfg = ConfigLoader.parse(ConfigLoader.STARTER)
        for (s in cfg.sources) assertNotNull(ConnectorRegistry[s.type], "unknown source: ${s.type}")
        assertTrue(
            ConnectorRegistry.types.containsAll(
                listOf("symbols", "code", "docs", "web", "codegraph", "astindex"),
            ),
        )
    }
}
