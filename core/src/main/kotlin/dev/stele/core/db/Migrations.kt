package dev.stele.core.db

import liquibase.Contexts
import liquibase.Liquibase
import liquibase.Scope
import liquibase.UpdateSummaryEnum
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import liquibase.ui.LoggerUIService
import java.sql.Connection
import java.util.logging.Level
import java.util.logging.Logger

/** Master changelog (classpath); it includes per-change files under changes/. */
private const val CHANGELOG = "db/changelog/db.changelog-master.xml"

/**
 * Bring the schema up to date with Liquibase. Runs against the caller's
 * connection, which is left open. Applied changesets are tracked in
 * DATABASECHANGELOG (replacing the old hand-rolled schema_meta version row).
 */
fun migrate(conn: Connection) {
    System.setProperty("liquibase.analytics.enabled", "false")
    Logger.getLogger("liquibase").level = Level.WARNING

    // Route Liquibase's UI (changeset chatter) to the logger (pinned at WARNING)
    // and turn off the UPDATE SUMMARY block — keeps `stele init` output clean.
    Scope.child(mapOf(Scope.Attr.ui.name to LoggerUIService()), Scope.ScopedRunner<Unit> {
        val database = DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(JdbcConnection(conn))
        Liquibase(CHANGELOG, ClassLoaderResourceAccessor(), database).apply {
            setShowSummary(UpdateSummaryEnum.OFF)
        }.update(Contexts())
    })
    conn.autoCommit = true
}

/** Latest applied changeset id (the "schema version"), or null if unmigrated. */
fun schemaVersion(conn: Connection): String? =
    runCatching {
        conn.prepareStatement(
            "SELECT id FROM databasechangelog ORDER BY orderexecuted DESC, dateexecuted DESC LIMIT 1",
        ).use { st ->
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }.getOrNull()
