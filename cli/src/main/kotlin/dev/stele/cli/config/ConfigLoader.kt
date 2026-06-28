package dev.stele.cli.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File

/** Reads `stele.yml`. Lenient (`strictMode = false`) so unknown keys don't break older configs. */
object ConfigLoader {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    fun parse(text: String): SteleConfig = yaml.decodeFromString(SteleConfig.serializer(), text)

    fun load(file: File): SteleConfig = parse(file.readText())

    /** `stele.yml` / `stele.yaml` in the given dir (cwd by default), or null. */
    fun find(dir: File = File(System.getProperty("user.dir"))): File? =
        listOf("stele.yml", "stele.yaml").map { File(dir, it) }.firstOrNull { it.isFile }

    fun findAndLoad(): SteleConfig? = find()?.let { runCatching { load(it) }.getOrNull() }

    val STARTER: String = """
        # Stele config - commit this; the graph in .stele/ is gitignored.
        # Configure once, then run the whole pipeline with:  stele sync
        llm:
          provider: ollama            # ollama (local) | anthropic | deepseek | openai | <any OpenAI-compatible>
          model: llama3.1             # provider-specific model id (e.g. deepseek-chat, gpt-4o-mini)
          ollamaUrl: http://localhost:11434
          batch: 8
          # Cloud example — DeepSeek (set DEEPSEEK_API_KEY in your env):
          #   provider: deepseek
          #   model: deepseek-chat
          # Any other OpenAI-compatible API: set provider + model + baseUrl + apiKeyEnv.

        sources:
          - { type: symbols, path: "." }     # code -> candidate concepts (tree-sitter, incremental)
          - { type: docs,    path: "." }     # Markdown product docs -> concepts, rules, relations
          # - { type: web,   urls: ["https://your-wiki/spec", "https://jira/browse/PROJ-1"] }
          # - { type: astindex,  path: "~/.../ast-index/<hash>/index.db" }
          # - { type: codegraph, path: "gitnexus-export.json" }

        review:
          acceptAbove: 0.8            # `stele sync` auto-confirms proposed edges at/above this confidence

        hooks:
          autoReindex: false         # see `stele install-hook` - re-index changed files on commit

    """.trimIndent() + "\n"
}
