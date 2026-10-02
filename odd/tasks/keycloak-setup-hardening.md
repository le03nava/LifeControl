# ODD feature: keycloak-setup-hardening

**Status**: implemented — the nine advisory findings of W0's review are dispositioned: **eight fixed in
code** and the ninth (`R1-REALM-ADMIN-EDIT`) re-accepted with its setting now **verified instead of
assumed**. W1–W5 are done, W6 is done except for the offline shell test, which the maintainer deferred
(**D2**, **K1**). Everything below is evidenced in the log; the work landed as `cdf6b5d` on
`fix/keycloak-setup-hardening`. This header makes no claim about push or PR state.
**Created**: 2026-10-02 · **Risk**: **medium** — it changes **what the provisioning script verifies and
when it aborts**, on the artifact that provisions the authorization input for all 37 scoped call sites.
No schema, no migration, no runtime path, no contract: every change makes the script **stricter**, so
the failure mode is a loud deployment abort rather than a silent authorization defect.
**Repository**: LifeControl — `docker/scripts/keycloak-setup.sh` (the **only** artifact that provisions
`life-control-realm`) and its pin
`life-control-api/src/test/java/com/lifecontrol/api/common/security/KeycloakClaimMapperCoverageTest.java`
(**2 files, +266/−54**). No API source, no Angular, no gateway, no migration.

## Origin

