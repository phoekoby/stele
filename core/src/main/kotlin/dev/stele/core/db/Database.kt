package dev.stele.core.db

import java.sql.Connection
import java.sql.DriverManager

/**
 * Open (or create) a Stele graph database over sqlite-jdbc.
 * sqlite-vec is loaded via `loadExtension` from Phase 3 (vector search); not needed here.
 */
fun openDb(path: String): Connection {
    runCatching { Class.forName("org.sqlite.JDBC") }
    val conn = DriverManager.getConnection("jdbc:sqlite:$path")
    conn.createStatement().use { st ->
        st.execute("PRAGMA journal_mode=WAL")
        st.execute("PRAGMA foreign_keys=ON")
        st.execute("PRAGMA busy_timeout=3000") // wait, don't error, if another connection holds a lock (stele serve)
    }
    return conn
}
