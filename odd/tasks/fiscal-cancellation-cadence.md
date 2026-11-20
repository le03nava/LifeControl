# A submitted cancellation is settled on a cadence, not on a memory

**Issue**: #994 · **Component**: `life-control-api` · **Created**: 2026-11-20 · **Risk**: item 2 — the unit
opens a privileged cross-tenant read surface (`SECURITY DEFINER` resolver called by a pass that has no tenant
scope), which is the security decision the issue names instead of assuming.

**Status**: implemented — the resolver and its grants, the candidate enumeration, the pass that settles through
the reconciliation that already existed, and the property that keeps it off by default are the branch's content.
Nothing is deferred; the issue's own non-goals stay out (the `acuseSAT` column, any screen, a per-tenant
cadence, retrying a rejection).

## Specs

Quoted verbatim from issue #994. `S1` is its expected outcome; `S2`–`S7` are its acceptance criteria in order;
`S8` is its non-goals, which bound the unit rather than describe it. The problem statement is quoted ahead of
them because it is the premise the whole unit rests on, and the premise was verified against the tree before
the issue was approved: `V113` really does leave a `CANCELLATION_PENDING` row, `V108__fiscal_document.sql:218`
really does call `apply_tenant_isolation('fiscal_document')`, and `task_employee_company(uuid)` really is the
only `SECURITY DEFINER` resolver in `docker/postgres-init/lifecontrol-roles.sql`.

> "A cancellation the authority left in process (`statusUUID` `211`) is now remembered: `V113` writes it as a
> `CANCELLATION_PENDING` row and `POST /api/fiscal-documents/{id}/cancellation/reconciliation` resolves it. But
> the resolution needs someone to fire it, one document at a time. The receiver's window is 72 hours, so in
> practice the row waits until an operator remembers — the silent state #929 existed to remove."
>
> "A scheduled pass cannot find the work: `fiscal_document` is armed and forced (`V108__fiscal_document.sql:218`)
> and so is `companies` (`V53`), so a connection carrying no `app.company_id` reads zero rows. The tree's only
> precedent for resolving a tenant without a scope is a `SECURITY DEFINER` function in
> `docker/postgres-init/lifecontrol-roles.sql` (`task_employee_company(uuid)`, used by the provisioning worker),
> and opening a privileged cross-tenant read surface is a security decision of its own."

- **S1** — "A submitted cancellation is settled by the system on its own cadence, with no operator per document,
  and the tenant enumeration that makes it possible is stated and reviewed instead of assumed — either a
  privileged resolver whose grants are named in the roles script, or a recorded decision that this stays manual."
- **S2** — "With the provider stubbed to answer an acceptance, a pending row becomes `CANCELLED` with its
  authority code and settled stamp without any HTTP request from a human."
- **S3** — "The pass enumerates tenants through the resolver named in this issue, and the roles script carries
  its grants; a test asserts that a connection without a scope still reads zero `fiscal_document` rows directly,
  so the resolver is the only door."
- **S4** — "Running the pass twice changes nothing the first pass settled and makes no further status call for a
  settled row — the property #929 shipped, now asserted for the pass instead of the endpoint."
- **S5** — "A row whose authority answer is still pending moves nothing and stays a candidate; a rejected row
  leaves the candidate set, because retrying a rejection is a decision nobody has made."
- **S6** — "The pass is off by default, gated by a property whose default is stated in the compose and the
  template, so a deployment opts in and a missing key cannot silently enable it."
- **S7** — "`cd life-control-api && ./gradlew spotlessCheck spotbugsMain test` passes, with the new pass's counts
  recorded."
- **S8** — Non-goals: "Storing the authority's `acuseSAT`; `V113` gives it no column and this issue does not add
  one." · "Any screen: the pending state stays an API fact." · "A per-tenant cadence or backoff policy." ·
  "Retrying a rejected cancellation, and re-registering one whose transaction the authority no longer keeps."

## Tasks

The last column names the slice that holds the task. It names a **subject and not a SHA** on purpose: a branch commit
is a pointer a rebase orphans, and `.github/scripts/tests/odd-citations.test.sh` rejects any commit this directory cites
that is not an ancestor of `origin/main` — so the slices are named in `L4` and never by commit id.

