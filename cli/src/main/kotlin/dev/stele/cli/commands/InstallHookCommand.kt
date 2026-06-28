package dev.stele.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import java.io.File

/**
 * Install git hooks that keep the concept index fresh automatically. Because
 * `ingest symbols` is now incremental (only changed files are re-parsed), the
 * hook is cheap to run on every commit/merge/checkout. Relies on `stele` being
 * on PATH (the installer puts it there).
 */
class InstallHookCommand : CliktCommand(
    name = "install-hook",
    help = "Install git hooks so the index re-indexes changed files on commit/merge/checkout",
) {
    private val line = "stele ingest symbols . >/dev/null 2>&1 || true"

    override fun run() {
        val gitDir = File(".git")
        if (!gitDir.isDirectory) {
            echo("Not a git repo here (no .git). Run from the repo root.")
            return
        }
        val hooksDir = File(gitDir, "hooks").apply { mkdirs() }
        var installed = 0
        for (hook in listOf("post-commit", "post-merge", "post-checkout")) {
            val f = File(hooksDir, hook)
            when {
                f.exists() && f.readText().contains("stele ingest symbols") -> continue // already there
                f.exists() -> f.appendText("\n# stele: keep the concept index fresh\n$line\n")
                else -> {
                    f.writeText("#!/bin/sh\n# stele: keep the concept index fresh (incremental)\n$line\n")
                    f.setExecutable(true)
                }
            }
            installed++
        }
        echo(
            if (installed == 0) "✓ stele hooks already installed — index stays fresh on commit/merge/checkout."
            else "✓ installed stele freshness hook into $installed git hook(s). The index now re-indexes changed files automatically.",
        )
    }
}
