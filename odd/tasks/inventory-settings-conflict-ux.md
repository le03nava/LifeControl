# ODD feature: inventory-settings-conflict-ux

**Repository**: LifeControl — Angular frontend (`life-control-app-angular/**`) only. One feature
(`inventory`), one page, one shared helper. No backend edit, no template edit, no route, no contract
change, no build config, no CI change.
**Status**: implemented — the slice changes **seven files (+93 −3)** and the gate is green on the
committed tree `2b7368e` (130 files / 2537 tests / 0 failures; coverage 94.08 / 76.00 / 89.32 / 94.08
against the enforced 80 / 60 / 75 / 80). One independent read-only verification round was run and is
closed under `## Findings` and the evidence log. This header makes no claim about push or PR state.
**Created**: 2026-09-25
**Risk**: **low but not cosmetic.** Production behaviour changes on two paths — a write now carries a
precondition it did not carry, and a rejected write now reloads the form instead of leaving it stale.
The blast radius is one page plus one shared message helper. No auth, no role, no schema, no contract,
no data migration.
**Branch**: `fix/inventory-settings-conflict-ux` · **Base**: `main` @ `6423256` · **Worktree**:
`~/workspace/LifeControl-worktrees/fix-inventory-settings-conflict-ux` (herdr workspace `w17`)
**Requested by**: the user, choosing the D13b frontend half from the verified pending-work inventory
and then narrowing it to scope A ("solo la pantalla de settings") when shown that the handoff's step 5
is a contract change across four more screens.

## Why this exists

