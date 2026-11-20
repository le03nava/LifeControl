# A submitted cancellation is remembered and later resolved

**Issue**: #929 · **Component**: `life-control-api` · **Created**: 2026-11-19 · **Risk**: item 1 — the
unit rewrites the guard on stored rows: a closed status vocabulary, two constraints coupled to it by
equality, and an append-only trigger whose default is "mutable".

**Status**: implemented — the fourth state, the pending row, its reconciliation and the guards are the
branch's content; the issue's own non-goals stay out (the screen, the automatic retry, the CSD wiring).

## Specs

Quoted verbatim from the issue. `S1` is its expected outcome; `S2`–`S8` are its acceptance criteria in
order; `S9` is its non-goals, which bound the unit rather than describe it.

- **S1** — "A submitted cancellation is remembered with the authority's code, and a later reconciliation
  resolves it into either a `CANCELLED` row or a named rejection, without a human querying the PAC.
  Running the reconciliation twice changes nothing the first run already settled."
- **S2** — "A fourth document state records the submitted cancellation, and it is **not** `CANCELLED`:
  `ck_fiscal_document_status`, the migration self-check and the constraint inventory in
  `FiscalDocumentSnapshotMigrationIntegrationTest` move together, and no reader of `STAMPED` counts it."
- **S3** — "The submission row is written only on a non-terminal answer; `201`/`202` still write
  `CANCELLED` directly and a refusal still writes nothing, so the behaviour #926 shipped does not
  change."
- **S4** — "The reconciliation reads the outcome from an operation **quoted from the vendor's catalogue
  in the record**, with its request and answer shapes — not assumed. If the synchronous operation cannot
  answer it, the asynchronous `enviarSolicitudCancelacionAsincrono` and its status query are read and
  named first, because its `200` means \"registered\" and carries no `statusUUID`."
- **S5** — "A settled acceptance moves the pending row to `CANCELLED`; a rejection (`213`) leaves it
  settled and reported with the authority's code preserved; an unknown or still-pending answer moves
  nothing."
- **S6** — "Idempotence is asserted by running the reconciliation twice and comparing the row set."
- **S7** — "A pending cancellation cannot be cancelled again, the sale cannot be stamped again, and the
  document still reprints byte-identically."
- **S8** — "Tests count rows and provider calls around each path rather than asserting only a thrown
  exception."
- **S9** — "The screen that would show a pending cancellation." · "Automatic retry of a rejected
  cancellation, which is a decision nobody has made." · "The delivery wiring of the CSD."

## Tasks

| ID | Specs | Task | Evidence |
| --- | --- | --- | --- |
| T1 | S2 | Migration `V113`: the fourth state, the relaxed cancellation `CHECK`, the three answer columns and their `CHECK`, the guard's new arm and its freezing of the pending row's own facts, and the vocabulary plus the constraint inventory moved together | pid `6b5767583ae2f40aa4c05e90bd50f7d6a539d431`; schema 14, snapshot 8, green |
| T2 | S3 | Submission: write the pending row on a non-terminal answer, keeping `201`/`202` and the refusal exactly as #926 shipped | pid `9f156999a4868543d63d80ece609d0c3bd19a648`; cancellation 13 + HTTP 7, rows and calls counted |
| T3 | S4, S5 | Reconciliation: the port and the adapter for the two operations below, and the service that settles the pending row | pid `55eb12c8aecb09ae38c2b068521849a172b330fc` + `037bdbecfd81048e7987fcb06066c277d539309c`; service 12, adapter 15, integration 7 |
| T4 | S6 | Idempotence: a second run writes nothing and asks the authority nothing | a settled row answers from itself; the second call makes one status call and changes no row |
| T5 | S7 | Guards: a second cancellation is a 409 before the authority is called, the sale cannot be stamped again, and the reprint is unchanged | pid `90b8e5694f1c2ab6d2d50ba8bb38127671e8c79b`; bytes compared against the provider's own answer, plus row, artefact and call counts |
| T6 | S8 | Tests count rows and provider calls around each path | the twelve service and eight HTTP cases count `statusInvocations`, `registerInvocations` and row maps, not the answer body alone |
| T7 | S1–S9 | This record: the specs, the decisions, the vendor's answers and the evidence | this record |
| T8 | S1, S5 | The admin, tenant-scoped endpoint that drives the reconciliation | pid `9af191ca4ba96e3e4ac2877d4d4c4ef4f29ef332`; 8 HTTP cases + 4 context cases over the bean and the switch |

