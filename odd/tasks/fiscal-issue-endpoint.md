# ODD feature: fiscal-issue-endpoint

**Repository**: LifeControl — `life-control-api` (the fiscal module and its controller)
**Branch**: `feat/fiscal-issue-endpoint`, cut from `origin/main @ b1b31d00`
**Status**: **implemented** — issue #927, the endpoint that makes the fiscal module reachable in a deployed system. What it owes: nothing. What it deliberately leaves: substitution and re-issue, the Angular screens, and the delivery wiring of the CSD, each filed separately (#929–#932) and outside this unit.
**Created**: 2026-11-16 · **Risk**: item 1 only, and the endpoint does not add it — a stamp is irreversible and the ability to trigger one is what this unit exposes; the attempt key's idempotency, the one-live-stamped-document index and the profile refusals that make a bad request fail before the authority is called are all `T6`'s and are not re-implemented here.

## Objective

Give the fiscal document an entry point. `FiscalDocumentStamper` and `FiscalDocumentSnapshotWriter` have existed since `T6` and nothing in `src/main` called them, so the reprint and the cancellation added by `#926` could only answer 404 in a deployed system: no `STAMPED` row could exist.

## Problem — measured, not assumed

| Fact | Evidence |
| --- | --- |
| Nothing calls the stamper | `grep FiscalDocumentStamper\|FiscalDocumentSnapshotWriter life-control-api/src/main/java` outside `fiscal/service/` returns javadoc only |
| The controller had two paths | `FiscalDocumentController` declared `GET /{id}/xml` and `POST /{id}/cancellation` |
| The reprint and the cancellation were therefore unreachable | neither can serve a document that was never stamped |

## Specs

**S1** — A completed sale is issued in one call: `POST /api/fiscal-documents` takes the sale and an attempt key and answers `201` with the document's identity, the authority's UUID, its status and the bytes the provider returned. Every refusal the service already states reaches the caller as its own HTTP answer — `400` for a blank attempt key, `404` for a sale that is not this tenant's or does not exist, `409` for a sale that is not completed, already carries a live stamped document under another attempt, or whose fiscal profile is incomplete.

**S2** — The attempt key makes the call idempotent, and the body says which call it was: replaying a key that already stamped answers `200` with the same document and `stampedByThisAttempt = false`, without calling the authority a second time.

**S3** — A deployment without a stamping provider or without a sealer answers `503` naming the missing capability and writes nothing, so enabling it and retrying is the recovery rather than a hunt for half-written state.

## Tasks

| ID | Specs | Task | Depends | Route | Commit |
| --- | --- | --- | --- | --- | --- |
| T1 | S1, S2, S3 | The endpoint, its request record, and the 503 mapping widened from the cancellation's own | — | inline | `feat(fiscal)` |
| T2 | S1, S2, S3 | The HTTP suite over the real chain, and the not-configured suite | T1 | inline | `feat(fiscal)` |

## Log

**L1 (2026-11-16) — the maintainer's request, verbatim.** «ok apruebal y arrancate», in answer to the proposal that #927 goes first because it unblocks #929–#932. The issue was approved on the same instruction, with provenance commented on it.

**L2 (2026-11-16) — four decisions the issue left open, and the reason for each.** (a) The path is `POST /api/fiscal-documents` and the sale travels in the body, because the document is the resource this controller owns and a sale-scoped path would put a second controller over the same table. (b) A first stamp is `201` and a replay is `200`: a replay created nothing, called nobody and answered a document that already existed, so calling it a creation would be a status that lies about the only thing `S11` cares about. (c) The answer is `T6`'s own `FiscalDocumentStamp` record, XML included — that record was designed as "what a stamp attempt answers" and the attempt already holds the provider's bytes at the moment it keeps them; `GET /{id}/xml` remains the independent read, and neither is a second source of truth because the artefact table is insert-only. (d) The request record constrains the sale and **not** the attempt key: an absent sale is malformed and belongs to the binding layer, while a blank key is a domain refusal the service already states with a message that names it, and a second copy of the rule is a second copy free to drift from the one `S11` depends on.

**L3 (2026-11-16) — the 503 mapping was widened, not duplicated.** `FiscalStampProviderNotConfiguredException` and `FiscalSealNotConfiguredException` extend `IllegalStateException`, which the shared advice answers with **500** — so the endpoint's first version would have reported "this deployment does not invoice" as a server fault, and only the cancellation's handler existed. The controller's handler now lists the three capability refusals together, with the case that pins it: a context that imports no doubles, where the absence *is* the scenario.

**L4 (2026-11-16) — a test I wrote first was wrong, and the reason corrects a reason the record already gave.** The not-configured suite asserted that a `503` writes **no row at all**, and it failed with `1`. The row is real: `FiscalDocumentStamper.stamp` calls `snapshotWriter.createDraft` **before** it resolves the provider and the sealer, so a deployment that cannot invoice writes a draft and then refuses. In production nothing survives — the method is `@Transactional` and the refusal propagates, so the transaction rolls back — but inside one test transaction the row is visible and the exception only marks the transaction rollback-only. So the correct claim is about what the deployment **issued**, not about what the transaction holds mid-flight, and the case now asserts that no document is `STAMPED` and that the tenant kept no artefact. That is the fact that makes the `503` recoverable: enable the capability and retry.

Three things follow, and the second is a correction rather than a decision.

(a) **Asserting "zero rows" inside a `@Transactional` test suite is asserting the wrong thing** whenever the refusal happens after a write. The suite that *can* assert it is the one whose refusal precedes the first write — the cancellation path, where the provider is called before anything is inserted — and that is why the same assertion passed there and only here it was false. Both are honest; they just measure different layers.

(b) **`L15`(d)'s stated reason for putting the PUE refusal in the writer does not hold as written.** It says a refusal in the stamper "would run after the draft existed, leaving a row to clean up in a table that refuses `DELETE`". Measured: the draft exists and the exception rolls the transaction back, so there is no row to clean up. The placement is still right, for the reason `L15`(d) gives second — a refusal in the endpoint leaves the rule to whoever remembers to call it — and for the reason this slice adds: the writer is where a sale becomes a document, and a rule about which sales may become documents belongs there. What is wrong is the first reason, not the decision.

(c) **The order is deliberately not changed.** Resolving the provider before the draft would make the refusal write nothing even inside one transaction, but `FiscalStampProviderNotConfiguredException` and `FiscalSealNotConfiguredException` both name the **document** in their message, and a document that does not exist yet cannot be named. Changing the message to name the sale instead is a change to a public refusal and its test, in a slice whose subject is an endpoint; it is a candidate for the unit that owns the not-configured answers, and it is written here so the next reader does not rediscover it as a bug.

(d) **The endpoint's four refusals all reach HTTP through the exception family the port already declared**, with no new mapping invented: a blank attempt key is `400` because `FiscalAttemptKeyRequiredException` extends `IllegalArgumentException`; an unknown or foreign sale is `404` from `ResourceNotFoundException`; a sale that is not completed, already stamped under another attempt, or missing a profile is `409` from `ConflictException`; and the two capability refusals are `503` through the controller's handler. A test pins each, because a mapping that only works by inheritance is one refactor away from a 500.
