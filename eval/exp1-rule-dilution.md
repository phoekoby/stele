# Exp1 — scoped rule vs `cat all_rules.md` (the cheapest baseline). NEGATIVE RESULT.

The external review's #1 objection: rule-compliance was never tested against the cheapest
alternative — putting **all** rules in the system prompt (the industry standard:
CLAUDE.md / .cursorrules / AGENTS.md). This experiment measures it. **We publish the result
although it goes against us.**

## Hypothesis (as stated before running)

Prompt-stuffing degrades as the rule corpus grows (context dilution hurts instruction-
following); a scoped, concept-routed rule (Stele) stays flat. The crossover point =
the ICP boundary (at what org rule-count Stele overtakes `cat rules.md`).

## Setup

| | |
|---|---|
| Rule corpus | all 159 clean rules from the Documenso graph (post P0-4 extractor fix) |
| Tasks | 8 trap tasks; each rule is served by Stele, T1/T5 additionally ground-truth-verified as enforced in Documenso source |
| Arms | `plain` (0 rules) · `stele` (1 scoped rule) · `cat10` / `cat40` / `cat159` (the same rule + 9/39/158 distractors, shuffled seed=42) |
| Model | Haiku 4.5 (the small-model persona), K=3 samples/cell → 120 cells |
| Injection | ALL arms injected (isolates the payload variable; the live tool-call loop was separately proven in P2) |
| Scoring | intent-tolerant static checks (accepts semantic equivalents), `exp1-score.py` |

## Result

```
task                            plain    stele    cat10    cat40   cat159
T1_delete_completed               0/3      3/3      3/3      3/3      3/3
T2_owner_visibility               3/3      3/3      3/3      3/3      3/3
T3_send_needs_field               2/3      3/3      3/3      3/3      3/3
T4_expiration_after_sent          0/3      3/3      3/3      3/3      3/3
T5_recipient_after_sent           0/3      3/3      3/3      3/3      3/3
T6_recipient_expiry_pending       1/3      2/3      3/3      3/3      2/3
T7_token_team_scope               3/3      3/3      3/3      3/3      3/3
T8_org_transfer_admin             2/3      3/3      3/3      3/3      3/3
ALL                               46%      96%     100%     100%      96%

discriminating tasks only (plain fails, rule matters): T1, T4, T5, T6
compliance                         8%      92%     100%     100%      92%
```

**The dilution curve is FLAT.** `cat` with all 159 rules (~3k tokens) scores the same as the
single scoped rule. On a corpus of Documenso's size, a modern small model finds the relevant
rule among 159 just as well as when it is handed exactly one.

## Honest verdict

1. **The context-injection mechanic is NOT a differentiator at this corpus size.** By the
   reviewer's own bar ("if Stele loses to cat rules.md, there is no product — just a script"):
   on Documenso, scoped delivery ties prompt-stuffing; it does not beat it.
2. What remains is **token efficiency only** (~20 tokens vs ~3k per call at equal compliance) —
   the same weak "parity but cheaper" story as the Q&A axis.
3. **The crossover, if it exists, is above 159 rules** for Haiku 4.5. Finding it needs a
   synthetic extension (300/600/1200 rules) and/or weaker models. Until then, the honest
   ICP statement is: organisations whose rule corpus is far larger than Documenso's, or
   pipelines running models weaker than today's small tier.
4. **Consequence for the product:** the weight shifts from *pre-hoc context injection* (MCP)
   to what prompt-stuffing cannot do at any corpus size:
   - **drift-on-PR / CI audit** — post-hoc "this diff touches concept X, rule Y → VIOLATION,
     source: docs/…" (no reliance on the agent's cooperation; auditable);
   - **rule extraction + maintenance itself** — `cat rules.md` presupposes someone wrote and
     curates rules.md; Stele mines the rules from docs (and, reverse-drift, from code),
     keeps them attached to concepts/code, and flags staleness. The corpus the baseline
     consumes is the thing Stele produces.

## What survives from earlier results

- ast-index ≤ plain < rules (p=0.031): code STRUCTURE carries no rules — still true.
- P2 live loop: a small agent pulls and honors the rule — still true.
- What's new: *how the rule gets into context* (scoped vs all) doesn't matter at N≤159 on
  a modern small model.

## Reproduce

`exp1-workflow.js` (the cell generator; 120 subagent calls) + `exp1-score.py` (checkers +
curve). Corpus: `sqlite3 .stele/graph.db "SELECT DISTINCT a.title FROM artifacts a JOIN edges e
ON e.src_id=a.id AND e.type='constrains' WHERE a.kind='rule' AND LENGTH(a.title) BETWEEN 20 AND 160"`.