## Log

- **L1** — the user's request, verbatim: «aprueba el 929 y ejecutalo».
- **L2** — the row-shape decisions, taken before the first line of code because the issue leaves them open and
  two constraints force them:
  (a) the fourth state is `CANCELLATION_PENDING` — twenty characters, exactly the length
  `fiscal_document.status` declares.
  (b) the pending row **is** the cancellation row in waiting: it carries `cancels_fiscal_document_id`,
  `cancellation_motive` and `replaces_fiscal_document_id`, so `ck_fiscal_document_cancellation` admits
  `status IN ('CANCELLED', 'CANCELLATION_PENDING')` and keeps its logic otherwise. Those are the three facts a
  second submission needs, and `uq_fiscal_document_cancels` — already unique on the pointer — is what makes the
  second cancellation impossible at the schema level.
  (c) it carries **no** `uuid_fiscal` and no `stamped_at`: both equalities stay exactly as `V108` states them,
  and the UUID the reconciliation asks the authority about is the pointed document's, reached through the
  composite foreign key.
  (d) three columns record the authority's answer: `cancellation_status_uuid VARCHAR(4)`, `cancellation_message`,
  `cancellation_settled_at`. They are legal only on a cancellation row, and a timestamp requires a code.
  **Settled is `cancellation_settled_at IS NOT NULL`**: a rejection keeps the row at `CANCELLATION_PENDING` and is
  settled by that timestamp, which is exactly what makes a second run skip it without a second provider call.
  (e) the guard gains one arm: a `CANCELLATION_PENDING` row may only stay `CANCELLATION_PENDING` (recording an
  answer) or become `CANCELLED`. `STAMPED` and `CANCELLED` keep refusing every update and `DELETE` keeps refusing
  every row.
  (f) the submission's own code (`211`) is stored with `settled_at` null, so the row states what the authority
  said and that it has not settled.
  (g) the `202` body does not change: `FiscalDocumentCancellation` keeps its invariant (an id and a status come
  together), so the pending row's id is not echoed to the caller. The row is reachable through the document, and
  the screen that would show it is a non-goal.
