package dev.stele.cli

import com.github.ajalt.clikt.core.PrintMessage
import java.io.File

private const val STELE_DIR = ".stele"
private const val DB_FILE = "graph.db"

fun steleDir(): File = File(System.getProperty("user.dir"), STELE_DIR)

fun dbFile(): File = File(steleDir(), DB_FILE)

/** Path to an existing graph db, or abort with a hint to run `stele init`. */
fun requireDb(): File {
    val db = dbFile()
    if (!db.exists()) {
        throw PrintMessage(
            "No .stele graph found here. Run `stele init` first.",
            statusCode = 1,
            printError = true,
        )
    }
    return db
}
