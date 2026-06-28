package dev.stele.cli

import com.github.ajalt.clikt.core.PrintMessage
import dev.stele.resolver.AnthropicClient
import dev.stele.resolver.LlmClient
import dev.stele.resolver.OllamaClient
import dev.stele.resolver.OpenAiClient
import dev.stele.resolver.StaticLlmClient
import java.io.File

/**
 * Builds the LLM client for the resolver steps. Shared by build-ontology,
 * refine-rules and sync. OpenAI-compatible providers (deepseek/openai/any other)
 * go through [OpenAiClient]; add a new hosted API with just a `baseUrl` in stele.yml.
 */
object LlmFactory {
    fun build(
        provider: String,
        model: String?,
        ollamaUrl: String,
        responses: String? = null,
        baseUrl: String? = null,
        apiKeyEnv: String? = null,
    ): LlmClient {
        if (responses != null) return StaticLlmClient(File(responses).readText())
        return when (provider.lowercase()) {
            "ollama" -> OllamaClient(model ?: "llama3.1", ollamaUrl)
            "anthropic" -> AnthropicClient(key(apiKeyEnv ?: "ANTHROPIC_API_KEY"), model ?: "claude-sonnet-4-6")
            "deepseek" -> OpenAiClient(
                key(apiKeyEnv ?: "DEEPSEEK_API_KEY"), model ?: "deepseek-chat",
                baseUrl ?: "https://api.deepseek.com", "deepseek",
            )
            "openai" -> OpenAiClient(
                key(apiKeyEnv ?: "OPENAI_API_KEY"), model ?: "gpt-4o-mini",
                baseUrl ?: "https://api.openai.com/v1", "openai",
            )
            // Any other OpenAI-compatible endpoint: needs baseUrl; key from <PROVIDER>_API_KEY unless overridden.
            else -> {
                val url = baseUrl ?: throw PrintMessage(
                    "provider '$provider' needs llm.baseUrl in stele.yml (an OpenAI-compatible endpoint).",
                    statusCode = 1, printError = true,
                )
                val m = model ?: throw PrintMessage("provider '$provider' needs llm.model.", statusCode = 1, printError = true)
                OpenAiClient(key(apiKeyEnv ?: "${provider.uppercase()}_API_KEY"), m, url, provider)
            }
        }
    }

    private fun key(varName: String): String =
        System.getenv(varName) ?: throw PrintMessage("Set $varName for this LLM provider.", statusCode = 1, printError = true)
}
