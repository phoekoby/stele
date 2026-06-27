# Stele — project context for Claude Code

**Stele** turns a codebase into a navigable concept graph: it links **code ↔ documentation ↔ product (incl. Figma)** through a shared **ubiquitous-language ontology**, and serves that context to AI coding agents (via MCP) and to people. Tagline: *the product↔code Rosetta stone.*

## Core idea (do not lose this)
- The **concept / terminology layer (ontology)** is the spine and the IP — NOT the code graph (that is a commodity; reuse it, e.g. SCIP later).
- Figma / code / docs are **never linked directly** — each attaches to a shared **concept**; the cross-modal link is a path *through* the concept. This is what makes it language-agnostic and able to work on legacy codebases (Kotlin, Dart, anything).
- Retrieval is **ontology-first**: resolve a term -> traverse the typed subgraph -> drill to the artifact. Not flat vector top-k.
- Links are built by signals (deterministic anchors -> lexical -> embeddings -> LLM relation-typing) carrying **provenance + confidence**, then confirmed by a **human-in-the-loop**. That confirmed set is the moat.

## Architecture (4 layers + spine)
Sources (connectors) -> Ingest / self-update -> Artifacts (code / design / product / evidence) -> **Concept spine (ontology)** -> Retriever (resolve -> traverse -> drill) -> Serving (MCP / CLI / web).

## Stack & layout
**Kotlin / JVM (JDK 17+), Gradle (Kotlin DSL) — multi-module monorepo.** SQLite via `org.xerial:sqlite-jdbc` (native bundled in-jar) as the single local graph store; `sqlite-vec` via `loadExtension` later (Phase 3). Schema migrations via **Liquibase** (built-in SQLite support; master changelog includes per-change SQL files under `core/.../db/changelog/changes/`). Code parsing via **java-tree-sitter** (`io.github.bonede:tree-sitter` + `tree-sitter-<lang>` grammars, prebuilt natives in-jar — no C toolchain). CLI via **clikt**. MCP SDK from Phase 6.

```
settings.gradle.kts · build.gradle.kts · gradle/   # monorepo root (subprojects)
core/        # graph store + migrations + model (no parsing/CLI deps)
  src/main/kotlin/dev/stele/core/
    model/   Enums.kt · Entities.kt
    db/      Database.kt (openDb) · Migrations.kt (Liquibase migrate, schemaVersion)
    store/   GraphStore.kt · Json.kt
  src/main/resources/db/changelog/   db.changelog-master.xml + changes/NNN-*.sql
extractors/  # depends on :core; java-tree-sitter (cheap, language-agnostic token mentions)
  …/extractors/   Grammars · Normalize · FileWalker · Stopwords · CodeExtractor
  src/test/…       NormalizeTest · CodeExtractorTest
connectors/  # depends on :core; product/code sources → concept spine (kotlinx.serialization)
  …/connectors/codegraph/   resolved code-graph ingest (GitNexus/SCIP JSON export)
  …/connectors/docs/        Markdown product docs → `describes` edges (DocsIngest)
resolver/    # depends on :core; LLM concept canonicalization (Phase 3)
  …/resolver/   LlmClient · OllamaClient (local, default) · AnthropicClient (opt-in) · StaticLlmClient · Canonicalizer
mcp/         # depends on :core; MCP stdio server (Phase 6), hand-rolled JSON-RPC (no SDK)
  …/mcp/   McpServer (concept_context · why_code)
cli/         # depends on :core + :extractors + :connectors + :resolver + :mcp; clikt + application
  …/cli/   Main · Paths · commands/{Init,Stats,Ingest,BuildOntology,Dedupe,Terms,Concept,Mcp}Command
docs/        # build-spec, architecture, phase-1-spec, linking-design, validation-kit
```

Add a subproject: create `<name>/build.gradle.kts` (+ src), then `include(":<name>")` in `settings.gradle.kts`. Future modules: connectors, resolver, retriever, mcp-server.

