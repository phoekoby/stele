package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import dev.stele.cli.requireDb
import dev.stele.core.db.openDb
import dev.stele.core.store.GraphStore

class DedupeCommand : CliktCommand(
    name = "dedupe-concepts",
    help = "Merge concepts that share a name (folder twins), folding duplicates into aliases",
) {
    override fun run() {
        val conn = openDb(requireDb().path)
        val res = GraphStore(conn).dedupeByName()
        conn.close()
        echo("✓ dedupe: merged ${res.merged} duplicate concepts across ${res.groups} names")
    }
}