| ID | Task | Specs | Route | Slice |
| --- | --- | --- | --- | --- |
| T1 | `fiscal_cancellation_pending_tenants()` in `docker/postgres-init/lifecontrol-roles.sql`: plpgsql, `SECURITY DEFINER`, owned by the bootstrap role, `REVOKE`/`GRANT` for `lifecontrol_app` only, and a read-back that refuses the deploy when any of it is not true. Mirrored into `life-control-api/src/test/resources/test-container-roles.sql`; `db-roles.test.sh` section `1c` pins both halves and the narrow shape (`d.company_id`, never `d.id`) | S3 | inline | — |
| T2 | `FiscalCancellationTenantResolver` + `…Impl`: one fixed query, the `to_regprocedure` probe, and a refusal that names `db-roles.sh` instead of answering "no tenant has work" | S3 | inline | — |
| T3 | `reconcileAsSystem(companyId, documentId)` on the reconciliation service, sharing `reconcileWithin` with the endpoint path, so the pass reuses the settlement instead of writing a second one | S1, S4, S5 | inline | — |
| T4 | `findByCompanyIdAndStatusAndCancellationSettledAtIsNull` on `FiscalDocumentRepository`: the candidate rule as a query, state **and** stamp | S5 | inline | — |
| T5 | `FiscalCancellationPass`: tenant loop through `TenantScope.with`, per-document containment, and a `Report` of the counts the gate reads | S1, S4, S5 | inline | — |
| T6 | `FiscalCancellationPassSchedulerConfig`: absent unless `app.fiscal.cancellation.pass.enabled=true`, `fixedDelay` measured from the end of the previous pass, seconds in the property name | S2, S6 | inline | — |
| T7 | The property in `application.properties` and as a compose default (`FISCAL_CANCELLATION_PASS_ENABLED`, `FISCAL_CANCELLATION_PASS_INTERVAL_SECONDS`) | S6 | inline | — |
| T8 | `FiscalCancellationPassIntegrationTest`: acceptance settled with no request, a second pass silent about it, a pending answer still a candidate, a rejection gone from the set, and the resolver as the only door | S1–S5 | inline | — |
| T9 | Gate: `./gradlew spotlessCheck spotbugsMain test` and the docker suites the change reaches | S7 | inline | — |

## Log

- **L1** — "puedes darle al issue 994, lo apruebas" (2026-11-20). Approval only at that point: the issue was
  read, its four premises were checked against the tree, and `status:approved` was applied with the grounds
  recorded on the issue itself. No source was touched.
- **L2** — "si dale" (2026-11-20). The approval became authorization to implement #994, so the unit's first
  step is the convention's, not an edit: `./.github/scripts/new-unit.sh feat/fiscal-cancellation-cadence`, and
  the anchor stays read-only.
- **L3** — Decision taken while implementing, and it is the security half of this unit: **the resolver answers
  companies, never rows.** The function returns `SETOF uuid` of the tenants that have a row waiting, and the pass then
  states each tenant's scope and lets the ordinary policy select that tenant's candidates. Returning the rows
  cross-tenant would have handed a scheduled thread every tenant's RFCs, amounts and fiscal UUIDs; returning company
  ids hands it exactly what it needs to ask a policed question. `db-roles.test.sh` section `1c` pins it (`d.company_id`
  asserted, `d.id` refused), so a later widening fails a suite instead of passing review.
- **L4** — The unit is four slices, in the order that keeps every commit buildable: (1) the resolver in the roles
  script, its read-back, the container mirror, the `1c` suite and the Java resolver — *enumerate tenants through a
  privileged resolver*; (2) `reconcileAsSystem` sharing `reconcileWithin` with the endpoint’s path, plus the
  candidate finder — *split the reconciliation into a caller path and a system path*; (3) the pass, its scheduler and
  the two property keys — *settle submitted cancellations on a cadence*; (4) the suite and this record — *cover the
  pass and the resolver-only door*.
- **L5** — Checks, measured on 2026-11-20 in
  `LifeControl-worktrees/feat-fiscal-cancellation-cadence`:
  - `cd life-control-api && ./gradlew spotlessCheck spotbugsMain test` → **BUILD SUCCESSFUL**, 17m54s, **5236 tests**
    across 1187 classes, **0 failures, 0 errors, 0 skipped**.
  - `FiscalCancellationPassIntegrationTest`: **5 cases, 0 failures** — acceptance settled with no request, a second
    pass silent about it, a pending answer still a candidate, a rejection gone from the set, and the resolver as the
    only door.
  - The five suites that already shared this settlement — `FiscalCancellationReconciliationIntegrationTest` (7),
    `…HttpIntegrationTest` (8), `FiscalDocumentCancellationIntegrationTest` (13),
    `…EndpointIntegrationTest` (7), `…NotConfiguredIntegrationTest` (1) — **36 cases, 0 failures**, which is the other
    half of the `reconcile`/`reconcileWithin` split: the endpoint’s behaviour did not move.
  - `docker/scripts/tests/db-roles.test.sh`: **186 passed, 0 failed**, including the sixteen new assertions of section
    `1c`; `env-defaults.test.sh` **296 passed, 0 failed**; every suite in `docker/scripts/tests/` exits `0`.
  - `odd-status-headers.test.sh` **78 passed, 0 failed** over 192 records; `odd-citations.test.sh` **29 passed, 0
    failed**.
