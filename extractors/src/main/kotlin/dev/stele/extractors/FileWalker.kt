package dev.stele.extractors

import java.io.File

private val IGNORE = setOf(
    "node_modules", ".git", "dist", "build", ".stele", ".next",
    "out", "target", "vendor", "__pycache__", ".venv", ".idea", ".gradle",
)

private const val MAX_BYTES = 1_000_000L // skip files larger than 1 MB

/** Recursively yield source file paths, skipping vendor dirs and large files. */
fun walk(dir: File): Sequence<File> = sequence {
    val entries = dir.listFiles() ?: return@sequence
    for (entry in entries) {
        if (entry.name in IGNORE) continue
        if (entry.isDirectory) {
            yieldAll(walk(entry))
        } else if (entry.isFile && entry.length() <= MAX_BYTES) {
            yield(entry)
        }
    }
}
