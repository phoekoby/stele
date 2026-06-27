package dev.stele.resolver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Anthropic Messages API via the JDK HTTP client. Reads no globals — key is injected. */
class AnthropicClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-6",
    private val maxTokens: Int = 8000,
) : LlmClient {
    override val name = "anthropic:$model"

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun complete(system: String, user: String): String {
        val body = json.encodeToString(
            Request(model, maxTokens, system, listOf(Message("user", user))),
        )
        val request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
            .timeout(Duration.ofMinutes(3))
            .header("content-type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val res = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() !in 200..299) {
            throw RuntimeException("Anthropic ${res.statusCode()}: ${res.body().take(400)}")
        }
        return json.decodeFromString<Response>(res.body())
            .content.firstOrNull { it.type == "text" }?.text
            ?: throw RuntimeException("Anthropic: no text content in response")
    }

    @Serializable
    private data class Request(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String,
        val messages: List<Message>,
    )

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class Response(val content: List<Content> = emptyList())

    @Serializable
    private data class Content(val type: String = "", val text: String = "")
}
