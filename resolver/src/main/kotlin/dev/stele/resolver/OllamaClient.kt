package dev.stele.resolver

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Local LLM via Ollama — OSS, offline, free, no API key. The default backend
 * for a local-first tool; cloud (Anthropic) is opt-in.
 */
class OllamaClient(
    private val model: String = "llama3.1",
    private val baseUrl: String = "http://localhost:11434",
    private val timeout: Duration = Duration.ofMinutes(15),
) : LlmClient {
    override val name = "ollama:$model"

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun complete(system: String, user: String): String {
        val body = json.encodeToString(
            Request(
                model = model,
                messages = listOf(Message("system", system), Message("user", user)),
                stream = false,
                options = Options(numCtx = 8192),
            ),
        )
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/chat"))
            .timeout(timeout)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val res = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { throw RuntimeException("Ollama unreachable at $baseUrl — is `ollama serve` running? (${it.message})") }
        if (res.statusCode() !in 200..299) {
            throw RuntimeException("Ollama ${res.statusCode()}: ${res.body().take(400)}")
        }
        return json.decodeFromString<Response>(res.body()).message.content
    }

    @Serializable
    private data class Request(
        val model: String,
        val messages: List<Message>,
        val stream: Boolean,
        val options: Options,
    )

    @Serializable
    private data class Options(@kotlinx.serialization.SerialName("num_ctx") val numCtx: Int)

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class Response(val message: Message)
}
