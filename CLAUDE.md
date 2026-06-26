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
TypeScript, pnpm workspace. SQLite (`better-sqlite3`) + `sqlite-vec` as the single local graph+vector store (zero-infra, local-first). tree-sitter (WASM) for code parsing. MCP SDK (`@modelcontextprotocol/sdk`) from Phase 6.

```
packages/core         # schema, GraphStore (SQLite), types
packages/extractors   # tree-sitter code extractor (Phase 1)
apps/cli              # the `stele` command
docs/                 # build-spec.md, architecture.md, phase-1-spec.md, linking-design.md, validation-kit.md
```

## Status
- Phase 0 done — schema + `stele init`.
- Phase 1 done — `stele ingest code <path>` extracts identifiers / strings / comments into `mentions` (any language via tree-sitter). Pinned `web-tree-sitter@0.20.8` + `tree-sitter-wasms@0.1.13` (same ABI). Dart temporarily disabled (its grammar in this pack is a newer ABI); revisit with `@repomix/tree-sitter-wasms` + modern web-tree-sitter later.
- Next: Phase 2.

## Run
```
pnpm install
pnpm stele init
pnpm stele ingest code .
pnpm stele stats
pnpm stele terms
```

## Conventions
- Deterministic first, inference on top. Inferred links are proposals; humans confirm.
- Keep everything behind interfaces (extractors, graph-store) so SQLite->Kuzu and tree-sitter->SCIP are swappable.
- Keep the MVP narrow. Do NOT build federation or a web UI early.

## Roadmap
- Phase 2 — evidence: git + GitHub Issues; deterministic anchors code<->PR<->issue.
- Phase 3 — concept spine: candidates -> canonicalize (LLM) -> link artifact->concept + evidence-bridge -> confidence gate.
- Phase 4 — Figma (REST API): frames / text -> concepts; conceptual code<->Figma link via the concept.
- Phase 5 — confidence gate + human confirmation loop.
- Phase 6 — MCP server tools: `concept_context`, `why_code`.
- Phase 7+ — retriever polish, web graph UI, federation (system.graph.yaml).

Full detail in `docs/build-spec.md`, `docs/phase-1-spec.md`, `docs/architecture.md`, `docs/linking-design.md`.