**W0** of `company-scope-local-fallback` merged to `main` as `3f56a0dd` (PR **#222**) with **zero
blocking findings and nine advisory ones**, recorded in that record's evidence log as "separate later
work". This slice is that later work: it does not reopen W0's decisions (the mappers, the
`TENANCY_CLAIMS` array as the claim list's single home, the pin, or `ADMIN_EDIT`), it hardens the
implementation the review looked at.

**Honesty about provenance**: the nine findings were admitted by the native review
(lineage `review-730ba3356e7eb2a1`, risk tier **high**, four lenses, correction budget 186) whose
authority was burned on acknowledgement. **The finding bodies are not retrievable** — the local store
keeps only the terminal-consumption record (`.git/gentle-ai/review-transactions/…`, which holds
repository, target and lineage hashes and no finding text), and the acknowledgement closure carried id,
lens, location, severity and disposition. The dispositions below therefore interpret the **recorded
identifier, lens, location and severity** of each finding, and every fix is justified on its own
technical merits against the code as it is — not on an authority this record cannot re-read.

Relation to the owner record: this is a **follow-up slice of W0**; the owner record's evidence log
carries a pointer row. It does not change that record's W2, W3 or its closed decisions.

## What was measured, not assumed

Before designing anything, the failure semantics of `kcadm` were measured against the **running dev
realm** (`lifecontrol-dev-keycloak`, healthy). The fix depends on this and it was not readable from the
script:

| Probe (`kcadm get`, credentials configured in-container) | Exit | stdout | stderr |
| --- | --- | --- | --- |
| absent client → `clients/<uuid>/protocol-mappers/models` | **1** | empty | `Resource not found for url: …` |
| absent client via list → `clients -q clientId=nope` | **0** | empty | — |
| absent realm / realm role / client role | **1** | empty | `Resource not found for url: …` |
| **Admin API unreachable** → `--server http://localhost:1` | **1** | empty | `HTTP request error: Connect to localhost:1 … Connection refused` |
| `users/profile --fields unmanagedAttributePolicy` | **0** | `ADMIN_EDIT` | — |
| `protocol-mappers/models --fields id,name --format csv --noquotes` | 0 | rows of `<id>,<name>` | — |
| `protocol-mappers/models/<id>` | 0 | that mapper's JSON object | — |
| `protocol-mappers/models -q name=<claim>` | 0 | **ignores the query, returns the whole list** | — |

**Consequences, all of which shaped the code.** (1) **Absence and a failed read are indistinguishable
by exit status** — both exit `1` — so the pre-change `mapper_exists` read a transient Keycloak failure
as "mapper absent" and proceeded to create. That is **R4-001**, confirmed as a real defect rather than
a theoretical one, and later demonstrated behaviourally (see the log). (2) The only discriminator is
the **stderr text** (`Resource not found` vs anything else), which is what the fix keys on. (3) The
same class existed in the three sibling existence checks the review did not name, so they are fixed
too: a fix for one of four identically shaped reads that leaves three lying about the same thing is
not a fix. (4) `-q name=…` does not filter, so a per-claim check selects client-side from the `id,name`
CSV. (5) There is no `jq`, `python` or `awk` in the Keycloak image, and the JSON is pretty-printed, so
the mapper verification strips whitespace and matches fixed strings with POSIX `grep`.

## Decisions (user-owned, 2026-10-02)

| # | Decision | Chosen | Rejected, and why |
| --- | --- | --- | --- |
| D1 | How far to harden the script | **Fail-closed reads + realm verification**: one read helper that distinguishes absence from an unanswered API, wired into every read of the Admin API; a read-back of the policy after the update; and verification of an **already-existing** mapper against the declared contract instead of trusting that its name appeared in a list | **(a) reads only** — leaves **R3-MAPPER-DRIFT** unfixed, since a mapper that exists but is wrong keeps reporting `exists`; **(b) only the two named lines** — leaves the same defect live in three sibling helpers |
| D2 | Offline pin for the new behaviour | **Declared follow-up**, not this slice | A `docker/scripts/tests/keycloak-setup.test.sh` with a fake `docker`/`kcadm` shim is the only way CI could *execute* this logic (~200 lines and a fifth file). The behaviour was instead observed with a **temporary, uncommitted shim** and against the live realm; the gap is **K1** |
| D3 | What to do with **R1-REALM-ADMIN-EDIT** (the realm-wide `ADMIN_EDIT` widening) | **Keep the accepted trade and make it verified instead of assumed**: the read-back proves the policy took effect; the widening itself stays as the maintainer decided on 2026-10-02 | Declaring the five attributes in the user-profile schema would narrow it, at the cost of duplicating the claim list in a second place nothing couples to `ScopeLevel` — the drift class W0a exists to close. Re-deciding it here would reopen a closed decision of the owner record |

## Advisories and their disposition

| # | Lens | Location | Severity | Disposition |
| --- | --- | --- | --- | --- |
| `R4-001` | resilience | `keycloak-setup.sh` (`mapper_exists`) | warning | **Fixed** — `mapper_exists` is gone; `kcadm_get` returns 1 only for the `Resource not found` diagnostic and **aborts** on anything else, and every read of the Admin API goes through it (`client_exists`, `resolve_client_id`, `client_role_exists`, `realm_role_exists`, `find_mapper_id`, the policy read, and the realm check). A failed read can no longer be read as "absent" |
| `R4-002` | resilience | `keycloak-setup.sh` (the policy step) | warning | **Fixed** — the read is fail-closed, and after the update the policy is **read back**; if it is not `ADMIN_EDIT` the script explains that the tenancy attributes would be silently discarded and exits 1. Success is reported only after the read-back |
| `R3-MAPPER-DRIFT` | reliability | `keycloak-setup.sh` (the mapper loop) | warning | **Fixed** — the coupling is no longer only in the Java test. `verify_tenancy_mapper` reads the **existing** mapper back by id and requires all **eight** declared keys; a newly created mapper is read back and verified too. On any missing requirement the script prints the claim, the missing requirement (key + required value) and the declared contract, then exits 1. It **fails rather than repairs**: an authorization artifact must not be silently converged |
| `R3-PIN-WEAK` | reliability | the test | warning | **Fixed** — per-claim structural assertions inside a block **bounded to the loop**: the loop must iterate `"${TENANCY_CLAIMS[@]}"`, and the creation command must bind `name`, `config."claim.name"` and `config."user.attribute"` **to `$claim`**, plus the multivalued flag. Text presence anywhere no longer satisfies it |
| `R3-POLICY-PIN-WEAK` | reliability | the test | suggestion | **Fixed** — the assertion requires the assignment on a line that **starts with** `kcadm update users/profile`, so the read-back line cannot satisfy it and neither can a diagnostic that merely quotes it |
| `R2-mapper-block-range` | readability | the test | warning | **Fixed** — the block is bounded from the loop header to its `done` |
| `R2-class-scope-summary` | readability | the test | suggestion | **Fixed** — the class summary names what the class pins: the mappers **and** the realm policy they depend on |
| `R2-scope-bullet-lead` | readability | `company-scope-local-fallback.md` | suggestion | **Fixed** — the non-goal bullet leads with what is out of scope; the falsified premise it recorded is kept, as support rather than as the lead |
| `R1-REALM-ADMIN-EDIT` | risk | `keycloak-setup.sh` (the policy step) | warning | **Re-accepted, now verified (D3)** — the realm-wide widening is real and remains by decision (owner record's **G11**); what changed is that the script *proves* the setting instead of assuming it. Residual: an administrator endpoint can write any undeclared attribute |

## Work units

| # | Work unit | Status |
| --- | --- | --- |
| **W1** | Fail-closed reads: `kcadm_get` distinguishing "absent" (the `Resource not found` diagnostic) from "the Admin API did not answer" (abort with the diagnostic), wired into **every** read — `client_exists`, `resolve_client_id` (which replaced `get_client_id` and reports through the global `CLIENT_ID`), `client_role_exists`, `realm_role_exists`, `find_mapper_id` (which replaced `mapper_exists`) and the realm check | **done** |
| **W2** | The policy step: read fail-closed, update, **read back**, abort if the value did not take. Also the answer to `R1-REALM-ADMIN-EDIT`'s verifiability half | **done** |
| **W3** | Realm verification: `verify_tenancy_mapper` checks an existing mapper's JSON — and a newly created one, read back by id — against eight fixed requirements (`protocolMapper`, `claim.name`, `user.attribute`, `multivalued`, `access.token.claim`, `id.token.claim`, `userinfo.token.claim`, `jsonType.label`), failing loudly with the claim and the missing requirement. Never repairs | **done** |
| **W4** | The pin: bounded mapper block, per-claim structural bindings, the policy assertion on the update line, class summary tightened | **done** |
| **W5** | The records: this one, plus the owner record's non-goal bullet lead and its pointer row in the evidence log | **done** |
| **W6** | Verification: the pin observed RED by **mutation** for every strengthened assertion, `bash -n`, ShellCheck at `--severity=warning` with the CI-pinned image, the full API suite, `spotlessCheck`/`spotbugsMain`, the **live** drift RED with a read-back-proven restore, the offline injection pairs, and an **independent pass over the frozen bytes** | **done**, except the offline shell test that **D2** defers (**K1**) |

## Non-goals

- **Not** re-deciding `ADMIN_EDIT` (D3) and **not** declaring the five attributes in the user-profile
  schema: that is the owner record's G11 trade, made deliberately, and it stays.
- **Not** touching the mappers' contract: the same five claims, the same `TENANCY_CLAIMS` array as
  their single home, the same `oidc-usermodel-attribute-mapper` shape. This slice changes **when the
  script complains**, not what it declares.
- **Not** repairing a drifted realm automatically. The script **fails and names the mismatch**; a
  provisioner that silently converges an authorization artifact hides the divergence instead of
  surfacing it, and the operator owns the decision.
- **Not** the offline shell test (D2). Recorded as **K1**.
- **Not** rewriting the frozen narrative of the owner record, and **not** repairing the stale
  `Base`/`Branch`/`Worktree` header lines of the three unimplemented sibling records — observed in this
  session (they name a branch and a worktree that no longer exist), reported to the maintainer, and
  left as separate work.

## Gaps

| # | Gap | Note |
| --- | --- | --- |
| K1 | **Nothing in CI can execute the fail-closed logic.** `bash -n` and ShellCheck read the script; they cannot run it. The Java pin asserts what the script *declares*; it cannot assert what it *does*. | The behaviour was observed with a **temporary, uncommitted fake-`docker` shim in `/tmp`** (injecting one read failure and recording creates without executing them) and against the live realm for the drift case. **No probe ran against a genuinely dead Admin API**, and none of this is automatic. The remedy is `docker/scripts/tests/keycloak-setup.test.sh` wired into `docker-build-integrity.yml`, deferred by **D2** |
| K2 | **The script needs a materialised environment to run at all** (`docker/.env.<env>` and `docker/secrets/*`, both ignored by git), so it cannot be exercised from a fresh clone or a worktree without them | Pre-existing, recorded rather than changed: it is what made the live verification a manual step, with the two ignored inputs copied into this worktree and never committed |
| K3 | **The verification message names the required value, never the observed one.** `verify_tenancy_mapper` prints the claim, the missing requirement (`key:expected`) and the declared contract; the mapper's actual value is not extracted | Deliberate and bounded: an honest report of a limit the review should be able to check. Extracting the observed value would need real JSON parsing on a host whose toolchain this script does not assume |
| K4 | **This record's header omits `Base`/`Branch`/`Worktree`**, which the sibling records carry | Deliberate. Those three lines assert state the next merge falsifies — measured in this session against `employee-registry`, `employee-store-assignments` and `employee-access-provisioning`, whose headers still name `main @ 274c67f`, `feat/hr-org-structure` and worktree `wM`, none of which exist. No policy is invented here: the header shape this record follows is the one `references/feature-records.md` documents |

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-10-02 | **Slice opened, and the fix's premise measured before it was designed.** Read-only inspection of `docker/scripts/keycloak-setup.sh` and `KeycloakClaimMapperCoverageTest` at `main @ ebd4831`; the `kcadm` failure-semantics table above was produced against the **live** `lifecontrol-dev-keycloak` container and is what shows absence and a failed read to be indistinguishable by exit status. D1 and D2 were put to the maintainer as an explicit choice with their blast radius and answered as recorded. **No source line was written** in that pass |
| 2026-10-02 | **W1–W4 implemented, and the pre-change behaviour demonstrated rather than argued.** The reference point is the script as it was at `HEAD`, staged as a temporary untracked copy and removed afterwards. With a temporary fake-`docker` shim (in `/tmp`, never committed) injecting **one** unanswered mapper-list read: the **pre-change** script returned **0** and attempted **5 creates** (with every read injected) — it read a dead Admin API as "no mappers exist" — while the **new** script returned **1**, printed `The Admin API did not answer the read of 'clients/<cid>/protocol-mappers/models …'` and `Refusing to read an unanswered Admin API call as "not found"`, and attempted **0 creates**. The inverse injection (the same read answering `Resource not found`) put the new script on the **create** path, so absence still behaves as absence instead of aborting. A third injection on the client-list read, with `set -e` disabled in both scripts (`set -uo pipefail`), separated a structural guarantee from one that merely rides on errexit: **new → exit 1, 0 creates**; **pre-change → exit 0, 7 creates and "setup complete"** |
| 2026-10-02 | **Against the live dev realm, RED then GREEN, twice.** The script run as-is was **green and idempotent**: the policy "already ADMIN_EDIT" and all five mappers "exists and matches the declared contract". Then **one** mapper was drifted for real (`kcadm update clients/<cid>/protocol-mappers/models/<id> -s 'config."multivalued"=false'`, read back as `"multivalued":"false"`), and the script failed as designed: exit **1**, `Protocol mapper life-control-client/company_store_id is missing "multivalued":"true".` plus the declared contract and the fail-rather-than-repair line. The restore was **proven by read-back** (`"multivalued":"true"`, and in the second pass a canonical diff against the pre-mutation snapshot came back identical), the script ran green again, and the realm was audited at the end: **exactly five tenancy mappers**, each satisfying all eight contract keys, policy `ADMIN_EDIT`. The probes' creates were intercepted and never executed, so the realm was never mutated by them |
| 2026-10-02 | **The strengthened pin, observed RED by mutation before it was believed.** Each of the three mutations made the **new** assertion fail while the **pre-change text-presence** assertion still passed — which is the finding, made measurable: (a) `-s name="$claim"` replaced by a literal → old PASS, new FAIL (`must bind the mapper name to the loop variable`); (b) the update carrying `ENABLED` with a diagnostic line still mentioning `ADMIN_EDIT` → old PASS, new FAIL (`never runs a line that *starts with* kcadm update users/profile`); (c) the real creation moved outside the bounded loop with the mapper-type text left inside it → old PASS, new FAIL. The script was mutated in place and restored **from a backup copy** (never from `git checkout`, which restores `HEAD` and would have discarded the implementation — it did once, and the recovery is why the backup rule exists). Gates over the final bytes: `bash -n` clean; ShellCheck with the CI-pinned image at `--severity=warning` clean; `spotlessCheck --rerun-tasks` and `spotbugsMain --rerun-tasks` green; the **full API suite** `cleanTest test --rerun-tasks` → **737 reports / 2700 tests / 0 failures / 0 errors / 0 skipped**, the same totals as the W0 baseline; the hardcoded-secret scan over the diff empty |
| 2026-10-02 | **Appended after the first review of this log by the independent verifier: the fail-closed reads were fixed for a fourth, latent instance of the same defect — a *capture*, not a read.** `get_client_id` reported its id through **stdout**, and its call sites were `APP_CID="$(get_client_id …)"`: an `exit 1` inside a command substitution runs in a subshell, so the abort did not propagate from the substitution itself — it worked only because the failing assignment tripped `set -e`. Measured with errexit disabled, the pre-change path **continued with an empty client id, attempted 7 creates and reported success**, while the reworked path (**`resolve_client_id`**, reporting through the global `CLIENT_ID`, called as `resolve_client_id "$X" \|\| exit 1`) **aborted with 0 creates** — with `set -e` off in both. Every fail-closed helper now reports through a global and no call site captures one; the `kcadm_get` comment was corrected, because it had claimed a discipline the code did not keep. This is the one change in this slice that a review produced rather than a finding |
| 2026-10-02 | **Independent verification, and the frozen bytes proven to be the committed ones.** A separate read-only pass over the candidate re-derived the `kcadm` semantics live, reproduced the RED/GREEN pairs, the three mutation demonstrations and the live drift RED with a read-back-proven restore, ran every gate, found the latent capture defect above (fixed and **re-verified**: with errexit off the new path aborts and the pre-change one silently continues), and reported the four documentation overstatements this log was rewritten to remove — among them that the failure message names the **required** value and not the observed one (**K3**), and that the unreachable-API case was observed **offline with a shim**, never against a live dead Admin API (**K1**). The bytes it froze are the bytes that were committed, which is checkable rather than asserted: `docker/scripts/keycloak-setup.sh` = `2ee46fbde6991968c149a7b025c51132c62c4aba4a3ceca8164fbf5c6be0d4c7` and the test = `a3864f50b38876c744e2730f70d035dc8bc05fc84dbe340793df2d9439200f4f`, both equal to `git cat-file -p HEAD:<path> \| sha256sum`. **Committed as `cdf6b5d`**, 2 files, +266/−54 |
