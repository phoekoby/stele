package dev.stele.extractors

import dev.stele.core.model.ArtifactKind
import dev.stele.core.model.ConceptStatus
import dev.stele.core.model.EdgeSource
import dev.stele.core.model.EdgeStatus
import dev.stele.core.model.EdgeType
import dev.stele.core.model.Layer
import dev.stele.core.store.GraphStore
import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import java.io.File

data class SymbolIngestResult(
    val files: Int,
    val symbols: Int,
    val concepts: Int,
    val links: Int,
    val skippedLangs: List<String>,
)

// Declaration node types across the wired grammars. We only emit a symbol when
// the node also exposes a `name` field, so unknown grammars degrade to nothing
// rather than crashing.
private val DECL_TYPES = setOf(
    "function_declaration", "function_definition", "function_item",
    "method_declaration", "method_definition", "method_spec",
    "class_declaration", "class_definition", "class_specifier",
    "interface_declaration",
    "struct_declaration", "struct_specifier", "struct_item",
    "enum_declaration", "enum_specifier", "enum_item",
    "trait_item", "object_declaration", "protocol_declaration",
    "type_spec", "type_alias_declaration", "type_item",
    "constructor_declaration",
)

// Structural/layer directories that aren't features — skipped when deriving a
// cluster label so the *feature* folder (auth, secrets, …) wins.
private val GENERIC_DIRS = setOf(
    "src", "lib", "internal", "app", "apps", "cmd", "pkg", "packages", "main",
    "source", "sources", "dist", "build", "target", "out", "vendor", "node_modules",
    "test", "tests", "transport", "service", "services", "store", "stores",
    "repository", "domain", "model", "models", "dto", "api", "core", "common",
    "shared", "util", "utils", "helpers", "config", "types", "db", "database", "server",
)

/**
 * In-house resolved-ish indexer: parse each file, emit a `code_symbol` artifact
 * for every declaration (function/type/method/class/…), and cluster symbols by
 * their feature folder into candidate concepts (inferred `implements` edges,
 * proposed). Fully embedded — no external indexer, no manual export.
 *
 * This is the deterministic cousin of GitNexus/ast-index: same definition nodes,
 * a simpler folder-based clustering instead of call-graph community detection.
 */
fun ingestSymbols(store: GraphStore, rootArg: String): SymbolIngestResult {
    val root = File(rootArg).absoluteFile.normalize()
    val rootPath = root.toPath()
    val parser = TSParser()
    val langCache = HashMap<String, TSLanguage?>()
    val skipped = LinkedHashSet<String>()
    val concepts = HashMap<String, String>()
    val symbolRefs = HashSet<String>()
    val files = HashSet<String>()
    var links = 0

    for (file in walk(root)) {
        val langKey = EXT_TO_LANG["." + file.extension] ?: continue
        val language = langCache.getOrPut(langKey) { loadLanguage(langKey, parser, skipped) } ?: continue
        parser.setLanguage(language)

        val src = file.readText()
        val bytes = src.toByteArray(Charsets.UTF_8)
        val tree = parser.parseString(null, src) ?: continue

        val ref = rootPath.relativize(file.toPath()).toString().replace('\\', '/')
        val cluster = clusterLabel(ref)
        var fileHadSymbols = false

        val stack = ArrayDeque<TSNode>()
        stack.addLast(tree.rootNode)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val name = declName(node, bytes)
            if (name != null && name.length in 2..80) {
                if (!fileHadSymbols) {
                    store.addArtifact(ArtifactKind.FILE, Layer.CODE, "code", ref, title = ref)
                    files.add(ref)
                    fileHadSymbols = true
                }
                val symbolRef = "$ref#$name"
                val symbolId = store.addArtifact(
                    kind = ArtifactKind.CODE_SYMBOL,
                    layer = Layer.CODE,
                    source = "code",
                    ref = symbolRef,
                    title = name,
                    attrs = mapOf("symbolKind" to node.type, "via" to "treesitter"),
                )
                symbolRefs.add(symbolRef)

                val conceptId = concepts.getOrPut(cluster) {
                    store.findConceptId(cluster) ?: store.addConcept(name = cluster, status = ConceptStatus.CANDIDATE)
                }
                store.addEdge(
                    srcId = symbolId,
                    dstId = conceptId,
                    type = EdgeType.IMPLEMENTS,
                    source = EdgeSource.INFERRED,
                    confidence = 0.6,
                    evidence = listOf("treesitter:dir:$cluster"),
                    status = EdgeStatus.PROPOSED,
                )
                links++
            }
            for (i in 0 until node.childCount) stack.addLast(node.getChild(i))
        }
    }

    return SymbolIngestResult(files.size, symbolRefs.size, concepts.size, links, skipped.toList())
}

/** The declared name of a definition node, or null if the node isn't a named declaration. */
private fun declName(node: TSNode, bytes: ByteArray): String? {
    val isDecl = node.type in DECL_TYPES ||
        (node.type == "variable_declarator" && hasFunctionValue(node)) // TS/JS `const x = () => …`
    if (!isDecl) return null
    val nameNode = node.getChildByFieldName("name")
    if (nameNode == null || nameNode.isNull()) return null
    return nodeText(bytes, nameNode).takeIf { it.isNotBlank() }
}

private fun hasFunctionValue(node: TSNode): Boolean {
    val value = node.getChildByFieldName("value") ?: return false
    if (value.isNull()) return false
    return value.type.contains("arrow_function") || value.type.contains("function")
}

/** Feature label for a file: nearest non-generic directory, else the file stem. */
private fun clusterLabel(ref: String): String {
    val parts = ref.split('/')
    for (i in parts.size - 2 downTo 0) {
        val seg = parts[i]
        if (seg.isNotBlank() && seg.lowercase() !in GENERIC_DIRS) return capitalize(seg)
    }
    val stem = parts.last().substringBeforeLast('.')
    if (stem.isNotBlank() && stem.lowercase() !in GENERIC_DIRS) return capitalize(stem)
    return "Misc"
}

private fun capitalize(s: String): String = s.replaceFirstChar { it.uppercaseChar() }
