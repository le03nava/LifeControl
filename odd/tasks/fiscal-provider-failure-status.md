# A fiscal provider failure states who failed, not that this API is broken

**Issue**: #995 · **Component**: `life-control-api` · **Created**: 2026-11-20 · **Risk**: item 3 — a new
`@ExceptionHandler` changes where one exception family lands, and the family is raised from adapters that a
scheduled thread also uses. The handler is placed on the controller that owns the three endpoints, not in the shared
advice, so the blast radius is the three paths `D3` names and the catch-all keeps answering everything else.

**Status**: implemented — the `502` mapping, its constant body, the nine provider-failure cases, the catch-all
control and the no-row assertion are the branch's content. Nothing is deferred; the issue's own `D2`/`D3` bound it.

## Specs

Quoted verbatim from issue #995 as amended on 2026-11-20. `S1` is its expected outcome; `S2`–`S4` are the decisions
it states (`D1`–`D3`); `S5`–`S10` are its acceptance criteria in order.

- **S1** — "A provider failure answers **`502`** with the existing `ErrorResponse` shape and its correlation id, and
  leaves one log line naming the endpoint and the cause."
- **S2** — "**`D1` — the status is `502 Bad Gateway`, and `503` is excluded by the tree itself.** `503` already
  answers two different facts: the "not configured" refusal of `FiscalDocumentController` (provider, sealer or
  cancellation provider absent) and `IdentityProviderConnectionException` in `GlobalExceptionHandler`. The whole point
  of this issue is to be distinguishable from "not configured", so it cannot reuse its code. `504` is rejected for a
  narrower reason: the tree does not separate a timeout from any other transport failure —
  `FiscalStampProviderTransportException.unreachable` and `…answeredWithStatus` are one family — so `504` would name a
  distinction no code makes. Both subclasses mean the same thing to a caller: *an upstream server did not answer us in
  a usable way*, which is what `502` means."
- **S3** — "**`D2` — the response body carries no provider detail.** The `message` is one fixed sentence — `The fiscal
  authority failed to answer this request` — and names no host, no provider name, no HTTP status, no payload fragment.
  The cause (which already carries all of that) goes to the log together with the endpoint path and the correlation id.
  A caller gets an actionable, constant answer; an operator gets the detail through the correlation id it already
  returns."
- **S4** — "**`D3` — the scheduled pass is out of scope by construction.** `FiscalCancellationPass` reaches the same
  adapters from a scheduled thread, where no exception handler is involved: its per-document containment keeps its
  behaviour and this issue changes nothing about it. The blast radius of the new handler is exactly the three HTTP
  endpoints — the provider adapters are called from `FiscalDocumentStamper`, `FiscalDocumentCancellationService` and
  `FiscalCancellationReconciliationService`, and from the pass."
- **S5** — "With the PAC unreachable, with a non-2xx answer, and with an unreadable body, each of the three fiscal
  endpoints — `POST /api/fiscal-documents`, `POST /api/fiscal-documents/{id}/cancellation` and
  `POST /api/fiscal-documents/{id}/cancellation/reconciliation` — answers **`502`** in the `ErrorResponse` shape, with
  the constant message of `D2` and a correlation id."
- **S6** — "The "not configured" cases — provider or sealer absent — keep answering the `503` they answer today,
  unchanged."
- **S7** — "A genuinely unexpected exception still answers `500` through the fallback: the new handler does not
  swallow the catch-all, and a test states that with an exception outside the fiscal family."
- **S8** — "A test asserts the status **and** the body shape for each of the nine cases (three failure kinds across
  three endpoints), not only that an exception was raised."
- **S9** — "One case asserts that the request left **no row behind**, and it is not a tautology: each of the three
  entry points is a single `@Transactional` method (`FiscalDocumentStamper#stamp`,
  `FiscalDocumentCancellationService#cancel`, `FiscalCancellationReconciliationService#reconcile`) and the family is
  unchecked, so the rollback is what keeps the database clean — including the draft row the stamp writes **before**
  the authority is called. The case fails if the handler is given a no-rollback rule, or if the call is committed
  around the transaction."
- **S10** — "`cd life-control-api && ./gradlew spotlessCheck spotbugsMain test` passes."

## Tasks

The last column names the slice that holds the task, by subject and never by commit id: a branch commit is a pointer
a rebase orphans, and `.github/scripts/tests/odd-citations.test.sh` rejects any commit cited here that is not an
ancestor of `origin/main`.

