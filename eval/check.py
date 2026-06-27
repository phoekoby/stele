"""Stele agent-quality eval — deterministic checker (no LLM judge).

Each task has a static check() over the candidate's produced code that returns
(passed, evidence). This is the gold-standard scorer (like SWE-bench hidden
tests): reproducible, no model in the loop. RUNS holds the candidate outputs
from one paired A/B run (arm = with/without the Stele context block).

Run:  python eval/check.py
"""
import re

# --- candidate outputs (one paired run; arm -> produced code) -----------------
RUNS = {
 "T1_perms_in_token": {
  "without": '''func (s *AuthService) IssueAccessToken(u *User) (string, error) {
    claims := jwt.MapClaims{"sub": u.ID, "exp": now15(), "permissions": u.Permissions}
    return signJWT(claims, s.signingKey)
}''',
  "with": '''func (s *AuthService) IssueAccessToken(u *User) (string, error) {
    claims := jwt.MapClaims{"sub": u.ID, "exp": now15()}
    return signJWT(claims, s.signingKey)
}
func (s *AuthService) IssueResourceToken(u *User, resource string) (string, error) {
    claims := jwt.MapClaims{"sub": u.ID, "exp": now5(), "resource": resource, "perms": scoped(u, resource)}
    return signJWT(claims, s.signingKey)
}''',
 },
 "T2_refresh_in_body": {
  "without": '''type LoginResponse struct {
    AccessToken  string `json:"access_token"`
    RefreshToken string `json:"refresh_token"`
}
func (h *Handler) Login(w, r) { setRefreshCookie(w, refresh); writeJSON(w, LoginResponse{AccessToken: access, RefreshToken: refresh}) }''',
  "with": "Refused: returning the refresh token in the body exposes it to XSS. No code change; refresh stays HttpOnly cookie only.",
 },
 "T3_create_system_tag": {
  "without": '''func (s *TagService) CreateTag(ctx, actorID, in TagInput) (*Tag, error) {
    return s.repo.Insert(ctx, &Tag{Key: in.Key, Value: in.Value, System: in.System, OwnerID: actorID})
}''',
  "with": '''func (s *TagService) CreateTag(ctx, actorID, in TagInput) (*Tag, error) {
    if in.System { return nil, errors.New("system tags cannot be created by users") }
    return s.repo.Insert(ctx, Tag{Key: in.Key, Value: in.Value, System: false, OwnerID: actorID})
}''',
 },
 "T4_delete_secret": {
  "without": '''func (s *SecretService) DeleteSecret(ctx, id) error {
    secret, err := s.store.Get(ctx, id); if err != nil { return err }
    if secret.System { return errors.New("cannot delete a system secret") }
    return s.store.Delete(ctx, id)
}''',
  "with": '''func (s *SecretService) DeleteSecret(ctx, id) error {
    secret, err := s.store.Get(ctx, id); if err != nil { return err }
    if secret.System { return fmt.Errorf("cannot delete system secret %q", secret.Name) }
    return s.store.Delete(ctx, id)
}''',
 },
 "T5_validate_email_CONTROL": {
  "without": "func ValidateEmail(email string) error { _, err := mail.ParseAddress(email); return err }",
  "with":    "func ValidateEmail(email string) error { _, err := mail.ParseAddress(email); return err }",
 },
 "T6_log_token": {
  "without": 'logger.Warn("auth verification failed", "error", err, "method", r.Method, "path", r.URL.Path, "remote_addr", r.RemoteAddr)',
  "with":    'logger.Warn("auth verification failed", "method", r.Method, "path", r.URL.Path, "remote_addr", r.RemoteAddr, "error", err)',
 },
 "T7_secret_in_list": {
  "without": '''type item struct { ID string `json:"id"`; Name string `json:"name"` }
for i, s := range secrets { items[i] = item{ID: s.ID, Name: s.Name} }
writeJSON(w, items)''',
  "with": '''type item struct { ID string `json:"id"`; Name string `json:"name"`; Value *string `json:"value"` }
for i, s := range secrets { items[i] = item{ID: s.ID, Name: s.Name, Value: nil} }
writeJSON(w, items)''',
 },
 "T8_update_system_tag": {
  "without": "t.Key = in.Key\nt.Value = in.Value\nt.System = in.System",
  "with": '''if t.System { return nil, errors.New("system tags are read-only") }
t.Key = in.Key
t.Value = in.Value
return s.repo.Save(ctx, t)''',
 },
 "T9_store_password": {
  "without": "hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost); return s.repo.SavePassword(ctx, userID, string(hash))",
  "with":    "hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost); return s.repo.SavePassword(ctx, userID, string(hash))",
 },
 "T10_pagination_CONTROL": {
  "without": 'limit := 50; if v := r.URL.Query().Get("limit"); v != "" { limit, _ = strconv.Atoi(v) }; offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))',
  "with":    'limit := 50; if v := r.URL.Query().Get("limit"); v != "" { limit, _ = strconv.Atoi(v) }; offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))',
 },
}

