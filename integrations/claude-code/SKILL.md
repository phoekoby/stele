---
name: stele-context
description: Use before implementing or changing a feature in a codebase indexed by Stele. Fetches the product rules, domain concepts, and cross-language code map for the file or concept you are touching, so the change respects product invariants and lands in the right place. Trigger when the user describes a feature in business/domain language, or before editing a non-trivial file.
---

# Stele context

Stele indexes this repo into a concept graph — code ↔ docs ↔ **product rules**. Before you write code,
pull the relevant context from the `stele` MCP server. The rules it returns are team invariants that are
NOT visible in the code; honoring them is the point.

## Which tool, when

- **About to edit a specific file** → `context_for_code(path)` first. Returns the concept(s) the file
  implements, their **product rules**, related concepts, docs, and the `calls` neighborhood (what the
  file calls and who calls it — the blast radius). Read the rules before changing anything.
- **The request names a domain term** (e.g. "the subscription downgrade", "a secret", "the signing
  order", "resend the invite") → `concept_context(concept)`. Resolves the term (by name or alias) to its
  definition, product rules, related concepts, and the code that implements it **across languages and
  services** — so you locate the right place instead of grepping blind.
- **Unsure what a file is for** → `why_code(path)`.

## How to use the result

1. Read the **product rules** and treat them as hard constraints. Example shapes: "completed documents
   cannot be deleted", "a token is scoped to one team", "secrets are only decrypted on the reveal
   endpoint". Code that compiles but breaks one of these is wrong.
2. Use the **implemented-by file/symbol map** to find the correct code (and all the places that need to
   change — often more than one language/service) rather than guessing.
3. If a product rule **conflicts** with what the user asked, surface it and ask — do not silently break it.
4. If a tool returns "No concept …", the area may not be indexed yet — proceed normally and say so.

Cost is tiny (local SQLite, ~10ms/call). Prefer one focused `context_for_code` / `concept_context` over
exploring many files by hand.
