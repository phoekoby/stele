#!/usr/bin/env node
import { existsSync, mkdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { Command } from "commander";
import { GraphStore, migrate, openDb, schemaVersion } from "@stele/core";
import { ingestCode } from "@stele/extractors";

const STELE_DIR = ".stele";
const DB_FILE = "graph.db";

function steleDir(cwd = process.cwd()): string {
  return join(resolve(cwd), STELE_DIR);
}
function dbPath(cwd = process.cwd()): string {
  return join(steleDir(cwd), DB_FILE);
}

const program = new Command();

program
  .name("stele")
  .description("The product↔code Rosetta stone — a navigable concept graph for your codebase.")
  .version("0.0.0");

program
  .command("init")
  .description("Create the .stele graph database in the current directory")
  .action(() => {
    const dir = steleDir();
    if (!existsSync(dir)) mkdirSync(dir, { recursive: true });

    const path = dbPath();
    const db = openDb(path);
    migrate(db);
    const counts = new GraphStore(db).counts();
    const version = schemaVersion(db);
    db.close();

    console.log(`✓ Stele initialized`);
    console.log(`  db:     ${path}`);
    console.log(`  schema: v${version}`);
    console.log(
      `  graph:  ${counts.concepts} concepts · ${counts.artifacts} artifacts · ${counts.mentions} mentions · ${counts.edges} edges`,
    );
  });

program
  .command("stats")
  .description("Show graph counts")
  .action(() => {
    const path = dbPath();
    if (!existsSync(path)) {
      console.error("No .stele graph found here. Run `stele init` first.");
      process.exit(1);
    }
    const db = openDb(path);
    const counts = new GraphStore(db).counts();
    db.close();
    console.table(counts);
  });

const ingest = program
  .command("ingest")
  .description("Pull data from sources into the graph");

ingest
  .command("code <path>")
  .description("Parse a repo (any language) and extract code mentions")
  .action(async (path: string) => {
    const p = dbPath();
    if (!existsSync(p)) {
      console.error("No .stele graph found here. Run `stele init` first.");
      process.exit(1);
    }
    const db = openDb(p);
    const res = await ingestCode(new GraphStore(db), path);
    db.close();
    console.log(`✓ ingested ${res.files} files, ${res.mentions} mentions`);
    if (res.skippedLangs.length > 0) {
      console.log(`  skipped incompatible grammars: ${res.skippedLangs.join(", ")}`);
    }
  });

program
  .command("terms")
  .description("Show the most common normalized terms")
  .action(() => {
    const p = dbPath();
    if (!existsSync(p)) {
      console.error("No .stele graph found here. Run `stele init` first.");
      process.exit(1);
    }
    const db = openDb(p);
    console.table(new GraphStore(db).topTerms(30));
    db.close();
  });

program.parseAsync(process.argv).catch((err) => {
  console.error(err);
  process.exit(1);
});
