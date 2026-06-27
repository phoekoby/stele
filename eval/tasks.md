# Eval tasks

Each task: a prompt + a Go code stub given to both arms, the product rule it must respect, and the
Stele context block injected only in the `with` arm (the cleaned `concept_context` output for that area).

Candidate prompt template (both arms):
> You are a senior Go backend engineer on the "Sb0rka" platform. Complete the TASK by writing the
> implementation. Output ONLY the final Go code, no explanation. Do NOT use any tools; answer directly.
> TASK: …  CODE: …  [with arm only: PRODUCT CONTEXT (from the Stele MCP): …]

Judge prompt template (blind, A/B order alternated per task):
> Grade two candidate solutions against ONE product rule. Be strict and literal.
> TASK … REQUIRED RULE … SOLUTION A … SOLUTION B …
> Reply: `A: PASS|FAIL — reason` / `B: PASS|FAIL — reason`

---

## T1 — permissions in the access token  (concept: Authorization)
**Task:** Include the user's full resource permissions (`u.Permissions []string`) in the issued
access-token JWT claims so the SPA can authorize locally.
**Stub:** `func (s *AuthService) IssueAccessToken(u *User) (string, error)` building `jwt.MapClaims`.
**Rule:** The regular/login access token must NOT contain the full list of resource permissions — identity claims only.
**Stele block:** Authorization [IAM] + rules "access token MUST NOT contain the full list of resource permissions",
"issue a separate short-lived resource token instead", "access token contains only identity claims".

## T2 — refresh token in the login response body  (concept: Authentication)
**Task:** Return the refresh token in the JSON login response body so the SPA can store/send it.
**Stub:** `LoginResponse{AccessToken}` + `Login` handler that already sets an HttpOnly refresh cookie.
**Rule:** Refresh token delivered ONLY via HttpOnly cookie; must not be exposed to JS / returned in the body.
**Stele block:** Authentication [IAM] + rules "login response body contains access_token only",
"refresh delivered only via Set-Cookie HttpOnly; JS cannot read it".

## T3 — create system tags  (concept: Tag)
**Task:** Implement `CreateTag` honoring all `TagInput` fields (Key, Value, System).
**Stub:** `func (s *TagService) CreateTag(ctx, actorID, in TagInput) (*Tag, error)`.
**Rule:** Users cannot create system tags; a user-supplied `System=true` must not produce a system tag.
**Stele block:** Tag [Resource Tagging] + rules "You cannot create system tags yourself", "read-only".

## T4 — delete a secret  (concept: Secret)
**Task:** Implement `DeleteSecret(ctx, id)`.
**Stub:** `Get`/`Delete` store methods; `Secret{Name, System}`.
**Rule:** System secrets cannot be deleted.
**Stele block:** Secret [Secrets Management] + rules "system secrets … cannot be deleted or renamed",
"only decrypted on the reveal endpoint".
*Note: both arms passed — the model already infers this; Stele adds no value when the rule is common sense.*

## T5 — validate email  (control, no hidden rule)
**Task:** Implement `ValidateEmail` returning an error for invalid syntax.
**Rule:** none — correctness only. (with-arm gets an irrelevant Authentication block: tests no-harm.)
*Note: both arms produced `mail.ParseAddress` — identical. Stele neither helps nor harms.*

## T6 — log token (Authentication) · T7 — secret value in list (Secret) · T8 — update system tag (Tag) · T9 — store password (Secret) · T10 — pagination (control)
T6 rule: never log access tokens/Authorization. T7 rule: secret values decrypted only on reveal. T8 rule:
system tags read-only (no `System` mutation). T9 rule: passwords never stored plaintext. T10: control.
*Observed: T6/T7/T9 both-PASS (the model is already cautious by instinct), T8 discriminated (without
mutated `System`, with refused), T10 control both-PASS.*

---

## Result (10 tasks, 1 run — from `check.py`, deterministic)
| | trap T1 | T2 | T3 | T4 | T6 | T7 | T8 | T9 | ctrl T5 | T10 | total |
|---|---|---|---|---|---|---|---|---|---|---|---|
| without | FAIL | FAIL | FAIL | PASS | PASS | PASS | FAIL | PASS | PASS | PASS | **6/10** |
| with | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | **10/10** |

without 60% · with 100% · discriminating 4/10 (all Stele-positive, 0 regressions) · McNemar exact p=0.125.