| ID | Task | Specs | Route | Slice |
| --- | --- | --- | --- | --- |
| T1 | `FiscalProviderFailureHttpIntegrationTest`: the nine provider-failure cases (unreachable, non-2xx, unreadable × stamp, cancel, reconcile) asserting status, the constant message, the `path` and a correlation id | S5, S8 | inline | 1 — state who failed, on the three endpoints that call the authority |
| T2 | The `@ExceptionHandler(FiscalStampProviderException.class)` on `FiscalDocumentController`, beside the not-configured trio, answering `502` and logging the cause with the path | S1, S2, S3 | inline | 1 — state who failed, on the three endpoints that call the authority |
| T3 | The catch-all control: an exception outside the fiscal family, thrown at the same endpoint, still answers `500` through the advice | S7 | inline | 1 — state who failed, on the three endpoints that call the authority |
| T4 | The no-row case on the stamp path: a provider failure after the draft was written leaves neither the draft nor the artefact behind, which is the rollback the `@Transactional` entry point promises | S9 | inline | 1 — state who failed, on the three endpoints that call the authority |
| T5 | The not-configured control: the `503` of the existing trio is untouched, read from the same suite | S6 | inline | 1 — state who failed, on the three endpoints that call the authority |
| T6 | Gate: `./gradlew spotlessCheck spotbugsMain test`, and the script suites the change reaches | S10 | inline | 1 — state who failed, on the three endpoints that call the authority |

## Log

- **L1** — "puedes revisar el issue 995" (2026-11-20). Review only: the issue was read and its claims measured
  against the tree. Not approved at that point.
- **L2** — "si aplica las correcciones y resuelve el issue" (2026-11-20). Two authorizations in one sentence: amend
  the issue with the three gaps the review found, and implement it. The amendment landed first and is the contract
  this record quotes; `status:approved` was applied on this instruction with the grounds recorded on the issue.
- **L3** — The three corrections, and what each replaced:
  (a) the issue named "a stated 5xx" and no code; `D1` names **`502`** and says why `503` is unusable (it already
  answers the not-configured refusal *and* the identity provider's failure) and why `504` is not (nothing separates a
  timeout from the rest of the transport family).
  (b) the "no row behind" criterion read as a claim; it is now a measurement with its reason — each entry point is one
  `@Transactional` method and the family is unchecked, so the rollback discards the draft the stamp writes before
  calling the authority. That was verified in the tree before the issue was approved, not assumed:
  `FiscalDocumentStamper.java:209-210`, `FiscalDocumentCancellationService.java:180-181`,
  `FiscalCancellationReconciliationService.java:140-141`.
  (c) the body's contents were unstated; `D2` fixes one constant sentence and sends the cause to the log.
- **L4** — Decision taken while implementing, and it is the risk half of this unit: **the handler lives on
  `FiscalDocumentController`, not in `GlobalExceptionHandler`.** The advice is the class every endpoint in the module
  goes through, and its `Exception` fallback is the behaviour `S7` protects; adding a fiscal-family arm there would put
  the unit's change on a surface no other controller asked for. The controller already carries the not-configured trio
  for the same reason, and a controller-local `@ExceptionHandler` resolves before the advice, so the three endpoints
  get the `502` while every other exception keeps the answer it has today.
- **L5** — Red, green and gate, all measured on this branch on 2026-11-20.
  **Red**, with the two suites written and no handler: `./gradlew test --tests "…FiscalProviderFailure*"` → **11 tests,
  10 failed, 1 passed** (the pass is the `S7` control, which must be green before and after). The literal message is the
  same for every provider case — `Status expected:<502> but was:<500>` — and that is the defect itself: the family fell
  to the advice's catch-all. Writing the red also surfaced two defects **in the harness**, both fixed in the fixtures
  rather than in the assertions: `when(...)` executed the previous stubbing's answer (replaced by `doAnswer`/`doThrow`),
  and the cancellation cases sent no body, answering `400` before reaching the authority.
  **Green**: same command → BUILD SUCCESSFUL, 11 tests, 0 failures.
  **Preserved**: the four not-configured suites plus the three endpoints' own suites → **27 tests, 0 failures**, which
  is `S6` read rather than assumed: the `503` trio and the existing `200`/`201`/`202`/`4xx` paths answer what they
  answered before.
  **Gate**: `./gradlew spotlessCheck spotbugsMain test` → **BUILD SUCCESSFUL**, 22m58s, **5255 tests** across **1191**
  classes, **0 failures, 0 errors, 0 skipped** (the tree before this unit read 5244 / 1189; the difference is exactly
  this unit's two suites and eleven cases).
- **L6** — Two limits this unit states instead of hiding. (a) `S9`'s case is the tree's only fiscal HTTP suite that is
  **not** `@Transactional`, and it has to be: inside a transactional suite the request joins the test's transaction and
  the rollback the case asserts never happens, so the draft would stay visible and the assertion could never pass. It
  commits for real in the shared container and deletes its own fixture tree leaf-first; `fiscal_document`,
  `fiscal_document_line`, `fiscal_document_artifact` and `status_transition` are **not** deletable (`V108` revokes
  `DELETE` and the append-only triggers refuse it), so a regression makes the teardown fail loudly on the company
  foreign key instead of passing quietly. (b) `S6` has no case of its own here: four suites already pin the `503` trio,
  and a fifth copy would measure the same three handlers a second time. What this unit adds to that check is that it
  **ran** them.
