package dev.stele.extractors

import dev.stele.core.connector.Connector
import dev.stele.core.connector.ConnectorParams
import dev.stele.core.store.GraphStore

object SymbolsConnector : Connector {
    override val type = "symbols"
    override val help = "tree-sitter declarations → candidate concepts (incremental)"
    override fun ingest(store: GraphStore, params: ConnectorParams): String {
        val r = ingestSymbols(store, params.path ?: ".")
        return "${r.changed}/${r.files} files re-parsed → ${r.symbols} symbols, ${r.concepts} concepts, ${r.links} links"
    }
}

object CodeConnector : Connector {
    override val type = "code"
    override val help = "token mentions (language-agnostic fallback)"
    override fun ingest(store: GraphStore, params: ConnectorParams): String {
        val r = ingestCode(store, params.path ?: ".")
        return "${r.files} files, ${r.mentions} mentions"
    }
}
