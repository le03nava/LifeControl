# ODD feature: fiscal-document-screens

**Repository**: LifeControl — `life-control-app-angular` (a new `fiscal` feature, the sale screen's issue action), `odd/tasks/`
**Branch**: `feat/fiscal-document-screens` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-fiscal-document-screens`, cut from `origin/main @ 2d41fb9f`
**Status**: blocked — the sale's document has **no reader**, so a reprint or a cancellation cannot find the document it must act on. That contract is issue **#947** and it lands first, as its own unit; the five tasks below start on top of it and none of them is written yet. The blocker is a contract, not a decision: the shape is settled in #947's acceptance criteria, so this record's `T1` will follow it rather than guess it.
**Created**: 2026-11-17 · **Risk**: item 3 only. The unit consumes four contracts (`POST /api/fiscal-documents`, `GET /api/fiscal-documents/{id}/xml`, `POST /api/fiscal-documents/{id}/cancellation`, and `GET /by-sale/{salesOrderId}` from #947) and adds no endpoint, no field, no migration and no credential; what it can break is a sale screen an operator already uses, and the one irreversible act it exposes — a stamp — is the API's to refuse, not the screen's to guard. No seam here decides a stored fact: the attempt key, the one-live-stamped-document rule and the profile refusals all live in Go-land's counterpart, `FiscalDocumentStamper`.

## Objective

Make the fiscal document reachable from the product. `#927` gave the API its entry point and `#930` its deployment, so a stamp, a reprint and a cancellation are all one request away — and a request is what an operator cannot write. This unit adds the only surface that turns the three endpoints into three actions, and it says out loud what the API is honest about: a `202` is *submitted*, not cancelled.

## Problem — measured in the tree, not quoted from the issue

| Fact | Evidence |
| --- | --- |
| No fiscal-document client code exists | `grep -rniE "fiscal-document\|fiscalDocument\|cancellationMotive\|attemptKey" life-control-app-angular/src --include=*.ts` → **1** match, and it is a doc comment (`features/customers/validation/fiscal-profile-complete.ts:9`) |
| The three endpoints do exist | `fiscal/controller/FiscalDocumentController.java:85` (`POST /api/fiscal-documents`), `:141` (`GET /{id}/xml`), `:181`-ish (`POST /{id}/cancellation`) |
| The API's two success statuses are not cosmetic | `FiscalDocumentController#issue` answers **201** when this attempt stamped and **200** when the attempt already had a document (`stampedByThisAttempt`), and the cancellation answers **201** when granted vs **202** when the authority has not decided |
| Both fiscal profiles are readable from the app already | issuer: `features/companies/companies/components/companies-form/companies-form.ts` (`regimenFiscal`, `fiscalZipCode`); receiver: `features/customers/validation/fiscal-profile-complete.ts` |
| The role gate is the sale's own | `@PreAuthorize("hasAnyRole('lc-admin','lc-sales')")` on all three endpoints, `Roles.ADMIN` / `Roles.SALES` |
| The motive codes have no HTTP reader | `FiscalCancellationMotive` is a Java enum (`01`–`04`) and no catalog endpoint serves it, so the select's four codes are a client-side copy, on the precedent of `shared/fiscal/regimen-fiscal.ts` and `shared/fiscal/uso-cfdi.ts`, each with a parity spec that reads its `.java` from disk |
| The app has never downloaded a file | zero matches for `responseType`, `blob`, `createObjectURL` under `src/**`, and no `file-saver` dependency: S3 is the tree's first blob download |
| `503` and `202` have no copy path today | `shared/data/http-error-message.ts:18-40` maps 400/401/403/404/409/412/500 plus a generic default, and no screen distinguishes a 2xx other than 200/201 |

## Specs

Quoted verbatim from issue **#932**, which is the request.

- **S1** — "The sale screen offers the issue action only when the sale is completed and both fiscal profiles are complete, and says which one is missing when it is not; the action sends the attempt key and reports the document's id and `uuid_fiscal`."
- **S2** — "A replay of the same attempt does not issue twice from the screen either: the second click reports the same document."
- **S3** — "The document view downloads the XML as the API serves it (`application/xml`, attachment) and never renders it inline."
- **S4** — "The cancel action collects the motive as a select over the four codes, asks for the substituting folio **only** for `01`, and shows the authority's code and message on a refusal instead of a generic banner."
- **S5** — "A **202** from the cancel action is rendered as "submitted, not yet accepted" with the authority's code — never as cancelled — which is the state the API is honest about and the screen must not contradict."
- **S6** — "The three "why nothing happened" cases are distinguishable: no provider configured (`503`), the document is a draft or belongs to another company (`404`), and it is already cancelled (`409`)."
- **S7** — "Unit tests cover each state and the error mapping; `npm run test:coverage:check` and `npm run lint` pass."

Non-goals, also the issue's own words:

- **N1** — "Capturing the fiscal profiles, which is a separate unit — this screen reads them."
- **N2** — "Substituting a document automatically after a cancellation."
- **N3** — "Showing a pending cancellation state before the API has one."

## Tasks

| ID | Specs | Route | Commit |
| --- | --- | --- | --- |
| T0 | the dependency | external — issue **#947** | its own branch, `feat/fiscal-document-read` |
| T1 | S1-S6 (the contract) | inline | `feat(fiscal): model and serve the fiscal document's three endpoints` |
| T2 | S1, S2 | inline | `feat(fiscal): issue a completed sale's document, attempt key and all` |
| T3 | S3 | inline | `feat(fiscal): download the stamped XML as the API serves it` |
| T4 | S4, S5, S6 | inline | `feat(fiscal): cancel with a motive, and say submitted is not cancelled` |
| T5 | S7 | inline | `test(fiscal): pin each state, gate the route and pass the coverage check` |

## Log

- **L1** — the user's original request, verbatim: «con cual issue de fiscal continuamos?» — then, on the recommendation of #932: «si».
- **L2** — the request is issue **#932**, `status:approved`, `life-control-app-angular`. It was chosen over the two other open fiscal issues: **#929** (the receiver's acceptance window) has no approval and needs a state `V108` does not have, and **#888** is the tax-rate day collision, a different domain. #932 is also the piece `cfdi-fiscal-document.md` records as deliberately outside that unit.
- **L3** — base `origin/main @ 2d41fb9f`, which is the merge of `feat/customer-fiscal-form` (#943): the receiver's fiscal profile screen. With the issuer's profile landed in `fiscal-issuer-profile.md`, both facts S1 needs are now readable from the app, which is what unblocks this unit rather than merely permitting it.
- **L4** — the motive vocabulary is copied to the client, not fetched. `FiscalCancellationMotive` (a Java enum, `01`–`04`) has **no** HTTP reader and adding one is a new public contract nobody asked for, so the select's options are a client-side const with a parity spec that reads the enum from disk — the two precedents are `shared/fiscal/regimen-fiscal.ts` and `shared/fiscal/uso-cfdi.ts`, and both their specs already open their `.java` sibling through `process.getBuiltinModule('fs')`.
- **L5** — S3 is the tree's **first** blob download: no `responseType`, `blob`, `createObjectURL` or `saveAs` exists under `src/**` and no `file-saver` dependency is declared, so the download is `HttpClient` with `responseType: 'blob'` + an object URL + a synthetic `<a download>`, with the filename taken from the `Content-Disposition` the API already builds (`FiscalDocumentController#reprintXml`). No new dependency.
- **L6** — S5 and S6 open two copy paths the app does not have. `shared/data/http-error-message.ts:18-40` maps 400/401/403/404/409/412/500 and a generic default, and nothing in it knows `503`; and no screen distinguishes a 2xx other than 200/201, so `202` is the first success status the app renders as neither success nor failure. Both are added where the fiscal feature reads them, not by widening the shared map for every screen.
- **L7** — **the unit is blocked on a contract, and the human decided how (2026-11-17).** Measured while planning: no endpoint returns a sale's fiscal document, `SalesOrderResponse` carries no fiscal pointer (`grep -rni fiscal .../salesorder/` → zero), and `FiscalDocumentDraft` is returned by nobody — so a `documentId` reaches a caller only in the body of a stamp attempt. The screen can therefore issue a document and then lose it: a cancellation happens days later, in another session, and would have no way to name the document. The three options put to the maintainer were (a) screen-only, session-scoped knowledge with the gap recorded, (b) the read endpoint inside this unit, (c) the read endpoint first as its own unit. **The maintainer chose (c).**
- **L8** — the predecessor is **#947**, opened 2026-11-17 as `feat(fiscal): a sale's fiscal document has no reader...` and approved on the maintainer's direct instruction (provenance commented on the issue). Its contract, settled there and not here: `GET /api/fiscal-documents/by-sale/{salesOrderId}`, **200** with the document and its cancellation block, **204** when the sale has no stamped document (never invoiced is a state, not an error — a 404 would toast on every completed sale), **404** for an absent or foreign sale, **403** without `lc-admin`/`lc-sales`, and no XML in the body. `T1` follows that shape; it does not invent one.
