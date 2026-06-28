package dev.stele.cli

import com.github.ajalt.clikt.core.PrintMessage
import dev.stele.resolver.AnthropicClient
import dev.stele.resolver.LlmClient
import dev.stele.resolver.OllamaClient
import dev.stele.resolver.StaticLlmClient
import java.io.File

/** Builds the LLM client for the resolver steps. Shared by build-ontology, refine-rules and sync. */
object LlmFactory {
    fun build(provider: String, model: String?, ollamaUrl: String, responses: String? = null): LlmClient = when {
        responses != null -> StaticLlmClient(File(responses).readText())
        provider == "anthropic" -> {
            val key = System.getenv("ANTHROPIC_API_KEY")
                ?: throw PrintMessage("Set ANTHROPIC_API_KEY for --provider anthropic.", statusCode = 1, printError = true)
            AnthropicClient(key, model ?: "claude-sonnet-4-6")
        }
        else -> OllamaClient(model ?: "llama3.1", ollamaUrl)
    }
}
