package dev.stele.connectors

import dev.stele.connectors.codegraph.AstIndexSource
import dev.stele.connectors.codegraph.JsonCodeGraphSource
import dev.stele.connectors.codegraph.ingestCodeGraph
import dev.stele.connectors.docs.ingestDocs
import dev.stele.connectors.docs.ingestWeb
import dev.stele.core.connector.Connector
import dev.stele.core.connector.ConnectorParams
import dev.stele.core.connector.ConnectorPhase
import dev.stele.core.store.GraphStore

object DocsConnector : Connector {
    override val type = "docs"
    override val help = "Markdown product docs → concepts (describes, rules, relations)"
    override val phase = ConnectorPhase.DOC
    override fun ingest(store: GraphStore, params: ConnectorParams): String {
        val r = ingestDocs(store, params.path ?: ".")
        return "${r.docs} docs, ${r.sections} sections, ${r.links} describes, ${r.rules} rules"
    }
}

object WebConnector : Connector {
    override val type = "web"
    override val help = "external pages (Jira/Confluence/any URL) → concepts"
    override val phase = ConnectorPhase.DOC
    override fun ingest(store: GraphStore, params: ConnectorParams): String {
        if (params.urls.isEmpty()) return "no urls configured"
        val r = ingestWeb(store, params.urls)
        return "${r.docs} pages, ${r.sections} sections, ${r.links} describes, ${r.rules} rules"
    }
}

object CodeGraphConnector : Connector {
    override val type = "codegraph"
    override val help = "resolved code graph (GitNexus/SCIP JSON export)"
    override fun ingest(store: GraphStore, params: ConnectorParams): String {
        val p = params.path ?: return "no path configured"
        val r = ingestCodeGraph(store, JsonCodeGraphSource(p).load())
        return "${r.symbols} symbols, ${r.concepts} concepts, ${r.calls} calls across ${r.files} files"
    }
}

object AstIndexConnector : Connector {
    override val type = "astindex"
    override val help = "ast-index SQLite (read directly) → symbols + concepts"
    override fun ingest(store: GraphStore, params: ConnectorParams): String {
        val p = params.path ?: return "no path configured"
        val r = ingestCodeGraph(store, AstIndexSource(p).load())
        return "${r.symbols} symbols, ${r.concepts} concepts across ${r.files} files"
    }
}