`odd/tasks/optimistic-lock-conflict.md` (PR #161, merge `bcb8d10db`) shipped the **backend** half of
W2-D13: `GlobalExceptionHandler` now maps `ObjectOptimisticLockingFailureException` to **409**, and the
store-inventory-settings endpoint gained an optional `version` precondition plus a `version` on its
response. That record's own `## Handoff to the Angular slice (D13b)` lists five steps and says the
frontend half "stays deferred".

The deferred half is not neutral. Today the backend capability is **unreachable from the only screen
that exists for it**:

- The GET response carries `version` and the Angular model **does not declare it**. The field is
  silently dropped at the type boundary.
- The PUT therefore sends no precondition, so `assertVersionPrecondition` returns on its first line
  and every write is an unconditional overwrite. Two operators editing the same store cannot produce a
  409 — they produce a **silent lost update**.
- Even if a 409 did arrive, `http-error-message.ts` does not name 409, so the operator would read
  `'Ocurrió un error inesperado. Intentá de nuevo más tarde.'` — the message that tells them nothing
  and invites them to retry the write that must not be retried.

So the merged backend work is, as of `6423256`, dead code from the UI's point of view.

## Problem (verified in the code at `6423256`, not inferred from the record)

Read-only map by a `gentle-ai-explore` scout, with every citation below re-checked by the orchestrator.

| # | Fact | Evidence |
| --- | --- | --- |
| P1 | The response DTO exposes `version` as a **required** `long`. | `life-control-api/src/main/java/com/lifecontrol/api/inventory/dto/StoreInventorySettingsResponse.java` — record header carries `long version` |
| P2 | The request DTO accepts `version` as an **optional** `Long`; `null` means "no precondition". | `.../inventory/dto/StoreInventorySettingsRequest.java` — `Long version`; the 2-arg constructor passes `null` |
| P3 | Asserting a version for a row that does **not** exist is also a conflict. | `.../inventory/service/StoreInventorySettingsService.java:178` — the create branch calls `assertVersionPrecondition(request.version(), null)`. *(Citation corrected from `:175` by the independent verification; `:175` is blank)* |
| P4 | The update path asserts against the freshly loaded row and flushes before mapping. | same file `:190` (assert) and `:196` (`saveAndFlush`), with the comment explaining that a non-flushed map would answer a pre-increment version and cause a **false 409** on the client's next write. *(The assert citation was corrected from `:181` by the independent verification; `:196` was already right)* |
| P5 | Two distinct 409 causes exist: the precondition (`ConflictException`) and a genuine concurrent flush. | `.../exception/GlobalExceptionHandler.java:51-54` and `:73-78` |
| P6 | The Angular response model has **no** `version`; the request model has no `version` either. | `life-control-app-angular/src/features/inventory/models/store-location-summary.models.ts:35` and `:42` |
| P7 | That file's own header claims a "field-for-field mirror of the backend DTOs". **It is now false.** | `.../store-location-summary.models.ts:5` |
| P8 | The page never sends a version: the request is built from the two selections only. | `.../stores/pages/store-inventory-settings/store-inventory-settings.ts` — `onSave()` |
| P9 | `handleSaveError` branches on 400 / 404 / 403 and falls through for everything else, so 409 reaches the generic copy. | same file — `handleSaveError` |
| P10 | `http-error-message.ts` names 400, 401, 403, 404 and 500. 409 is unmapped. | `life-control-app-angular/src/shared/data/http-error-message.ts` — the `switch` inside `httpErrorMessage`. *(The original line list was imprecise and is replaced by the symbol: the independent verification found the case returns at a different offset than the one first recorded, so a line list here was brittle evidence)* |
| P11 | No other consumer builds a `StoreInventorySettings` literal, so widening the response type breaks nothing but the specs. | grep across `src/` — the only non-spec consumers are the service and `receipt-create.ts:201`, which only reads `getSettings` |
| P12 | `strict: true`, and `exactOptionalPropertyTypes` is **not** enabled. | `life-control-app-angular/tsconfig.json:7` |

**Correction to the previous record's framing (recorded, not silently fixed).** Its F3/F4 phrase the
store-tree hazard as arming "the moment their DTOs expose **either** field". For `version` that is
latent. For `updatedAt` it is **already live**: all four store-tree response DTOs expose `updatedAt`
today and `Auditable` sets it at flush, so the eight update/enable paths among the twelve answer a
**stale `updatedAt` right now**. This slice does not touch that; it is recorded under `## Follow-ups`
as its own defect. No code contradicts the record's "twelve" count — the enumeration reproduced it
exactly.

## Scope

**In** (five surfaces, four of them source):

1. `life-control-app-angular/src/features/inventory/models/store-location-summary.models.ts` — declare
   `version` on the response model, optional `version` on the request model, and correct the stale
   "field-for-field mirror" claim.
2. `~.../companies/stores/pages/store-inventory-settings/store-inventory-settings.ts` — hold the loaded
   version, send it on update, omit it on create, and add the 409 branch.
3. `life-control-app-angular/src/shared/data/http-error-message.ts` — name 409.
4. The three specs that pin that behaviour.
5. This record.

**Out** (deliberate, each with its reason):

- **The other four `@Version`-backed edit screens** (`stores`, `store-areas`, `store-zones`,
  `store-locations`) and the `version` their contracts would need. That is a **contract change on four
  backend endpoints** plus twelve write methods, and the user explicitly narrowed it out of this slice
  when shown the size (the handoff's step 5). Candidate for its own chained work.
- **The stale-`updatedAt` defect on those twelve methods.** Live today, pre-existing, separate.
- **The template.** Option 1 of the 409 UX decision needs no markup change, so the HTML is untouched.
- **The e2e mock.** `e2e/mocks/api.ts` declares its own `StoreInventorySettingsMock` and returns no
  `version`, so the page will omit the precondition there and keep working exactly as before. No e2e
  spec exercises this save. Recorded as a follow-up, because it means e2e cannot catch a `version`
  regression.
- **Any backend change.** The backend half is merged and this slice must not touch it.

## Decisions

- **D1 — the models mirror the DTOs again; the version lives in the model, not in a side signal.**
  `StoreInventorySettings` gains `version: number` (the backend's is a required `long`);
  `StoreInventorySettingsRequest` gains `version?: number`. This restores the invariant the models
  file declares at `:5` instead of leaving a second, invisible carrier of the same state. Consequence:
  every spec fixture typed as the response must supply it, which is exactly the type-level proof that
  the field is no longer dropped.
- **D2 — the version is sent on update and omitted on create.** P3 makes this correctness, not style:
  the create path asserts against `null`, so sending a version when no row exists turns a legitimate
  first save into a 409. "No row" is exactly the state the page already models — `getSettings` maps a
  404 to `null` and the page renders an empty create form. So the rule is `version !== null → include
  it`, expressed as a conditional spread so no `undefined` key is ever serialized.
- **D3 — a 409 reloads the form and says so.** Chosen by the user as option 1 of three. On 409 the page
  sets a specific Spanish copy, clears `dirty`, and reloads `settingsResource`; the seeding effect then
  re-seeds both selects from the server's current state, so the operator's next save carries a fresh
  version and there is **no dead end**.
  - **Rejected variant, recorded because it is the tempting one:** reload only the version and keep the
    operator's selections. That removes the dead end by **consummating the lost update on the second
    attempt** — the operator overwrites the other session's change without ever having seen it. It is
    precisely the defect this slice exists to prevent.
  - Accepted cost: the operator's unsaved selection is replaced by the server's state. The copy says so
    explicitly, so the discard is announced rather than silent.
  - The mechanism mirrors the success path, which already clears `dirty` and reloads — so the re-seed
    path is pre-existing and already covered by the "keep an operator edit made while the post-save
    reload is in flight" test.
- **D4 — the 409 copy lives in two places, on purpose.** The page keeps the operator-facing, page-specific
  sentence (it can name the conflict and what was reloaded); `http-error-message.ts` gains a generic 409
  case so that **every other** call site stops answering "error inesperado" to a conflict. The page's
  branch is checked first and therefore wins.
- **D5 — copy is Rioplatense voseo, matching every neighbour.** The file's 400/403 strings and
  `http-error-message.ts`'s whole table already use voseo (`Revisá`, `No tenés`, `Intentá`). The new
  strings follow the same register; a `tuteo` string here would be the only one of its kind on the page.
- **D6 — the specs are the gate, not a formality.** The three claims this slice makes are each a
  behaviour a wrong implementation would silently pass without: (a) the PUT body carries the version
  when settings loaded, (b) it omits it when they did not, (c) a 409 reloads and clears the guard. Each
  gets a test that fails against today's code — verified by observation, per the repo's strict-TDD
  expectation, not asserted.

- **D7 — the response field is `required`, not optional, and that is a fail-closed choice.** Widening
  `StoreInventorySettings` with `version: number` breaks any fixture of that type, and the writer found
  the one remaining consumer (`receipt-create.spec.ts`) that my surface list had missed. Relaxing the
  field to `version?: number` would have avoided that edit — and would have been **fail-open**: the page
  reads `settings?.version ?? null`, so an absent field would silently disable the precondition and
  restore the lost update with no error anywhere. Required makes the type system force every response
  fixture to supply it, so a response that stopped carrying the token would fail loudly. The one-line
  fixture edit is the cost of that choice, and it was approved as a derived surface.

## Edit contract

Exact intent per surface. The writer must stop and report rather than improvise if any of this does not
match the code it finds.

### 1. `src/features/inventory/models/store-location-summary.models.ts`

- `StoreInventorySettings` (the response, `:35`): add `version: number;` with a short doc line.
- `StoreInventorySettingsRequest` (`:42`): add `version?: number;` and document that omitting it means
  "no precondition", so a **create** sends nothing.
- Fix the header comment at `:5` so the "field-for-field mirror" claim is true again.

### 2. `.../stores/pages/store-inventory-settings/store-inventory-settings.ts`

- Add a private signal holding the loaded version, seeded by the **existing** seeding effect alongside
  the two selections (`null` when there is no persisted row).
- `onSave()`: include `version` in the request **only** when it is not `null` (D2). The existing
  `canSubmit()` / chain guards stay untouched.
- `handleSaveError()`: add a `case 409:` that sets the D3 copy, clears the dirty flag, and reloads
  `settingsResource`. `saveErrorDetail` keeps receiving the raw `ApiError.message`, unchanged.
- Do **not** touch the template, the route, `canSubmit`, the stale-location logic, or the retry path.

### 3. `src/shared/data/http-error-message.ts`

- Add a `case 409:` to the existing switch, before `default`, with the generic conflict copy (D4).

### 4. Specs

- `.../inventory/models/`-consuming service spec: the mocked GET/PUT responses gain `version`.
- `.../store-inventory-settings/store-inventory-settings.spec.ts`:
  - the response fixtures at `:44-45` and the stale-location fixture at `:333` gain `version`;
  - the PUT assertion at `:369` proves the version is sent on update;
  - a new test proves a version is **not** sent when the store was never configured;
  - a new test proves a 409 sets the conflict copy, clears `hasUnsavedChanges()`, and triggers the
    reload;
  - the existing 400/404/403 table and the unmapped-status fallback must keep passing unchanged.
- `src/shared/data/http-error-message.spec.ts`: add 409 to the table-driven cases.

- `src/features/purchases/receipts/pages/receipt-create/receipt-create.spec.ts` — **derived surface, added
  after the first writer pass.** `receipt-create.ts:201` is the only other consumer of `getSettings`, and
  its spec builds a `StoreInventorySettings` literal at `:103`. D7's required field makes that fixture a
  compile error (TS2741), so the response fixture gains `version: 3,` and nothing else. The writer
  stopped and asked instead of editing out of scope or weakening the type; both decisions are recorded
  under D7.

## Tasks

| # | Task | Status |
| --- | --- | --- |
| T1 | Read-only map of the surface, every citation re-verified | done |
| T2 | Scope decision (A) and the 409-UX decision locked with the user | done |
| T3 | This record, before the first source write | done |
| T4 | Implement the four surfaces through one bounded writer | done |
| T5 | Gate: `npm run lint`, `npm run build`, `npm run test:coverage:check` | done |
| T6 | Work-unit commit on the feature branch | done |
| T7 | Independent read-only verification of this record's claims | done |

Statuses are written only after the outcome and its check were observed. A row still reading
`pending` means exactly that, and is the record's way of not claiming work it has not seen.

## Gates

Run from `life-control-app-angular`:

```bash
npm run lint
npm run build
npm run test:coverage:check
```

`npm ci --legacy-peer-deps` is required in a fresh worktree (`zone.js` sits outside the
`@angular/core` peer range). Do not run this suite in parallel with itself.

## Review workload

Declared before the first write, so the forecast can be checked against the result.

| Surface | Forecast |
| --- | --- |
| Models | ~6 changed lines |
| Page component | ~30 changed lines |
| Shared helper | ~3 changed lines |
| Specs | ~80 changed lines (3 fixtures, 1 updated assertion, 2 new cases, 1 table row) |
| This record | ~200 lines |
| **Source + specs** | **~120 lines of diff — a single PR, well inside the repo's comfort zone** |

**Result (measured)**: **7 files, +93 −3 as committed** (`2b7368e`), inside the forecast. Five of the
seven are source or spec edits; the seventh is the one-line derived fixture (D7).

The writer measured **+90 −3** before the commit, and that measurement was accurate for the tree it saw.
The commit is three lines larger because the `pre-commit` hook runs `eslint --fix` + `prettier --write`
and **re-stages its own output**, reflowing one type cast across three lines after the gate had already
run. Both numbers are kept: the delta is the hazard, not a rounding error (F9).

A one-file, one-line change to a shared helper is the only cross-cutting edit; it is additive (a new
`case`, no reordering, no changed existing branch) so no existing caller changes behaviour.

## Findings

- **F1 — the focused test command narrows execution, not compilation.** `@angular/build:unit-test`
  type-checks the **whole** `tsconfig.spec.json` graph before running, so `--include` does not isolate a
  spec from a type error elsewhere in `src/**`. This is why a one-line change to a shared model type
  could not be observed green until the consumer fixture above was authorized. Recorded because the
  plan's "focused run" language implies an isolation the builder does not provide.
- **F2 — one of the three behavioural claims has no genuine RED, and is reported as such.** The
  create-omission test ("no `version` key is sent when the store was never configured") passes
  **vacuously** against the pre-change component, because that component already sent no version. Its RED
  was only observable against the naive `version: version ?? undefined` implementation, where the
  `'version' in requestBody` assertion turns red. So the test is genuinely sensitive to the
  conditional-spread contract, but it did not fail first on the untouched code. Not upgraded into a
  claim of strict-TDD RED.
- **F3 — the orchestrator's first surface list was incomplete.** The parent's edit contract named six
  files and missed the one other consumer fixture. The gap did not become a silent out-of-scope edit
  because the writer stopped and reported it. Recorded as a process finding, not a code defect: the
  derivation rule (map unknown targets read-only before authoring surfaces) covers a model type's
  consumers, and it was applied one round late.
- **F4 — this record's own task table briefly asserted `done` for four tasks that had not started.**
  Caught and corrected before any source write, on the same reasoning the header invariant rests on: a
  record must not claim work it has not seen. The correction is itself recorded rather than quietly
  applied.
- **F5 — the copy strings are voseo and match every neighbour** (verified in the diff): the page's 400
  and 403 strings and the whole `http-error-message.ts` table already use `Revisá` / `No tenés` /
  `Intentá`. The two new strings follow the same register.
- **F6 — `life-control-app-angular/AGENTS.md`'s coverage table is deliberately NOT updated.** The gate
  now measures 94.08 / 76.00 / 89.32 / 94.08 against the table's 94.07 / 75.98 / 89.32 / 94.07. The
  table's own surrounding prose already states that `branches` varies in the second decimal between
  runs (75.93 / 75.98 / 75.99 observed previously, 76.00 now) and that **the contract is the floor, not
  the `Actual`**. Rewriting the `Actual` column to this run's values would re-stale the table on the
  next run — the exact failure mode that column was already corrected for. No edit is the correct
  action, and it is recorded here rather than left as an unexplained silence.
- **F8 — the post-409 version re-seed has no 409-specific test.** The verification confirmed the
  property holds in the code (the seeding effect writes the version at its third `set`, so the post-409
  reload genuinely restores a usable precondition), and it also found that the 409 test asserts only the
  copy, the raw detail, `hasUnsavedChanges()` and the re-seeded *selection* — not the version. The
  property is guarded **indirectly**, by the PUT test that asserts `version: 3` in the update body: delete
  the effect's version line and that test goes red. So the behaviour is not unguarded, but a 409-specific
  assertion would localise the failure to the path that depends on it. Recorded as a gap, not a defect.
- **F9 — the `pre-commit` hook re-stages after the gate, so the gated tree is not the committed tree.**
  `lint-staged` runs `eslint --fix` and `prettier --write` on `src/**/*.ts` and stages the result, which
  reflowed one type cast (4 insertions, 1 deletion) *after* the gate had measured the tree. The commit is
  therefore **+93 −3** where the writer measured **+90 −3**, and the first gate result described a tree
  that no longer exists. The verification round re-ran all three gate commands on `2b7368e` and reproduced
  the numbers exactly, which is why the claim survives — but the general lesson is worth more than the
  three lines: **a gate run on an uncommitted tree can be invalidated by the commit itself**, so the
  numbers that get reported must be measured after the hook, not before it.
- **F10 — the falsification round's most valuable target came back clean, and that is the load-bearing
  result.** The orchestrator's stated worry was that the 409 path might re-seed the two selections but
  **not** the version, which would make the operator's next save omit the precondition entirely — a
  silent restoration of the very lost update this slice exists to prevent, with the 409 test still
  passing. The verification read the effect body and found the version written alongside both selections,
  and confirmed that line is the signal's only writer. The property holds; the slice does not fail open
  after a conflict.

## Header history

| When | Line became | Why |
| --- | --- | --- |
| T3 (before the first write) | the planned form | The contract is durable; the delivery state was unknown and is deliberately not claimed |
| T6 (after T4 and T5 were observed) | `implemented — seven files (+90 −3), gate green, verification remains` | T4 and T5 were observed, so the planned form became false. The new line states only the durable content and what remains, and still names no delivery state |
| T7 (after the verification round closed) | `implemented — seven files (+93 −3), gate green on 2b7368e, verification closed` | The verification was observed and found the pre-hook line count stale; the header now carries the committed measurement and no longer claims verification as remaining |

## Evidence log

| Date | Task | Evidence |
| --- | --- | --- |
| 2026-09-25 | T1 | Read-only scout map (8 questions) + orchestrator re-check of each citation listed under `## Problem` |
| 2026-09-25 | T3 | The task table in this record was corrected from a false `done` to `pending` on T4–T7 before any source write (F4) |
| 2026-09-25 | T4 | One bounded `gentle-ai-worker` pass: 7 files, +90 −3 (source + specs). Focused run `npx ng test --no-watch --include=…` → **4 files / 70 tests passed, 0 failed** (baseline 67, so +3). Spec type check `npx tsc -p tsconfig.spec.json --noEmit` → **exit 0, clean**. `git status --porcelain` shows exactly the seven authorized files modified. RED observed for two of the three behavioural claims plus the shared 409 copy (F2 records the third honestly). The writer stopped on the derived surface instead of improvising (D7, F3) |
| 2026-09-25 | T5 | Gate run by a read-only `gentle-ai-verify`, each command once, sequentially: `npm run lint` **exit 0** (`All files pass linting.`); `npm run build` **exit 0**, initial total **851.16 kB** against `angular.json`'s `maximumWarning: 900kB` → **48.84 kB of headroom, no budget warning**; `npm run test:coverage:check` **exit 0** → **130 files / 2537 tests / 0 failures / 0 errors**, coverage **94.08 / 76.00 / 89.32 / 94.08** against floors **80 / 60 / 75 / 80 — all OK**. Test delta vs `main` @ `c49d296` (130 / 2534): **+3 tests, 0 files**, decomposed by the verifier as +2 genuinely new cases on the page spec and +1 parameterized row on the shared-helper spec; the two fixture-only edits add none. `git status --porcelain` byte-identical before and after the gate, so the gate changed no tracked file |
| 2026-09-25 | T6 | Commit **`2b7368e`** — `fix(angular): send the store inventory settings version precondition and recover from a 409`, 8 files, **+398 −3** total (7 source/spec files at +93 −3, plus this 305-line record). The `pre-commit` hook re-staged a formatting reflow after the gate, which is the +3 (F9) |
| 2026-09-25 | T7 | Independent read-only `gentle-ai-verify` on `2b7368e`: **all of C1–C12 UPHELD**, the hook's post-commit change confirmed **formatting-only** (`bf70d8f` → `2b7368e` = one hunk, one reflowed type cast), and the gate **re-run on the committed tree** reproducing the record exactly (lint 0, build 0 at 851.16 kB, test 0 at **130 files / 2537 tests / 0 failures / 0 errors**, coverage **94.08 / 76.00 / 89.32 / 94.08**). `git status --porcelain` empty before and after, HEAD unmoved. Four of this record's own citations were corrected as a result: the `+90 −3` figure (F9), P3 `:175`→`:178`, P4 `:181`→`:190`, and P10's brittle line list. F8 records the coverage gap it found; F10 records the falsification target that came back clean |

## Follow-ups (recorded, not in this slice)

1. **The four other `@Version` screens** — `version` on four backend request/response contracts, the
   precondition in four services, and the same write-side handling in `stores`, `store-areas`,
   `store-zones`, `store-locations`. Contract change; chained PRs.
2. **The live stale-`updatedAt` defect** — the eight update/enable paths among the twelve store-tree
   write methods map a response from a non-flushed entity while their DTOs already expose `updatedAt`.
3. **The e2e mock does not model `version`** — `e2e/mocks/api.ts:126-130` and `:300-304`. No e2e spec
   covers this save, so the gate cannot catch a `version` regression on this page.
4. **The store-tree-wide 409 UX** — once the contracts carry `version`, the same conflict/reload
   decision has to be made for four more screens; the copy and the mechanism chosen here are the
   precedent.
5. **A 409-specific assertion that the version was re-seeded** — the property is guarded only indirectly
   today, through the update-body test (F8). One extra assertion on the existing 409 test would localise a
   regression to the path that depends on it.
