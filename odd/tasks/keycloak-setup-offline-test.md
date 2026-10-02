# ODD feature: keycloak-setup-offline-test

**Status**: merged — PR **#227** (`test/keycloak-setup-offline-test` @ `6072d65`), 2026-10-02. The
offline shell test that partly closes **K1** runs in `docker-build-integrity.yml`, and the residual
gaps are **K5–K9** plus **K2**, listed below. **The native review was declined for this candidate**, so
no lens ran and the delivery is an ordinary repository-policy decision; the dated detail is in the
evidence log.
**Created**: 2026-10-02 · **Risk**: **low** — it adds one test file and one CI step and changes **no
production artifact**: not the provisioning script, not the API, not the gateway, not a migration.
Its worst failure mode is a red check on a pull request. The **pipeline gate** applies anyway
(`project-conventions`), because the diff touches `.github/workflows/`: rollback is reverting the
commit, and post-deploy verification is the workflow's own run on `main` (the `push` trigger does not
filter by path, so it runs regardless).
**Repository**: LifeControl — `docker/scripts/tests/keycloak-setup.test.sh` (new),
`.github/workflows/docker-build-integrity.yml` (one step),
`odd/tasks/keycloak-setup-hardening.md` (the `Status` repair, the **K1** closure note, the non-goals bullet that pointed at it, and one appended evidence row). No change to
`docker/scripts/keycloak-setup.sh`, by **D1**.

## Origin

