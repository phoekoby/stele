# Live-agent loop (P2) — does an agent PULL the rule and HONOR it?

The prior rule-compliance eval (`eval/README.md`, `eval/PROTOCOL.md`) **injected** the product
rule into the agent's prompt. Its own threats-to-validity called that out: a real agent must
*decide to call the tool*, get the *full served slice* (not a hand-picked rule), and apply it.
This run closes that gap — the agent retrieves the rule itself, live, via Stele.

## Setup

| | |
|---|---|
| Repo under test | [Documenso](https://github.com/documenso/documenso) (TypeScript, Prisma) |
| Graph | `ingest symbols` → canonicalize → `ingest docs` (rules re-extracted with the P0-4 fix — 160 clean rules) → `embed` (nomic) |
| Agent under test | **Haiku 4.5** — deliberately a SMALL model (the persona the thesis targets) |
| Retrieval | the agent runs `stele ask --context-only "<question>"` itself and reads the served slice; it decides whether to call it |
| Arms | **stele** (told it *may* run the one `stele ask` command for product rules) vs **plain** (stub only, no tool, no repo reads) |
| Scoring | intent-tolerant static check per task (accepts semantic equivalents, e.g. Documenso's real `isDocumentCompleted` helper) |
| Samples | K=3 per cell on the discriminating tasks |

Both arms implement a function body from a stub only; the **only** difference is whether the
`stele ask` rule is available. Neither arm greps the codebase, so the isolated variable is the rule.

## Tasks (rules Stele genuinely serves)

Candidate traps were derived from rules the graph actually serves. Of six, **four** surfaced their
rule for a natural question; **two did not** (an honest retrieval-gap finding — see below), so the
compliance test uses the four that do.

| id | task | product rule (served by Stele) | check |
|---|---|---|---|
| T1 | `deleteDocument(id)` | completed documents cannot be deleted | guards on completion before delete |
| T2 | `canViewDocument(doc, user)` for `visibility==='ADMIN'` | the **owner** can still view an Admins-only doc | owner exception present |
| T5 | `updateRecipient(doc, id, email)` | recipients **cannot be changed after the document is sent** | guards on sent/non-draft status |
| T6 | `onRecipientExpired(recipient)` | recipient expiry does **not** expire the document | does not set the document status to EXPIRED |

## Results

```
task                         arm A (stele)   arm B (plain)   discriminating?
T1 delete-completed             3/3 PASS        0/3 PASS       YES  (non-obvious rule)
T5 recipient-after-send         3/3 PASS        0/3 PASS       YES  (non-obvious rule)
T2 owner-view                   PASS            PASS           no — Haiku adds the owner check anyway
T6 recipient-expiry             PASS            PASS           no — Haiku doesn't touch the document anyway

tool-call rate, arm A: 100% (every stele agent ran `stele ask` and applied the served rule)
tool-call rate, arm B: 0% (by design)
```

Representative outputs (verbatim):
- **T1 arm A** → `if (document.status === 'COMPLETED') throw new Error('Completed documents cannot be deleted')` — and used Documenso's *real* `isDocumentCompleted` / soft-delete pattern, learned from the served CODE-DOES slice.
- **T1 arm B** → `return prisma.document.delete({ where: { id } })` — no guard, every sample.
- **T5 arm A** → `if (document.status !== 'DRAFT') throw new Error('Cannot change recipients after send')`.
- **T5 arm B** → `prisma.recipient.update({ ... })` — no guard, every sample.

## What this shows (and what it doesn't)

**Closes the threat-to-validity.** The one thing the injected eval couldn't show — that a live agent
*decides to call the tool*, receives the real served slice, and *honors* the rule — is demonstrated:
100% of the small-model agents pulled the rule and applied it, 3/3 consistently, 0 regressions.

**Reproduces the compliance gap on non-obvious rules.** On the genuinely team-specific rules
(delete-completed, recipient-immutable-after-send) the plain agent violates every time and the Stele
agent complies every time. On common-sense-ish rules (owner-can-view, don't-expire-the-doc) Haiku
complies without the tool — the same dilution the injected eval reported. The value is precisely on
the non-obvious, team-specific rules a code-graph structurally can't carry.

**Not a standalone significance claim.** Only two tasks discriminate here (a function of which rules
surfaced), so this run is a *qualitative* live-loop confirmation, not new statistical power — it
combines with the injected multi-repo run (41 tasks, ast-index ≤ plain < Stele, McNemar p=0.031).

**Honest retrieval gap.** 2 of 6 candidate traps (send-requires-signature-field; expiration-immutable-
after-send) did **not** surface their rule for a natural question — the resolver picked the wrong
concept or the rule wasn't in the served top-k. Improving question→rule recall is the next retrieval
work; today the loop only helps when the right rule is actually served.

## Ground-truth check (are the rules real?)

The experiment is only valid if the trap rules are genuinely enforced in Documenso's **real code**
(not doc marketing) — so that arm B's naive output is a true bug and arm A's guard matches reality.
Verified against the actual source:

- **T1** — `packages/lib/server-only/document/delete-document.ts`: `import { isDocumentCompleted }`
  (L11), `if (isDocumentCompleted(envelope.status))` soft-deletes instead of deleting (L146),
  and the hard-delete path filters `status: { not: COMPLETED }` (L189). The real code enforces it.
  Arm A independently reproduced this exact `isDocumentCompleted` + soft-delete pattern; arm B's
  `prisma.document.delete()` would hard-delete a completed document — a genuine bug.
- **T5** — `packages/lib/server-only/recipient/update-envelope-recipients.ts` throws
  `'Envelope already complete'` (L76) and `'Cannot modify a recipient who has already interacted
  with the document'` (L113). Arm A's `status !== 'DRAFT'` guard matches the invariant; arm B's
  plain update is a genuine bug.

So the served rules are real product invariants the Documenso engineers actually coded — Stele
surfaced them from the docs, and the small model pulled them and reproduced the real guard.

## Reproduce

1. Build the Documenso graph (symbols → canonicalize → docs → embed nomic).
2. For each task, run the agent-under-test (a small model) twice: with the instruction that it may
   run `stele ask --context-only "<question>"`, and without. Capture the emitted code.
3. Score with the intent check. The `stele ask` questions are in the task table above.