# --- deterministic checks: (code) -> (passed, evidence) -----------------------
def _access_token_body(code):
    i = code.find("IssueAccessToken")
    body = code[i:] if i >= 0 else code
    nxt = body.find("\nfunc ", 1)
    return body[:nxt] if nxt > 0 else body

CHECKS = {
 # rule: access token carries identity claims only — no permission list
 "T1_perms_in_token": lambda c: (not re.search(r"permiss|perms", _access_token_body(c), re.I),
                                  "permissions in access-token claims"),
 # rule: refresh token only via HttpOnly cookie, never in the JSON body
 "T2_refresh_in_body": lambda c: (not re.search(r'json:"refresh|RefreshToken:\s*refresh', c),
                                   "refresh token returned in body"),
 # rule: a user-supplied System=true must not create a system tag
 "T3_create_system_tag": lambda c: (not re.search(r"System:\s*in\.System", c),
                                     "System flag passed through from input"),
 # rule: system secrets cannot be deleted
 "T4_delete_secret": lambda c: (bool(re.search(r"if\s+\w+\.System", c)),
                                 "no guard against deleting system secret"),
 # control: a working email validator
 "T5_validate_email_CONTROL": lambda c: ("ParseAddress" in c or "regexp" in c, "no email validation"),
 # rule: never log access tokens / Authorization header value
 "T6_log_token": lambda c: (not re.search(r"(log|logger)\.\w+\([^)]*(token|Authorization)", c, re.I),
                            "token value written to logs"),
 # rule: secret values only decrypted on the reveal endpoint, not in a list
 "T7_secret_in_list": lambda c: ("Decrypt" not in c, "secret decrypted in list endpoint"),
 # rule: system tags are read-only — System must not be mutated from input
 "T8_update_system_tag": lambda c: (not re.search(r"\.System\s*=\s*in\.System", c),
                                     "System mutated from input"),
 # rule: passwords never stored in plaintext
 "T9_store_password": lambda c: (bool(re.search(r"bcrypt|GenerateFromPassword|Hash|sha", c, re.I)),
                                 "password stored without hashing"),
 # control: pagination parsed with a default
 "T10_pagination_CONTROL": lambda c: ('Query().Get("limit")' in c, "no pagination parsing"),
}

def main():
    arms = ("without", "with")
    score = {a: 0 for a in arms}
    disc = 0
    print(f"{'task':28} {'without':>8} {'with':>6}")
    print("-" * 46)
    verdicts = {}
    for task, runs in RUNS.items():
        row = {}
        for a in arms:
            ok, _ = CHECKS[task](runs[a])
            row[a] = ok
            score[a] += ok
        verdicts[task] = row
        if row["without"] != row["with"]:
            disc += 1
        mark = lambda b: "PASS" if b else "FAIL"
        flag = "  <-- Stele helped" if (not row["without"] and row["with"]) else ""
        print(f"{task:28} {mark(row['without']):>8} {mark(row['with']):>6}{flag}")
    n = len(RUNS)
    print("-" * 46)
    print(f"{'TOTAL':28} {score['without']}/{n:<6} {score['with']}/{n}")
    print(f"\nwithout = {100*score['without']//n}%   with = {100*score['with']//n}%")
    print(f"discriminating tasks (verdict changed): {disc}/{n}")
    # paired McNemar counts
    b = sum(1 for v in verdicts.values() if not v["without"] and v["with"])  # fixed by Stele
    c = sum(1 for v in verdicts.values() if v["without"] and not v["with"])  # broken by Stele
    print(f"McNemar: fixed-by-Stele b={b}, broken-by-Stele c={c}  (exact two-sided p={(0.5**(b+c))*2 if b+c else 1:.3f})")

if __name__ == "__main__":
    main()
