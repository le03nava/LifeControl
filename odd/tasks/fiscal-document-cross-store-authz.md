# Fiscal documents: the store axis the whole surface was missing

**Status**: implemented — issue #967. Every fiscal-document endpoint now answers **403** when the sale behind
the document belongs to a store outside the caller's store scope, reusing
`CurrentUserContext#verifyCompanyStoreAccess` through one collaborator, and `lc-admin` keeps its unscoped access.
Nothing is deferred except the observation recorded as `L6`.

## Specs

Quoted from issue #967 (`### Acceptance criteria`), verbatim. `S7`–`S8` come from the issue's
`### Expected outcome` paragraph and are marked as such.

- **S1** — "`GET /api/fiscal-documents/by-sale/{salesOrderId}` on a store-B sale from a store-A-scoped `lc-sales`
  caller answers 403 and leaks neither the `uuidFiscal` nor the folio; the store-B-scoped caller keeps its 200, and a
  stamped-less sale keeps answering 204."
- **S2** — "`GET /api/fiscal-documents/{id}/xml` on a store-B document answers 403 and returns no bytes; the
  store-B-scoped caller still reprints it."
- **S3** — "`POST /api/fiscal-documents/{id}/cancellation` on a store-B document answers 403 and **writes no
  cancellation row**, leaving the document's status exactly as it was. This is the sharpest of the four: a
  cancellation is a statement to the tax authority, not a local flag."
- **S4** — "`POST /api/fiscal-documents` naming a sale of store B answers 403 and stamps nothing."
- **S5** — "The guard runs **before** the authority is called and before any row is written, so a refused request has
  no observable effect beyond the 403."
- **S6** — "`./gradlew test` in `life-control-api` passes, including the existing fiscal suites."
- **S7** — *(from `### Expected outcome`)* "`lc-admin` keeps its unscoped access through the same short-circuit that
  already exempts it."
- **S8** — *(from `### Expected outcome`)* "Every fiscal-document endpoint that names a sale, **or a document that
  belongs to one**, answers **403** when that sale's store is outside the caller's store scope" — so the
  substituting folio a cancellation names is inside the rule, not beside it. It is the one place the issue names a
  *second* document by folio rather than by id, and a foreign folio there answers a distinguishable 400 today.

## Tasks

| ID | Spec | Route | What | Commit |
|----|------|-------|------|--------|
| T1 | S1–S8 | inline | The seed states the caller's real company → country → region → zone → store chain, so every existing fiscal suite keeps passing through a real guard instead of a short-circuit | `95ed5f3c` |
| T2 | S1, S4, S8 | inline | `FiscalDocumentStoreScope`: the one collaborator that resolves a sale's store and hands its chain to `verifyCompanyStoreAccess` | `d9c43d70` |
| T3 | S1, S5 | inline | Guard in `FiscalDocumentReadService#readBySale`, on the sale it already loaded, before any document is read | `d9c43d70` |
| T4 | S2, S5 | inline | Guard in `FiscalDocumentReprintService#reprintXml`, after the document is resolved and before the artefact is read | `d9c43d70` |
| T5 | S3, S5, S8 | inline | Guard in `FiscalDocumentCancellationService#cancel`, before the authority is called and before the already-cancelled check answers, plus on the substituting document | `d9c43d70` |
| T6 | S1–S8 | inline | `FiscalDocumentStoreScopeHttpIntegrationTest`: the four refusals over HTTP against real PostgreSQL, each paired with the state a write would have moved, plus the two open directions and the `lc-admin` short-circuit | the commit that adds this record |

Route is `inline` for all six: one behaviour, one component, one test class — the Writer rule has no trigger.

## Log

- **L1** — the human's request, verbatim: "puedes revisar los issues de fix y ver con cual continuamos", followed by
  the selection `#967 — fiscal: el guard de tienda que falta`.
- **L2** — measured at `be3349b7`: `grep -rn "companyStoreId\|verifyStoreAccess\|verifyCompanyStoreAccess"
  life-control-api/src/main/java/com/lifecontrol/api/fiscal/` returns nothing, while the sales-order paths
  (#889, merged hours earlier as #965/#966) guard every one of theirs. The gap is the axis, not a forgotten call.
- **L3** — where the guard goes, decided by what the existing suites call. `FiscalDocumentStamperIntegrationTest`,
  `FiscalDocumentBooksSeamIntegrationTest`, `FiscalDocumentReadHttpIntegrationTest` and
  `FiscalDocumentCancellationHttpIntegrationTest` call `stamper.stamp(...)`, `cancellationService.cancel(...)` and
  `readService.readBySale(...)` **directly**, under the seed's own identity. So the guard lives in the services — not
  in the controller, where `S5`'s ordering would be a promise instead of a property — and the seed's identity is the
  half that has to become truthful (T1). A company-only token is not a caller with no store scope; it is a caller
  whose token does not state its chain, and `--` at `CurrentUserContext#verifyCompanyStoreAccess` refuses exactly
  that.
- **L4** — `FiscalDocumentStamper#stamp` finds the sale by id with no company predicate, so tenant separation there
  is `RLS`'s, not the query's: a foreign company's sale is a 404 because the policy hides the row, which
  `FiscalDocumentReadHttpIntegrationTest#anotherCompanysSaleAnswersNotFound` already pins by stamping a foreign
  sale from that foreign seed's own identity. T1 is what keeps that case honest: the fixture stamps as the company it
  created, with the chain of the store it created.
- **L5** — the store is not on the document. `fiscal_document` carries `source_type`/`source_id` (`V108`), so the
  address is `document → sale → company_store_id → store`, and the company → country → region → zone → store chain
  is derived from the store row by `CompanyStoreOwnerResolver`, exactly as `SalesOrderService#verifyStoreAccess`
  does it. That is one extra select per id-addressed request, the same price #889 accepted and documented.
- **L6** — observed and **not** fixed here, to keep the slice at the four endpoints the issue names: the
  `sales_orders` row is reachable through `GET /api/fiscal-documents/by-sale/{id}` only, but the fiscal **cancellation
  row** a read returns names `replaces_fiscal_document_id`, and that document is not store-verified on the read
  path. It carries no folio or UUID of the replaced document, so nothing leaks today; it is named here so the next
  reader does not have to rediscover it.
- **L7** — the consequence this fix has on a deployed stack, stated so nobody has to infer it: a caller whose only
  role is `lc-sales` is now refused on these endpoints unless the token carries `company_store_id`. That is the
  requested behaviour and not a side effect — `lc-sales` is store-scoped and a token that names no store is a
  caller that never said which store it may touch — and it is what the provisioning flow already writes (the five
  `company_*` claims, `AGENTS.md` &rarr; *Claims de tenancy*). A caller holding a broader role (`lc-company` down to
  `lc-company-zone`) keeps its own scope, because `verifyCompanyStoreAccess` verifies only up to the broadest scope
  granted. `lc-admin` keeps everything (`S7`).
