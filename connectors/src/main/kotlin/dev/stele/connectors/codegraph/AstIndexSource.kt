package dev.stele.connectors.codegraph

import java.sql.Connection
import java.sql.DriverManager

/**
 * Durable, automated code-graph source: reads an **ast-index** SQLite database
 * (`index.db`) directly via JDBC — no agent, no hand-pulled export. Maps the
 * real ast-index schema (docs/db-schema.md): `symbols` (joined to `files`) →
 * code symbols, `modules` (by file-path prefix) → candidate concepts.
 *
 * Run `ast-index rebuild` in a repo, then `stele ingest astindex <…>/index.db`.
 */
class AstIndexSource(private val dbPath: String) : CodeGraphSource {

    private data class Module(val name: String, val path: String)

    override fun load(): CodeGraphExport {
        runCatching { Class.forName("org.sqlite.JDBC") }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            val files = readFiles(conn)
            val modules = readModules(conn).sortedByDescending { it.path.length }

            val byLabel = LinkedHashMap<String, MutableList<SymbolExport>>()
            conn.prepareStatement("SELECT name, kind, file_id FROM symbols WHERE kind <> 'import'").use { st ->
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        val file = files[rs.getLong("file_id")] ?: continue
                        val name = rs.getString("name") ?: continue
                        val label = modules.firstOrNull { it.path.isNotBlank() && file.startsWith(it.path) }?.name
                            ?: topSegment(file)
                        byLabel.getOrPut(capitalize(label)) { mutableListOf() }
                            .add(SymbolExport(rs.getString("kind") ?: "symbol", name, file))
                    }
                }
            }

            val communities = byLabel.map { (label, members) -> CommunityExport(label, members) }
            return CodeGraphExport(repo = null, source = "ast-index", communities = communities)
        }
    }

    private fun readFiles(conn: Connection): Map<Long, String> = buildMap {
        conn.prepareStatement("SELECT id, path FROM files").use { st ->
            st.executeQuery().use { rs -> while (rs.next()) put(rs.getLong("id"), rs.getString("path")) }
        }
    }

    private fun readModules(conn: Connection): List<Module> =
        runCatching {
            conn.prepareStatement("SELECT name, path FROM modules").use { st ->
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(Module(rs.getString("name"), rs.getString("path") ?: "")) }
                }
            }
        }.getOrDefault(emptyList())
}

private fun topSegment(path: String): String =
    path.split('/').firstOrNull { it.isNotBlank() && it != "." } ?: "code"

private fun capitalize(s: String): String = s.replaceFirstChar { it.uppercaseChar() }
