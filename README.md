# Stele

**The product↔code Rosetta stone — give your AI agent a developer's full context.**

The Rosetta Stone is a *stele*: one slab carrying the same decree in three scripts. Stele does the same for software — one navigable graph carrying the meaning of your **product**, **code**, and **design**, joined by a shared concept layer (the ubiquitous language).

Structural code graphs tell an agent *what* the code is. Stele adds the *why* — the product rules, the Figma screens, the decisions behind it — and serves it to coding agents (Cursor, Claude Code) and to your team, via MCP.

> Status: **Phase 0** (skeleton). See `Rosetta-build-spec.md` for the full architecture and the phased plan.

---

## How it works (one line)

Sources → ingest → **concept spine (ontology)** with code / design / product / evidence hanging off it → `resolve → traverse → drill` retrieval → MCP / CLI.

The key idea: Figma and code are **never linked directly** — both attach to a shared *concept*, so the link is language-agnostic and works on legacy (Kotlin, Dart, anything).

---

## Quickstart (Phase 0)

Requires Node ≥ 20 and [pnpm](https://pnpm.io).

```bash
pnpm install
pnpm stele init     # creates ./.stele/graph.db and runs migrations
pnpm stele stats    # shows graph counts (all zero for now)
```

Expected output of `init`:

```
✓ Stele initialized
  db:     .../.stele/graph.db
  schema: v1
  graph:  0 concepts · 0 artifacts · 0 mentions · 0 edges
```

That's the Phase 0 Definition of Done: the graph database is created and queryable.

---

## Repository layout

```
stele/
  packages/
    core/        # schema, graph store (SQLite + sqlite-vec), types
  apps/
    cli/         # the `stele` command
```

Coming next (see build spec):

```
  packages/
    connectors/  # git, github, figma, docs, config
    extractors/  # tree-sitter (any language), figma text, doc parse
    resolver/    # candidates → LLM relation typing → confidence gate → human loop
    retriever/   # ontology-first: resolve → traverse → drill
  apps/
    mcp-server/  # concept_context / why_code tools for agents
    web/         # graph view + curation
```

---

## Roadmap

- **Phase 0** — skeleton + data model ← *you are here*
- **Phase 1** — code → mentions (any language, tree-sitter)
- **Phase 2** — evidence (git + GitHub Issues) + deterministic anchors
- **Phase 3** — concept spine (candidates → canonicalize → link → gate)
- **Phase 4** — Figma + conceptual (language-agnostic) link
- **Phase 5** — confidence gate + human confirmation loop
- **Phase 6** — MCP serving (`concept_context`, `why_code`)
- **Phase 7+** — retriever polish, web UI, federation

---

## Tech

TypeScript · SQLite (`better-sqlite3`) + `sqlite-vec` · tree-sitter (Phase 1) · MCP SDK (Phase 6). Local-first and zero-infra by design — the whole graph is a single file under `.stele/`.

## License

MIT