**K1** of `keycloak-setup-hardening` — that slice hardened the fail-closed reads of
`docker/scripts/keycloak-setup.sh` (merged to `main` as **`5de2456`**, PR **#226**) and declared its
own verification gap: *"Nothing in CI can execute the fail-closed logic. `bash -n` and ShellCheck
read the script; they cannot run it. The Java pin asserts what the script declares; it cannot assert
what it does."* The behaviour **was** observed — with a temporary, uncommitted fake-`docker` shim in
`/tmp`, and against the live dev realm — but nothing automatic reproduces it, and the shim was never
committed. Its **D2** deferred the remedy explicitly:

> A `docker/scripts/tests/keycloak-setup.test.sh` with a fake `docker`/`kcadm` shim is the only way CI
> could *execute* this logic.

This slice is that deferral, taken up. It does not reopen the hardening: it pins it.

## What was measured, not assumed (design evidence, read-only)

Read from `main @ 5de2456` before any line was written. Every item is `path:line`.

| What | Evidence |
| --- | --- |
| **The only indirection to Keycloak** is a one-line wrapper: `kcadm() { docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }` | `docker/scripts/keycloak-setup.sh:55-57` |
| The only other external shell-out is the container check `docker ps --format '{{.Names}}'` | `:148` |
| So a PATH shim named **`docker`** intercepts **100%** of the script's contact with Keycloak — `kcadm.sh` rides inside the `docker exec` argv and is never a host command | `:56`, `:148` |
| The container name is derived from the env file, not a constant: `COMPOSE_PROJECT_NAME` (default `lifecontrol`) → `CONTAINER="${COMPOSE_PROJECT_NAME}-keycloak"` | `:42-43` |
| **No path is overridable**: `SCRIPT_DIR` comes from `BASH_SOURCE`, `DOCKER_DIR` from `SCRIPT_DIR/..`, `ENV_FILE="$DOCKER_DIR/.env.$ENV"`, `SECRETS_DIR="$DOCKER_DIR/secrets"` | `:22-27`, `:155-156` |
| It **sources nothing** (no `_common.sh`) and has **no `main` guard**; it runs top to bottom under `set -euo pipefail` | `:20`, `:51-53` |
| The two files it cannot run without are both gitignored: `.env.$ENV` and the two secret files | `:27`, `:155-156`; `.gitignore:104-106` ignores the `.env.*` files, and `docker/.gitignore:3` ignores `secrets/*` |
| `kcadm_get` distinguishes absence from an unanswered API **by the stderr diagnostic text**, because both exit 1 | `:78-91` |
| `find_mapper_id` selects client-side from `id,name` CSV because `-q name=…` does not filter | `:138-139` |
| The **ShellCheck step lints the new test too** — its glob is `find docker/scripts -type f -name '*.sh'` | `.github/workflows/docker-build-integrity.yml:30-37` |
| A new sibling test would be a third `run:` step, executed from the repo root | `:38`, `:41` |
| The existing harness convention: no `set -e`, `PASS:`/`FAIL:` lines, `PASS_COUNT`/`FAIL_COUNT`, `<name>: N passed, M failed`, exit 1 on any failure, `mktemp -d` + `trap cleanup EXIT` | `docker/scripts/tests/freshness-guard.test.sh:27`, `:35-40`, `:317-321` |

**Consequence of the last four rows, and the reason D1 was answered as it was.** Because
`SCRIPT_DIR` derives from `BASH_SOURCE` and nothing is overridable, the *only* way to give the script
a runnable environment without touching it is to place a copy of it in a **synthetic layout** whose
own `docker/` sibling holds the env file and the two secrets. The copy is asserted byte-identical to
the repository's script before anything runs, so the bytes under test are the real ones — checkable,
not asserted. This is the gap **K2** ("the script needs a materialised environment to run at all")
being routed around rather than reopened.

## Decisions (user-owned, 2026-10-02)

| # | Decision | Chosen | Rejected, and why |
| --- | --- | --- | --- |
| D1 | How the test obtains a runnable environment | **Synthetic layout + `cmp`-asserted copy**: the test builds `<tmp>/docker/{.env.dev,secrets/*,scripts/keycloak-setup.sh}` and shims `docker` on `PATH`. **Zero** change to the provisioning script | **Env-var overrides in the script** (`KEYCLOAK_SETUP_ENV_FILE`, `KEYCLOAK_SETUP_SECRETS_DIR`) so it can be tested in place. It reads better, but it **reopens the artifact that was frozen, independently verified and merged hours earlier** (`2ee46fb…`, PR #226) and changes the production provisioning path for the benefit of a test. It would also nibble at K2, which the owner record deliberately left as *pre-existing, recorded rather than changed* |
| D2 | Case coverage | **The minimum four**: happy path, R4-001 (unanswered read), genuine absence, mapper drift | **The full seven**: the above plus the R4-002 policy read-back, the `set -e`-off structural case, and the `docker ps` seam. Deliberately deferred to keep the review workload small; the three excluded cases are recorded as gaps **K5–K7** rather than left implicit |

## Design

**Layout built per case, in `mktemp -d`** (never inside the repository tree, so the test is safe to
run from a dirty worktree and cannot create the gitignored files the repo forbids committing):

```
<tmp>/docker/.env.dev                              COMPOSE_PROJECT_NAME, KC_HTTP_PORT, realm, client ids
<tmp>/docker/secrets/keycloak_admin_password       non-empty, != CHANGEME
<tmp>/docker/secrets/keycloak_admin_client_secret  non-empty, != CHANGEME
<tmp>/docker/scripts/keycloak-setup.sh             byte-identical copy of the repository script
<tmp>/bin/docker                                   the shim; PATH-first
```

**The shim** is written by the test itself (inline heredoc, so this stays a **single** new file: there
is nothing extra for ShellCheck, the workflow or a reader to discover). It is a self-contained bash
script that never calls a real `docker`:

- `docker ps --format …` → prints the derived container name, or nothing when the case says the
  container is down.
- `docker exec <container> /opt/keycloak/bin/kcadm.sh <args…>` → answers from a per-case state
  directory, and **records every `create`** into a log file instead of executing it, so "how many
  creates were attempted" is an observation rather than an inference.
- An **injection knob** decides, per case, which read answers with the genuine absence diagnostic
  (`Resource not found` on stderr, exit 1) and which answers as an unanswered API (any other
  diagnostic on stderr, exit 1).

**Case table** (each row: the observation, and what it would catch):

| # | Case | Expected | Guards |
| --- | --- | --- | --- |
| 1 | Happy path: realm, client, roles, policy and all five mappers present and matching the contract | exit **0**, **0** creates | A test that passes trivially — without this row, "aborts" and "works" are indistinguishable |
| 2 | **R4-001**: the mapper-list read answers with an *unanswered-API* diagnostic | exit **1**, **0** creates, message `Refusing to read an unanswered Admin API call as "not found"` | The defect the slice fixed: a failed read silently consumed as "absent" |
| 3 | The *same* read answers with the genuine absence diagnostic | exit **0**, the mapper creates **recorded** | The inverse injection: absence must still behave as absence. Without it, case 2 is satisfied by aborting on everything |
| 4 | **R3-MAPPER-DRIFT**: an existing mapper's JSON is missing a required key | exit **1**, names the claim and the missing requirement, **0** creates, **no repair** | A mapper that exists but is wrong being reported as `exists` |

**Test-first analogue, stated honestly.** The artifact *is* the test, so the usual RED/GREEN does not
apply to it. The equivalent obligation is the one the owner record used on its own assertions: after
the test is green, each guarded behaviour is **mutated in a copy** and the matching case must be
observed **FAIL** — case 2 against a `kcadm_get` that reads a failed read as "not found", case 3
against one that aborts on the absence diagnostic, case 4 against a `verify_tenancy_mapper` that
accepts drift. A case that cannot be made to fail guards nothing.

## Work units

| # | Work unit | Status |
| --- | --- | --- |
| **W1** | `docker/scripts/tests/keycloak-setup.test.sh`: layout builder, inline `docker` shim, the four cases, harness shape matched to its two siblings | **done** |
| **W2** | RED by mutation for the three guarded behaviours, plus an anti-vacuity control | **done** |
| **W3** | CI wiring: a third step in `docker-build-integrity.yml`; gates over the final bytes | **done** |
| **W4** | Records: this one; the owner record's **K1** row and a pointer row in its evidence log | **done** |
| **W5** | Independent verification of the frozen bytes | **done** — two passes; the second found **L4** |

## Non-goals

- **Not** touching `docker/scripts/keycloak-setup.sh`, by **D1**. Its bytes are the subject, not the
  object, of this slice.
- **Not** a general fake Keycloak. The shim answers only the calls the four cases make; it is not a
  reusable simulator and does not pretend to be one.
- **Not** the three excluded cases (R4-002 read-back, `set -e`-off, `docker ps`), by **D2**. They are
  **K5–K7**, not oversights.
- **Not** closing **K2** (the script still needs a materialised environment to run). Routed around,
  not repaired.
- **Not** re-deciding anything the owner record closed: the five claims, `TENANCY_CLAIMS` as their
  single home, the Java pin, or the `ADMIN_EDIT` trade (**G11**/**D3**).

## Gaps

| # | Gap | Note |
| --- | --- | --- |
| K1 | **partially closed by this slice** | CI gains the ability to *execute* the fail-closed read path and the drift check. What it still cannot execute is below |
| K5 | **The R4-002 policy read-back is still not executed anywhere.** The step that proves `unmanagedAttributePolicy` took effect, and aborts the deploy when it did not, has no test | Excluded by D2 (review workload). The Java pin asserts the update line's shape; only a run could assert that a non-taken value aborts |
| K6 | **The `set -e`-off structural guarantee is still not pinned.** The owner record's own review produced the finding that a captured abort (`APP_CID="$(…)"`) works *only* because of errexit; the reworked helper reports through a global. A regression back to `$( )` would keep the four cases above green | Excluded by D2. This is the excluded case the owner record argues hardest for, and it is the largest remaining hole in K1 |
| K7 | **The container-down abort is unpinned.** Every case needs the `docker ps` check to *succeed* (the shim prints the derived container name, and each case asserts either exit 0 or a later, different abort), so a broken seam would go red — but the negative branch (`keycloak-setup.sh:148-151`, container not running → exit 1) is never exercised | Excluded by D2. It is a pre-existing path the hardening did not touch |
| K8 | **The exit-0-empty branch of `find_mapper_id` is not exercised.** `keycloak-setup.sh:139-140` reads an empty list as "absent"; case 3 reaches the create path through the *diagnostic* branch instead. What a real Keycloak answers for an existing client with zero mappers was never measured live, so neither the test nor this record claims which branch production takes | Stated in the test itself, so a reader of the failing output is not misled |
| K9 | **A guard-coverage gap in the repository, not in this slice**: `.github/workflows/api-ci.yml` filters pull requests by `life-control-api/**`, so `KeycloakClaimMapperCoverageTest` — which *does* pin the mapper create's declared bindings flag statically (`:64`, `:67`, `:75`) — **does not run on a docker-only pull request**, which is exactly the path on which `keycloak-setup.sh` changes. Until **L4** was fixed, that left the create's content unguarded on this workflow's own path | Observed while sizing **L4**. Not repaired here: changing another workflow's path filter is a separate slice with its own blast radius. This test now covers it behaviourally on the path that runs |

## Known limits of the implemented test

Found by the mutation pass that froze the bytes, and kept here rather than smoothed over.

| # | Limit | Disposition |
| --- | --- | --- |
| L1 | **Case 2's `rc=1` assertion does not discriminate on its own.** With the R4-001 guard removed, the script still exits 1 — from the *read-back* after a create it should never have attempted. The case still goes red, through `assert_creates 0` and the two message assertions | Left as is: `rc` is a coarse signal by nature. An explicit assertion that the create branch was never entered was added, and the mutation matrix was re-run |
| L2 | **Negative assertions were one-sided.** `assert_file_has_no_match` passed when the evidence file was absent, so "no repair" could have been satisfied by the log never appearing | Fixed: `build_layout` creates both logs empty, and the helper now fails on a missing file |
| L3 | **A citation, not a behaviour**: the shim's `updates.log` comment tied "never repairs" to `keycloak-setup.sh:314`, which is the fail-loudly line, not the absence-of-update property | Fixed: the comment now says the log proves no update was *attempted*, and cites `:314-316` for the property |
| L4 | **The shim fabricated the read-back, so case 3 was green on a wrong create.** It served the ideal contract JSON regardless of the create argv, so changing `keycloak-setup.sh:337`'s `config."multivalued"=true` to `=false` reddened **no** case: the test observed the create *count* and then verified a JSON the shim had made up | **Fixed**, because a false green on this script is the defect the slice exists to catch. The shim now records each create's declaration and serves a **created** mapper's JSON from it, so the read-back is a function of the write. Re-measured: ADV2 and ADV4 redden case 3 alone |
| K2 | **Unchanged**: the script cannot run without `docker/.env.<env>` and `docker/secrets/*`, both gitignored | Routed around by D1's synthetic layout. Recorded as still open, not as fixed |

## Evidence log

| Date | Evidence |
| --- | --- |
| 2026-10-02 | **Slice opened read-only, and the seam that makes it possible was measured before it was designed.** No file was written in this pass. `docker/scripts/keycloak-setup.sh`, `freshness-guard.test.sh`, `version-consistency.test.sh` and `docker-build-integrity.yml` read at `main @ 5de2456`. The decisive finding is the `kcadm()` wrapper at `:55-57` plus the single `docker ps` at `:148`: a PATH shim named `docker` intercepts every contact with Keycloak, which is what makes an offline test possible at all. The second decisive finding is that no path in the script is overridable (`:22-27`, `:155-156`), which is what makes D1's synthetic layout the only option that does not reopen the artifact. D1 and D2 were put to the maintainer with their blast radius and answered as recorded. The deployed backend has no `.env.*`, no `docker/secrets/*` and no running container: none of them is needed by the design |
| 2026-10-02 | **W1 implemented as a single new file, and the seam re-measured rather than inherited.** `docker/scripts/tests/keycloak-setup.test.sh` was written by a bounded writer whose only allowed surface was that one path: **633 lines, mode 755** (568 when first written; the difference is the four scrutiny fixes recorded as L1–L4). The fake `docker` is written by the test itself into its own `mktemp -d` root (one inline heredoc, so no fixture and no fourth file is added to `docker/scripts/`); it is first on `PATH` for the script invocation only, and it calls no real `docker` on any code path — the interception is structural, not conventional. Every `create` and every `update` the shim sees is appended to a log and never executed, which turns "how many creates were attempted" into an observation. Cases: the four of **D2**, with 5/6/5/6 assertions. For a **seeded** mapper the shim serves all eight contract keys with the values `verify_tenancy_mapper` actually requires (`keycloak-setup.sh:302-310`), **including `id.token.claim=false` and `userinfo.token.claim=false`** — checked against the script, because a shim that guessed those two would have produced a green run that proved nothing; for a mapper **created in this run** it serves back exactly what the create argv declared (see **L4**) |
| 2026-10-02 | **W2: the four cases were made to fail on purpose, in scratch copies under `/tmp`.** No worktree file was mutated: each variant is one behavioural change to a throwaway copy of the script, whose `diff` was reported to prove the mutation was confined, and the test then runs against that copy because it resolves the script relative to itself and `cmp`s against the same path. **Matrix, run twice** — once over the initial bytes and re-derived independently over the final ones. The five core variants produced identical outcomes in both runs; the assertion count went 21 → 22 when **L1** was fixed, and the second pass added **ADV2**/**ADV4**, measured after **L4** was fixed. **Matrix** (`variant → cases 1/2/3/4`): **M0** none → pass/pass/pass/pass (`22 passed, 0 failed`); **MA** `kcadm_get` reads any failed read as absence → pass/**FAIL**/pass/pass (`18 passed, 4 failed`); **MB** `kcadm_get` aborts on the absence diagnostic too → pass/pass/**FAIL**/pass (`17 passed, 5 failed`); **MC** `verify_tenancy_mapper` drops the `multivalued` requirement → pass/pass/pass/**FAIL** (`19 passed, 3 failed`); **MD** the script does nothing but `exit 0` → **all four FAIL** (`10 passed, 12 failed`). Every variant matched its prediction, and each mutation reddens **exactly** the case that guards it, which is what makes the four cases non-redundant. **MD is the anti-vacuity control**: it is the row that proves no case passes merely because the script ran. The failure lines were the intended ones — under MA, `case 2 (R4-001): no create is attempted (expected 0, got 1)`, not a harness error. The frozen artifact digest was confirmed unchanged before and after (`2ee46fb…`). The second pass also searched for mutations that redden **no** case, and found **L4** — see the row below |
| 2026-10-02 | **W3: CI wiring and the gates, over the final bytes.** `.github/workflows/docker-build-integrity.yml` gains one step after the two sibling tests — a **3-line diff**, and the only tracked file this slice modifies outside `odd/tasks/`. `bash -n` clean. The test run from the worktree root **and** by absolute path from a different cwd: `keycloak-setup: 22 passed, 0 failed`, exit 0 both times, so it is path-independent. ShellCheck with the **CI-pinned image** (`koalaman/shellcheck:stable`, 0.11.0) at the CI's `--severity=warning`: clean on the new file, and clean over the whole set CI lints (`find docker/scripts -type f -name '*.sh'` → 9 scripts), because the workflow's glob does cover `tests/*.sh`. The test needs nothing from the repository: `docker/` and `docker/secrets/` are byte-identical before and after a run, `secrets/` still holds only its five `*.template` files, and `docker/.env.dev` does not exist in this worktree at all. At this point `git status --porcelain` showed exactly the three entries of the code change (two untracked, one modified) and `git diff --stat` one file, +3; the records' own edits came afterwards in **W4**, so the final candidate shows four entries and two modified tracked files, which the last row confirms |
| 2026-10-02 | **W5: two independent passes over the frozen bytes, and the one that mattered was the second.** Both ran read-only, mutated only throwaway copies under `/tmp`, and confirmed the artifact's digest (`2ee46fb…`) unchanged before and after. The first pass produced the matrix above and four findings, all of which were acted on rather than filed: **L2** (a negative assertion satisfiable by a missing file) and **L3** (a citation off by two lines) were fixed in the test; **L1** was mitigated by an added assertion; and the record's own stale counts were corrected. The second pass re-derived the matrix over the new bytes and, **adversarially, searched for a mutation that reddens no case** — and found one: with `keycloak-setup.sh:337`'s create argument changed from `config."multivalued"=true` to `=false`, **all four cases stayed green**, because the shim fabricated the read-back JSON and never looked at what the create had asked for. That is a green run on a script that would provision five mappers with the multivalued flag off — the exact authorization defect this hardening exists to prevent — so it was fixed rather than recorded: the shim now serves a created mapper's JSON **from the recorded create argv** (**L4**). Re-measured after the fix: **ADV2** (`:337` → `=false`) and **ADV4** (`:341` `jsonType.label=String` → `=Number`) each redden case 3 and nothing else (`18 passed, 4 failed`), while MA/MB/MC/MD keep their confined outcomes. The second pass also verified the records' claims, which is how the stale counts above were caught, and confirmed the in-file `keycloak-setup.sh:<line>` citations resolve |
| 2026-10-02 | **The frozen bytes, and the delta that came after the audit.** The last independent pass audited the bytes and both records and reported six accuracy defects — two citations off by two lines, one conflation of `.gitignore` with `docker/.gitignore`, a live-status residue in the work-unit table, an over-broad "the two runs had the same outcomes", and a comment that called the shim's reconstruction "a real write". All six were corrected, and the only change to the test after the audit is that comment: no executable line moved. Re-run over the final bytes: `bash -n` clean, `keycloak-setup: 22 passed, 0 failed`, ShellCheck with the CI-pinned image at `--severity=warning` clean. **The committed bytes are checkable rather than asserted**: `docker/scripts/tests/keycloak-setup.test.sh` = `df7fffcc4300e6ff5a2f340e94172fb824e294e8bd8a5298cca66e87dc0ce429`, mode 755, 23246 bytes, 633 lines — verifiable with `git cat-file -p <commit>:docker/scripts/tests/keycloak-setup.test.sh | sha256sum` — while the artifact under test is still `2ee46fb…` |
| 2026-10-02 | **Merged to `main` as `6072d65`** (PR **#227**), 2026-10-02T22:34:02Z, `--merge` — never a squash, so both commits of the branch travel preserved (`94eac25`, `9412779`) and the merge commit has two parents, `5de2456` and `9412779`. What merged is what was verified: `9412779^{tree}` (the branch tip), `6072d65^{tree}` (the merge) and `origin/main^{tree}` are **all `5669f05f1b11c40f3073a98a66f69d5324babc8e`**, the test blob at `origin/main` still hashes `df7fffcc4300e6ff5a2f340e94172fb824e294e8bd8a5298cca66e87dc0ce429`, and the artifact under test is still `2ee46fb…` — untouched, as **D1** required. **The new CI step ran and passed in both runs**, which was checked by reading the job's *steps* and not the check's name: on the PR and again on `main`, `Shell & Artifact Integrity` executed ShellCheck → Freshness guard tests → JAR version consistency tests → **Keycloak setup offline behaviour tests**, all four `success`. **All four workflows are green on `main` @ `6072d65`** — Docker Build Integrity, Gateway CI, Angular CI and API CI. **The native review was declined for this candidate, by decision, and no lens ran.** The preflight returned `ready` and the START consent was resolved as `declined_this_candidate` (`lineage_created: false`, `mutation_performed: none`) at a reported risk tier of **high**, on evidence this repository can check for itself: an executable permission change in `docker/scripts/tests/keycloak-setup.test.sh` and shell scripting in `.github/workflows/docker-build-integrity.yml`. `assess` with `nativeReviewOutcome: declined` agreed on the tier and returned the risk-gated plan RDD-off produces — `writerSelfVerification` plus a **separate independent verifier** — which is what the three read-only passes above satisfied; `candidate.consumed` stayed `false` and `reviewDue` `high_risk`, so a review remains available as a fresh decision rather than being something this log quietly claims. Post-merge cleanup done in the repository's order: the herdr workspace closed **before** the merge, `gh pr merge --merge` without `--delete-branch`, `git fetch` + `merge --ff-only` in the anchor before deleting the branch (so `git branch -d` reported "was `9412779`" and thereby proved the merge rather than assuming it), then the worktree removed, `git worktree prune` clean, and the remote branch deleted |
