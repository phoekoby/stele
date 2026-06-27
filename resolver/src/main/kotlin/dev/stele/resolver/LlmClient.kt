package dev.stele.resolver

/** Provider-agnostic chat completion. Returns the model's raw text response. */
interface LlmClient {
    val name: String
    fun complete(system: String, user: String): String
}

/** Offline/replay client: returns a canned response (tests, no-key runs). */
class StaticLlmClient(
    private val response: String,
    override val name: String = "static",
) : LlmClient {
    override fun complete(system: String, user: String): String = response
}
