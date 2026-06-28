package dev.stele.cli

import dev.stele.connectors.AstIndexConnector
import dev.stele.connectors.CodeGraphConnector
import dev.stele.connectors.DocsConnector
import dev.stele.connectors.WebConnector
import dev.stele.core.connector.Connector
import dev.stele.extractors.CodeConnector
import dev.stele.extractors.SymbolsConnector

/** The connectors Stele ships with. Add a new source by appending one line here. */
object ConnectorRegistry {
    val all: List<Connector> = listOf(
        SymbolsConnector, CodeConnector,
        DocsConnector, WebConnector,
        CodeGraphConnector, AstIndexConnector,
    )

    private val byType = all.associateBy { it.type }

    operator fun get(type: String): Connector? = byType[type]

    val types: List<String> get() = all.map { it.type }
}