### Three code-layer sources (all behind seams; the concept spine is the IP)
1. **`ingest code`** (`:extractors`) — java-tree-sitter token `mentions`. Cheap, language-agnostic, but **unresolved** (stdlib/import noise mixes with domain terms). Fallback only. *Why bonede not KTreeSitter:* KTreeSitter publishes no Maven grammars (builds from C via a plugin, needs a C toolchain); `io.github.bonede` ships `tree-sitter-<lang>` with prebuilt natives in-jar.
2. **`ingest symbols`** (`:extractors`, **embedded, default**) — tree-sitter **declarations** (function/type/method/class via the `name` field) → `code_symbol` artifacts, clustered by **feature folder** → candidate concepts (+ inferred `implements` edges, proposed). No external tool, no manual export — the in-house cousin of GitNexus/ast-index (same definition nodes, simpler folder clustering instead of call-graph communities).
3. **`ingest codegraph`** / **`ingest astindex`** (`:connectors`) — ingest a **resolved** code graph: communities→concepts, member symbols→artifacts, and **symbol↔symbol `calls`/`imports` edges** (resolved facts → `confirmed`).
   - `ingest codegraph <json>` — a JSON export (e.g. a hand-pulled GitNexus slice; on `sb0rka` gave 19 real `calls` edges unified with the concept graph, same symbol nodes).
   - `ingest astindex <index.db>` — the **durable, automated** adapter: reads an [ast-index](https://github.com/defendend/Claude-ast-index-search) SQLite (`~/…/ast-index/<hash>/index.db`) **directly** via sqlite-jdbc (no agent, no manual export). Built against the real schema (`docs/db-schema.md`): `symbols`(+`files`)→artifacts, `modules`(by file-path prefix)→candidate concepts, skips `import` rows. Verified on a fixture; once `ast-index rebuild` has run, `stele ingest astindex <db>` is one automated step.
   The code graph is a commodity — we consume it.

## Status (Kotlin port)
- Phase 0 done — schema + `stele init` (sqlite-jdbc; Liquibase migrations, `schema: v<changeset>` from DATABASECHANGELOG).
- Phase 1 done — `stele ingest code <path>` → token `mentions` (15 grammars; `tsx`/failed grammars → graceful skip).
- Phase 2.5 (concept spine bootstrap, done) — `stele ingest symbols <path>` (embedded tree-sitter indexer) and `stele ingest codegraph <export.json>` (external graph) both populate `concepts` + `code_symbol` artifacts + inferred `implements` edges (proposed). `stele concept <name>` shows the code behind a concept across languages/layers. On `sb0rka`: `ingest symbols` → 971 symbols / 42 concepts; `concept Auth` → 117 symbols across `apps/auth` (Go service), `apps/api`, `apps/s0c` and `apps/console` (TS) — one concept, three backends + frontend, fully automated.
- Phase 3 (concept canonicalization, done) — `stele build-ontology` (`:resolver`) sends candidate concepts + sample symbols to an LLM **in small batches** and applies the verdict: real concepts get canonical name + definition + bounded_context + aliases; infra/noise is dropped (confidence gate). **Local-first**: default backend is a local **Ollama** model (offline, free, OSS — `--model <name>`); cloud is opt-in (`--provider anthropic` + `ANTHROPIC_API_KEY`); `--responses <file>` replays a saved response. On `sb0rka`: **42 folder-clusters → 18 real concepts** with bounded contexts (IAM, Billing, Secrets Management, …); `Psql/Pgx/Misc/Router/Middlewares` dropped. `concept Authentication` now carries a definition + `bounded context: IAM`.
- Phase 2 (deterministic evidence: git/GitHub anchors + `stele why`) — still to do as part of `:connectors`.
  - Safety: a concept with **no verdict** is left as `candidate` (never deleted on a parse/LLM miss); a fully unparseable response **aborts** with no changes. Canonicalization is **incremental** — `candidateConcepts` only returns concepts with no definition yet, so re-running resolves the remainder (useful when a slow local model only finishes some batches per run).
  - Local-model reality: a 20B reasoning model (gpt-oss) on modest hardware is slow (~14 min/43) and returns parseable JSON for only some batches per run — re-run to fill in, use smaller `--batch`, or a faster/JSON-reliable model. Cloud (`--provider anthropic`) is one clean pass.
- Concept **dedup** (done) — `stele dedupe-concepts` (`GraphStore.dedupeByName`, deterministic, no LLM) merges same-named concepts (folder twins like `Users`+`User`): keeps the best survivor (has-definition, then most edges), repoints `implements` edges, folds the others into aliases, deletes them. On `sb0rka`: 20 → 16 concepts, `User/Project/Resource/Secret` unified.
- Product layer (the *other* side of the bridge, done) — `stele ingest docs <path>` (`:connectors` docs) walks Markdown, splits by heading into sections, and links each to the concept(s) it mentions by **name/alias** → `describes` edges (deterministic, `proposed`, confidence 0.9 heading / 0.6 body; only sections that touch a concept are kept). This grounds concepts in the team's **product language**, so `concept_context`/`concept` return the product prose alongside the code. It also **enriches aliases** (a heading containing a concept term, e.g. "Live session", becomes an alias → plain product phrases resolve), derives **concept↔concept `relates` edges** (concepts co-described in ≥2 sections), and extracts **product rules** (`rule` artifacts + `constrains` edges) from constraint sentences ("must", "only", "deny-by-default", "в течение N…"). So a concept is now a real knowledge node: definition + product language + related concepts + **rules** + docs + code — `concept_context`/`concept` serve all of it. On `sb0rka`: 38 docs / 156 sections / 348 `describes` / 17 aliases / 53 relations / 79 rules; `concept_context("Authorization")` → RBAC definition + related concepts + rules ("deny-by-default; a new action requires a matrix entry") + docs + Go/TS code. **Rule extraction is hardened** (`isProseRule`, unit-tested): rules are taken **only from sections a concept owns by heading** (not weak body mentions), and markdown table rows / URLs / schema column-defs are dropped — on `sb0rka` 79→34 rules, all genuine. *Caveat: doc matching and relations (co-occurrence) are still liberal `proposed` edges; the serving gate + Phase 5 confirmation prune them at read time.*
- Phase 6 (MCP serving, done) — `stele mcp` runs a hand-rolled MCP stdio server (newline-delimited JSON-RPC 2.0; `initialize`/`tools/list`/`tools/call`). Tools: **`concept_context(concept)`** (resolve by name/alias → definition + bounded_context + related concepts + product rules + docs + implementing code across languages), **`why_code(path)`** (reverse: file/dir → the concept(s) it implements), and **`context_for_code(path)`** (in-editor: the file's concept(s) + their rules + related + docs + symbols, **plus the `calls` neighborhood** — what the file's symbols call out to and who calls into them, i.e. the impact/blast-radius view; call before changing code to respect product rules; also `stele explain <path>`). stdout is the JSON-RPC stream only. Register in an MCP client (Claude Code: `claude mcp add stele -- <abs>/stele mcp`, run from the indexed repo so `.stele/graph.db` is found). Verified on `sb0rka`: `concept_context("Auth")` → Authentication/IAM + 116 symbols / 23 files (Go+TS). **Usage telemetry:** every tool call is appended to `.stele/usage.jsonl` (`UsageLog`); `stele usage` reports calls-by-tool, resolve hit-rate, **context served** (KB / ~tokens of curated context pulled vs discovered — a proxy for saved exploration), avg latency, top queries. Turnkey Claude Code setup (MCP + a `stele-context` skill + the metrics) in `integrations/claude-code/`. **`stele init` wires Claude Code automatically**: drops the `stele-context` skill into `.claude/skills/` and **merges** a clean `stele` server into the project `.mcp.json` (the install's own launcher — `…/bin/stele.bat` — + `mcp --db <abs>/.stele/graph.db`), keeping any other servers already there (`--no-skill`/`--no-mcp` opt out). The MCP server resolves the graph by **absolute `--db`** path and **never aborts on a missing graph** (creates+migrates an empty one, serve loop is exception-guarded) — so `tools/list` always succeeds regardless of the client's working directory. *(This fixed an "empty server" failure where launching `stele mcp` outside the repo aborted on `requireDb()` before listing tools.)*
- Phase 5 (human-in-the-loop confirmation, done) — `stele review` turns the broad deterministic `proposed` graph into a curated one: interactive `y/n/s/q` per edge (with endpoint labels + evidence), or bulk `--accept-above <conf>` / `--type <t>`. Sets `proposed`→`confirmed`/`rejected`; **stats** shows edges by status. The confirmed set + rejections are the moat. On `sb0rka`: bulk-confirmed 602 `implements`; rejecting `User—relates→Secret` dropped it from both concepts' served `related`.
- Serving quality gate (done) — `GraphStore.served(minConfidence)` is the single bar every serving query (`describingDocs`/`relatedConcepts`/`rulesFor`/`implementersOf`/`conceptsForPath`) passes through: a human-`confirmed` edge always shows; a `proposed` edge shows only above a per-type bar (`Gate`: describes 0.9 = heading-only, relates 0.7, implements/constrains 0.0). So the noisy deterministic-first proposals stay out of agent context until confirmed; `rejected` never shows (also fixed `conceptsForPath`, which had no status filter at all). The gate also extends to **nodes**: `relatedConcepts` serves only **resolved** concepts (those canonicalization gave a definition) — so un-canonicalized leftovers (`Mock`/`Routes`) never surface as neighbors. On `sb0rka` `concept Authentication`: served `describes` 62→8, `related` 11→7, `implements` 116 unchanged. Covered by `core` `GraphStoreServingTest`. *Hard-deleting leftover candidate concepts is canonicalization's job, not a deterministic prune — `Invite` (a real concept) is metric-indistinguishable from `Mock`; only the LLM re-run can tell them apart.*
- Tests: `extractors` (Normalize, CodeExtractor), `connectors` (`RuleExtractionTest` — `isProseRule` keeps real rules / drops tables+URLs+schema), `core` (`GraphStoreServingTest` — describes/relates gate + resolved-concept filter + `callsForPath`). Run `./gradlew test`.
- Known gaps: ast-index `refs`→`calls` (loose/name-based, so not yet ingested — calls come from GitNexus today) + `inheritance`→edges; verify `ingest astindex` against a real ast-index DB (built+fixture-tested against the schema, but ast-index isn't installed here); Phase 2 evidence (git/PR anchors + `stele why`); LLM relation-typing (`relates`→depends-on/part-of); incremental `build-ontology` re-run to resolve leftover candidate concepts (`Mock`/`Routes`/`Invite`); Figma connector (Phase 4); vector search (sqlite-vec).

## Run
```
./gradlew build                          # compile all modules + tests (gradlew.bat on Windows)
./gradlew :cli:installDist               # -> cli/build/install/stele/bin/stele
./gradlew :cli:shadowJar                 # -> cli/build/libs/stele.jar (single fat jar, `java -jar`); needs Java 17+
./install.ps1  /  ./install.sh           # build + put `stele` on PATH (one command). Distribution templates: packaging/
cli/build/install/stele/bin/stele init
cli/build/install/stele/bin/stele ingest symbols .              # embedded: declarations → concepts (default)
cli/build/install/stele/bin/stele ingest code .                 # token mentions (language-agnostic fallback)
cli/build/install/stele/bin/stele ingest codegraph <export.json> # external resolved graph (GitNexus JSON)
cli/build/install/stele/bin/stele ingest astindex <index.db>    # durable: read ast-index SQLite directly (automated)
cli/build/install/stele/bin/stele ingest docs .                 # product layer: Markdown docs → concepts (describes)
cli/build/install/stele/bin/stele ingest web <url> [<url>…]     # external pages (Jira/Confluence/any URL) → concepts
cli/build/install/stele/bin/stele build-ontology --model <ollama-model>   # Phase 3: LOCAL LLM (Ollama, offline/free) canonicalizes concepts
cli/build/install/stele/bin/stele build-ontology --provider anthropic     # opt-in cloud (needs ANTHROPIC_API_KEY)
cli/build/install/stele/bin/stele refine-rules --model <ollama-model>     # curate scraped product rules → invariants (keep/rewrite/drop noise)
cli/build/install/stele/bin/stele dedupe-concepts               # merge same-named concepts (folder twins) → aliases
cli/build/install/stele/bin/stele review --type relates         # Phase 5: human confirm/reject (or --accept-above 0.6 bulk)
cli/build/install/stele/bin/stele concept <Name>                # code behind a concept (cross-language)
cli/build/install/stele/bin/stele explain <path>                # in-editor context for a file: concept + rules + docs + symbols
cli/build/install/stele/bin/stele mcp                           # Phase 6: MCP server (concept_context / why_code / context_for_code)
cli/build/install/stele/bin/stele usage                         # MCP usage telemetry: calls / hit-rate / context served
cli/build/install/stele/bin/stele stats   /   terms
# or, without installing:  ./gradlew :cli:run --args="concept Auth"
```

## Conventions
- Deterministic first, inference on top. Inferred links are proposals; humans confirm.
- Keep everything behind seams (extractors, graph-store) so SQLite->Kuzu and tree-sitter->SCIP are swappable.
- Keep the MVP narrow. Do NOT build federation or a web UI early.
- Comments: only the non-obvious "why". No restating code.

## Roadmap
- Phase 2 — evidence: git + GitHub Issues; deterministic anchors code<->PR<->issue. (pending Kotlin port as `:connectors`)
- Phase 3 — concept spine: candidates -> canonicalize (LLM) -> link artifact->concept + evidence-bridge -> confidence gate.
- Phase 4 — Figma (REST API): frames / text -> concepts; conceptual code<->Figma link via the concept.
- Phase 5 — confidence gate + human confirmation loop.
- Phase 6 — MCP server tools: `concept_context`, `why_code`.
- Phase 7+ — retriever polish, web graph UI, federation (system.graph.yaml).

Full detail in `docs/build-spec.md`, `docs/phase-1-spec.md`, `docs/architecture.md`, `docs/linking-design.md`.
