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

/**
 * Any OpenAI-compatible chat-completions API (DeepSeek, OpenAI, Together, …).
 * `baseUrl` is the API root (e.g. `https://api.deepseek.com`); the client posts
 * to `<baseUrl>/chat/completions` with a Bearer key. Reads no globals.
 */
class OpenAiClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val provider: String = "openai",
    private val maxTokens: Int = 8000,
    private val timeout: Duration = Duration.ofMinutes(5),
) : LlmClient {
    override val name = "$provider:$model"

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun complete(system: String, user: String): String {
        val body = json.encodeToString(
            Request(
                model = model,
                messages = listOf(Message("system", system), Message("user", user)),
                stream = false,
                maxTokens = maxTokens,
            ),
        )
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/chat/completions"))
            .timeout(timeout)
            .header("content-type", "application/json")
            .header("authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val res = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { throw RuntimeException("$provider unreachable at $baseUrl (${it.message})") }
        if (res.statusCode() !in 200..299) {
            throw RuntimeException("$provider ${res.statusCode()}: ${res.body().take(400)}")
        }
        return json.decodeFromString<Response>(res.body())
            .choices.firstOrNull()?.message?.content
            ?: throw RuntimeException("$provider: no content in response")
    }

    @Serializable
    private data class Request(
        val model: String,
        val messages: List<Message>,
        val stream: Boolean,
        @SerialName("max_tokens") val maxTokens: Int,
    )

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class Response(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: Message)
}