- **L6** — What the first RED was, stated as what it was. The five cases failed before any fix with a single message:
  `column "company_id" does not exist` on `select company_id from public.fiscal_cancellation_pending_tenants()`. A
  `SETOF` function declares no columns of its own, so the read needs a `FROM` alias
  (`… as tenant(company_id)`). That is a defect in the resolver SQL the map could not have caught — no static read
  tells you whether a statement compiles — and it was fixed in the code, not in the assertion. The one other failure
  was an assertion of mine that compared a cumulative stub counter against a per-pass expectation (`expected: 1 but was:
  2`); the pass was right and the assertion was wrong, so the assertion moved.
- **L7** — Deferred, and named rather than implied: the pass’s **per-document and per-tenant failure containment has no
  case of its own**. It is written and it is the reason the pass continues, but every path in the new suite is a
  successful one, because making the fake reconciler throw for a single document means a new fixture in
  `FiscalCancellationProviderTestConfiguration`. That is the next unit’s first case, not a claim this one makes.
- **L8** — Deferred too, and twice for the same reason: `docker/.env.template` is behind this harness's safety guard,
  so its two keys were added by the operator from the text this unit produced, **and its staging is the operator's as
  well** — a `git add` that names that path is refused to this session, and staging it by glob to get around the
  refusal would be a bypass, not a shortcut. The evidence they landed is the suite that reads the file:
  `env-defaults` **296 passed, 0 failed**, four PASS lines naming both keys. No harness bypass was attempted.
- **L9** — **The independent verification was not a formality, and this is what it bought.** `gentle-ai-verify`
  (read-only, `Risk: item 2`) returned `met` on `S1`–`S6` and one **defect of scope**, which is fixed here:

  `reconcileWithin` re-registered the request on `NoTransaction` for every caller. On the endpoint that is one
  operator's deliberate act; on a cadence it was that **write** repeated every interval, unattended, for as long as the
  authority kept answering `NoTransaction`, about a transaction the authority no longer keeps. `S8` names exactly that
  as a non-goal — the cadence had made a prohibited behaviour automatic. The system path now enters with
  `mayRegister = false` and answers "still waiting" without writing anything (`answerWithoutRegistering`), the
  endpoint keeps its branch, and the case that pins it is
  `FiscalCancellationPassIntegrationTest#aPassDoesNotRegisterWhenTheAuthorityKeepsNoTransaction`.

  Four evidence holes were closed with it: (a) the integration suite runs in **one** transaction and `TenantScope` is
  installed at a transaction **boundary**, so deleting the wrapper left every case green — the wiring is now pinned by
  `FiscalCancellationPassTest`, which is the suite that fails when it is removed, and the limit is stated in its own
  javadoc instead of being implied; (b) the "never returns rows" pin only refused one literal, so
  `db-roles.test.sh` § `1c` now refuses four more column names by name; (c) `S6` had no test of the **context**, so a
  prefix typo or an unresolvable interval would have left the pass unregistered with every suite green —
  `FiscalCancellationPassSchedulerConfigTest` asserts one task when enabled and no scheduling infrastructure at all
  when absent or `false`; (d) four javadocs cited the wrong task ids and are corrected.

  **This closes `L7`**: the containment that had no case of its own now has one — `keepsGoingWhenOneTenantFails`, which
  abandons a broken tenant and settles the healthy one in the same pass. What is still **not** covered end-to-end is a
  second tenant through the container (the fixture seeds one company per instance); the two-tenant statement is a unit
  one, and saying otherwise would be the kind of claim this record exists to avoid.
- **L10** — The RED of the wiring is observed, not asserted. Removing
  `TenantScope.with` from `FiscalCancellationPass.runPass` and running only the new suite produced
  `FiscalCancellationPassTest > S1/S3: every tenant is stated before its candidates are read, and the thread carries no
  scope after FAILED` → `BUILD FAILED in 15s`; the line was then restored byte for byte from a copy taken before the
  mutation. That is the difference between a test and a decoration.
- **L11** — Second gate, after the correction round, on 2026-11-20: `./gradlew spotlessCheck spotbugsMain test` →
  **BUILD SUCCESSFUL**, 18m05s, **5244 tests** across **1189** classes, **0 failures, 0 errors, 0 skipped**. The three
  suites this round added: `FiscalCancellationPassTest` **4**, `FiscalCancellationPassSchedulerConfigTest` **3**,
  `FiscalCancellationPassIntegrationTest` now **6**. `db-roles.test.sh` **190 passed, 0 failed**.
  `L5` above is the first gate and its numbers are that tree, not this one: they are kept as the two runs happened.
