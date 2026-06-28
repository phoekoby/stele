package dev.stele.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import dev.stele.cli.commands.BuildOntologyCommand
import dev.stele.cli.commands.ConceptCommand
import dev.stele.cli.commands.DedupeCommand
import dev.stele.cli.commands.ExplainCommand
import dev.stele.cli.commands.GraphCommand
import dev.stele.cli.commands.IngestAstIndexCommand
import dev.stele.cli.commands.IngestCodeCommand
import dev.stele.cli.commands.IngestCodeGraphCommand
import dev.stele.cli.commands.IngestCommand
import dev.stele.cli.commands.IngestDocsCommand
import dev.stele.cli.commands.IngestSymbolsCommand
import dev.stele.cli.commands.IngestWebCommand
import dev.stele.cli.commands.InstallHookCommand
import dev.stele.cli.commands.McpCommand
import dev.stele.cli.commands.RefineRulesCommand
import dev.stele.cli.commands.ReviewCommand
import dev.stele.cli.commands.InitCommand
import dev.stele.cli.commands.StatsCommand
import dev.stele.cli.commands.SyncCommand
import dev.stele.cli.commands.TermsCommand
import dev.stele.cli.commands.UsageCommand

class SteleCommand : CliktCommand(
    name = "stele",
    help = "The product↔code Rosetta stone — a navigable concept graph for your codebase.",
    printHelpOnEmptyArgs = true,
) {
    override fun run() = Unit
}

fun main(args: Array<String>) =
    SteleCommand()
        .subcommands(
            InitCommand(),
            SyncCommand(),
            StatsCommand(),
            IngestCommand().subcommands(
                IngestCodeCommand(), IngestSymbolsCommand(), IngestDocsCommand(), IngestWebCommand(),
                IngestCodeGraphCommand(), IngestAstIndexCommand(),
            ),
            BuildOntologyCommand(),
            RefineRulesCommand(),
            DedupeCommand(),
            ReviewCommand(),
            TermsCommand(),
            ConceptCommand(),
            ExplainCommand(),
            GraphCommand(),
            McpCommand(),
            UsageCommand(),
            InstallHookCommand(),
        )
        .main(args)
