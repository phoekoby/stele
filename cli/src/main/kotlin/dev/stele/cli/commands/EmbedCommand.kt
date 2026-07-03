package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import dev.stele.cli.EmbedderFactory
import dev.stele.cli.config.ConfigLoader
import dev.stele.cli.requireDb
import dev.stele.core.db.migrate
import dev.stele.core.db.openDb
import dev.stele.core.embed.Embedder
import dev.stele.core.embed.conceptCard
import dev.stele.core.embed.sectionCard
import dev.stele.core.store.GraphStore

/**
 * Pre-computes the semantic layer: concept-card vectors (semantic resolution) and
 * doc-section vectors (question-aware drill), stored in the graph so SERVING never
 * needs a live embedding model. Idempotent — only missing vectors are computed;
 * switching the embed model re-embeds under the new name.
 */
class EmbedCommand : CliktCommand(
    name = "embed",
    help = "Pre-compute concept + doc-section vectors for semantic resolution and drill",
) {
    private val provider by option("--provider", help = "hashing (offline default) | ollama")
    private val model by option("--model", help = "Embedding model (default nomic-embed-text)")
    private val ollamaUrl by option("--ollama-url")

    override fun run() {
        val conn = openDb(requireDb().path)
        migrate(conn) // graphs created before 004 gain the vector tables in place
        val store = GraphStore(conn)
        val embedder = EmbedderFactory.fromConfig(ConfigLoader.findAndLoad()?.llm, provider, model, ollamaUrl)

        val (concepts, sections) = embedAll(store, embedder) { echo(it) }
        echo("✓ embed [${embedder.name}]: $concepts concept cards, $sections doc sections")
        conn.close()
    }
}

/** Shared with `sync`. Returns (new concept vectors, new section vectors). */
fun embedAll(store: GraphStore, embedder: Embedder, log: (String) -> Unit): Pair<Int, Int> {
    val modelKey = embedder.name

    val doneConcepts = store.embeddedIds("concept_vectors", modelKey)
    val concepts = store.resolvedConcepts().filter { it.id !in doneConcepts }
    for (c in concepts) store.storeConceptVector(c.id, modelKey, embedder.embed(conceptCard(c)))

    val doneSections = store.embeddedIds("section_vectors", modelKey)
    val sections = store.docSections().filter { it.id !in doneSections }
    var stored = 0
    var skipped = 0
    for ((i, s) in sections.withIndex()) {
        // A pathological section can bust the model's context window — skip it;
        // any other embedder failure (server down) propagates.
        val vec = try {
            embedder.embed(sectionCard(s))
        } catch (e: RuntimeException) {
            if (e.message?.contains("context length") == true) { skipped++; null } else throw e
        }
        if (vec != null) {
            store.storeSectionVector(s.id, modelKey, vec)
            stored++
        }
        if ((i + 1) % 500 == 0) log("  … ${i + 1}/${sections.size} sections")
    }
    if (skipped > 0) log("  (skipped $skipped sections over the context window)")
    return concepts.size to stored
}