- **L3** — the operations the reconciliation reads, quoted from the vendor's catalogue on 2026-11-19 (`S4`),
  because the synchronous `enviarSolicitudCancelacion` that #926 ships cannot be asked about a document it already
  judged:
  - **`enviarSolicitudCancelacionAsincrono`** — `SOAPAction: urn:enviarSolicitudCancelacionAsincrono`; parameters
    `usuario` (`xs:string`), `password` (`xs:string`), `solicitud` (`xs:base64Binary`, "la solicitud de cancelación
    firmada conforme al estándar especificado por el SAT"), `emailNotifica` and `properties` (both optional, the
    first being the vendor's only notification channel and not used). Returns `CancelacionAsincronoResponse{
    status xs:int, mensaje xs:string}` where `200` = "La solicitud de cancelación se registró exitosamente" and
    `mensaje` gives "el ID de la transacción, que generalmente será el mismo UUID que se envió a cancelar"; `701` =
    "Ya existe una transacción asíncrona para el UUID especificado"; `1801`/`1802`/`1803` = the request, its
    signature or its UUID is not valid; `601`–`605`, `611`, `620`, `621`, `633`, `500`, `501` = account or process
    failures.
  - **`getStatusCancelacionAsincrona`** — `SOAPAction: urn:getStatusCancelacionAsincrona`; parameters `usuario`,
    `password`, `transactionId` ("El UUID de la transacción que se desea consultar"), `properties`. Returns
    `StatusCancelacionResponse{status xs:int, mensaje xs:string, acuseSAT xs:string}` and, in the vendor's own
    words, "le retorna el acuse de la cancelación **si ésta fue cancelada correctamente** o el mensaje del por qué
    no se canceló", available "hasta 60 días después de haberse recibido respuesta del SAT". Codes: `200`
    registered, `204` the document cannot be cancelled, `211` in process, `213` rejected by the recipient, `702` no
    transaction with that UUID, plus the account failures.
  - **`cancelarAsincrono` is read and not used**: it takes `csdCer`/`csdKey`/`csdPassword`, the issuer's private
    key per call, which is the custody posture `L20` of `odd/tasks/cfdi-fiscal-document.md` rejected for
    `cancelar` and `cancelarBase64`.
- **L4** — the reconciliation's reading of those answers: `acuseSAT` present is **accepted** (the catalogue lists
  no `202` on this surface, and this field is its only statement of "cancelada correctamente"); `213` and `204`
  are **settled rejections** whose code is kept; `211`, a bare `200`, and any code the catalogue does not settle
  **move nothing**; `702` means the transaction is not registered, so the pass registers it — `200` and `701` both
  mean "registered", the second because an earlier pass already did — and writes nothing. Transport and account
  failures move nothing and are reported.
- **L5** — the trigger is an **admin, tenant-scoped endpoint**, decided with the maintainer on 2026-11-19.
  `fiscal_document` is armed and forced (`V108:218`), so no scheduled process can enumerate which companies hold a
  pending cancellation; the tree's only precedent for that (`task_employee_company`, a `SECURITY DEFINER` function
  in the roles script) is a privileged cross-tenant read this unit does not open. The reconciliation therefore runs
  inside the request's tenant scope, and its logic is scheduling-free so a scheduler is a later, purely additive
  unit. That automation is the one thing this record defers.
- **L6** — the pending row is written in the submission's own transaction and only for a non-terminal answer;
  `201`/`202` still write `CANCELLED` directly and a refusal still throws with nothing written (`S3`).
- **L7** — three consequences of T3, recorded rather than discovered later:
  (a) `cancellation_settled_at` means "the authority answered and this tree stopped asking". A pass that settles a
  row writes it **wherever the row lands**, `CANCELLED` included; a row a direct `201`/`202` wrote carries null
  because there was nothing to settle. That is the reading of L2(d) this unit keeps, and it is why the revision is
  not a contradiction of it.
  (b) the adapter is registered beside the other two fiscal beans in `FiscalProviderConfig`, under the same
  `@ConditionalOnProperty`. A bean that exists only behind an `ObjectProvider` is found missing by nobody, because
  the missing case has a good answer — that is `L20`(g) of `odd/tasks/cfdi-fiscal-document.md`, and a reconciler that
  answers 503 with the provider switched on would be the same defect one layer out.
  (c) the SAT's `acuseSAT` is answered to the caller and **not stored**: `V113` gives it no column, and the reprint
  serves the document's own bytes. The two new operations also carry no downloaded vendor fixture — the committed
  ones are for `enviarSolicitudCancelacion` — so the adapter suite builds its answer bodies from the shapes L3
  quotes, which is stated there rather than implied.
- **L8** — how the unit is cut, and the one gap it leaves. Seven commits by behaviour and not by file, because the
  entity carries the shape all three slices need: schema, submission, the shared transport and the asynchronous
  client, the settling service, the endpoint, the guards, and this record. The gap: a transport failure of the
  authority's status query reaches the catch-all `500`, because `FiscalStampProviderException` has no entry in
  `GlobalExceptionHandler` — the same shape the cancellation endpoint already has, and closing it means editing the
  shared advice, which is its own unit.
- **L9** — the independent verification, and the defect it found. Seven questions were answered against the
  migration; five passed outright, one passed with a reservation and one was a concern:
  (a) **the concern, now fixed**: the guard's new arm froze the row's `company_id`, `source_type` and `source_id`
  but not the facts the row exists for. On a `CANCELLATION_PENDING` row, `UPDATE … SET cancels_fiscal_document_id =
  <another stamped document>` and `UPDATE … SET cancellation_motive = '03'` were **accepted**, and the
  reconciliation asks the authority about the UUID of the document the row *points at* — so a re-pointed pending
  row would settle a cancellation against a document the authority never judged, which is a write the `CANCELLED`
  state already forbids. The arm now refuses a change to all three of `cancels_fiscal_document_id`,
  `cancellation_motive` and `replaces_fiscal_document_id`, each naming the column that moved, and the file's own
  verification block reads those names back.
  (b) **the coverage gap, now closed**: no suite applied `V113` to a database that already held rows, so the
  `ADD CONSTRAINT` validation pass on populated data was untested, and the answer `CHECK`'s semantics were held by
  nothing. Four cases now do both — a `V108`-shape `CANCELLED` row surviving `migrateTo("112") → migrateToHead()`
  with nothing backfilled, the pending row's pointer and answer coupling, the guard's transition allow-list, and the
  freezing above, which is the only one of the four that had a RED.
  (c) **the reservation, accepted as the style's limit**: the migration's verification block asserts *text* — a
  `CREATE OR REPLACE` that swapped two arms, or an `OR` for an `AND`, would still render the tokens it looks for.
  Behaviour is held by the cases, which is the stronger half, and the block's job is to refuse a dropped sentence.
  (d) what the verification also confirmed and this record now states: no reader of `STAMPED` can match a pending
  row (every comparison is an equality, and the two SQL predicates simply do not include it), and no pre-existing
  row shape is rejected by the new `CHECK`s.

### Evidence log

- **2026-11-19 — the whole module, once, at the branch head**: `cd life-control-api && ./gradlew spotlessCheck
  spotbugsMain test` → BUILD SUCCESSFUL in 15m19s, **1186 classes / 5227 tests / 0 failures / 0 errors /
  0 skipped**. The fiscal selector moved 222 → 256 (T3) → 271 (T8, T5) with every step green, and the module's
  baseline count is what says nothing else moved.
- **2026-11-19 — the verification round**: `gentle-ai-verify` over the migration, then the fix commit folded into the
  schema slice. The RED for the frozen facts: `FiscalDocumentSchemaMigrationIntegrationTest` 14 tests, 1 failed,
  the re-pointing `UPDATE` returning an empty refusal (accepted). After the guard change: 14/14, and the fiscal
  selector **275 tests / 0 failures**. The three cases that assert behaviour the unit already had were reported as
  such, not as REDs.
: the constraint inventory and the vocabulary list; the pending row
  (nothing was written at all); the `202` half (`expected: "202" but was: "201"`, the double could not state a
  code it never had); the 409 sentence (`is already cancelled by document` for a row that is not); 27 reconciliation
  cases against throwing skeletons; 11 endpoint and context cases against a path no mapping answers.
- **Three cases cannot fail first, and say so**: the wire assertions added around a `202` whose behaviour the same
  unit had already implemented; the reprint's byte-identity, because the artefact never reads a cancellation row;
  and the second stamp's refusal, because `requireNoOtherStamp` already filtered `STAMPED`. Each was made
  meaningful instead of being declared: the reprint baseline is compared against the provider's own answer, the
  fixture is proven to have written exactly one `CANCELLATION_PENDING` row and the stamped row is asserted still
  `STAMPED`, and the stamp refusal is counted in documents, artefacts and provider invocations.
