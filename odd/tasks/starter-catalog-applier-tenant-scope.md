# ODD feature: starter-catalog-applier-tenant-scope

**Repository**: LifeControl — `life-control-api/` only: `product/category/catalog/StarterProductCategoryApplier`
plus the two integration suites that call it. No schema, no migration, no endpoint, no frontend, no image pin.
**Status**: implemented — the applier states the tenant of the company it was given, inside the transaction it
owns; the catalog suites now call it with no scope on the thread, and the end-to-end publisher case states none
either. Nothing is deferred.
**Created**: 2026-11-01 · **Risk**: none — the change narrows one component's scope handling to the id it was
already writing rows for, and the fail-closed direction is unchanged: a wrong or absent tenant answers zero rows,
never another tenant's.
**Issue**: #724.

## Specs

**S1** — *the user, 2026-11-01, verbatim*: «continua con fix(api): the starter catalogue applier opens its own
transaction and never states the company it was given\n #724»

**S2** — *issue #724, "What happens", verbatim*:

> `StarterProductCategoryApplier.applyTo(UUID companyId)` opens its own `REQUIRES_NEW` transaction and **never
> states the company it was given**. ADR-0005 installs the tenant in `TenantScopeJpaTransactionManager#doBegin`,
> from the thread's `TenantScope`; a method that opens its own boundary therefore runs with whatever scope its
> caller happened to carry — and for a caller with none it runs with none. The reads of `companies` and the
> writes to `product_categories` then answer zero rows, and the applier reports:
>
> ```java
> throw new CompanyNotFoundException(companyId)   // "Company not found with id: …"
> ```
>
> plus a logged ERROR, and no catalogue is seeded.

**S3** — *issue #724, the measured blind spot and the three callers, verbatim*:

> Measured during T6b-4: the happy-path integration test passes only because the test wraps the call in
> `TenantScope.with(companyId, …)`. The three callers that cannot are the ones named in the same record — an
> operator script, a bulk import, and the `@TransactionalEventListener(fallbackExecution = true)` path, which
> runs **without a transaction and without a request scope** by definition.

**S4** — *issue #724, "Proposed fix", verbatim*:

> State the id the caller already knows, inside the applier's own transaction:
>
> ```java
> return TenantScope.with(companyId, () -> transactionTemplate.execute(status -> { … }));
> ```
>
> and pin it with a test that calls `applyTo` with **no** ambient scope and asserts the catalogue is seeded —
> the current test's wrapper is what hides the defect.

**S5** — *issue #724, "Acceptance", verbatim*:

> - `applyTo(companyId)` seeds the company's catalogue when invoked with no scope on the thread.
> - A test fails if the scope statement is removed.
> - No behavioural change for the callers that already state a scope.

## Tasks

- **T1** (S2, S3, S4, S5) — `StarterProductCategoryApplier`: the boundary becomes a `TransactionTemplate` with
  `PROPAGATION_REQUIRES_NEW`, stated around by `TenantScope.with(companyId, …)`; the catalogue body moves to a
  private `seed(UUID)` unchanged. Tests: `StarterProductCategoryApplierIntegrationTest` calls the applier with
  nothing stated on the thread (the `TenantFixtures.inTenant` wrapper is gone and the fixture asserts the thread
  is unscoped), and `StarterProductCategoryCompanyCreationIntegrationTest` stops stating a scope around the
  published event, which its own comment had recorded as a gap. Route: inline. Commit: the one carrying the two
  source files plus their tests.
- **T2** (S1) — this record. Route: inline. Commit: the one that adds this file.

Out of scope, recorded so the next session does not re-open it: `ProductService.createProduct` and the other
services whose caller still has to state the scope (T6b-2's note) — they are `@Transactional` request paths
behind `TenantScopeFilter`, not a boundary a system hook owns, and nothing in this issue asks for them.

## Log

- **L1** — *the user, 2026-11-01, verbatim*: «continua con fix(api): the starter catalogue applier opens its own
  transaction and never states the company it was given\n #724»
- **L2** — Measurement first, in the worktree, against the suite the issue names. With the
  `TenantFixtures.inTenant` wrapper removed and the applier untouched:
  `./gradlew test --tests "…catalog.StarterProductCategoryApplierIntegrationTest"` → **5 tests completed, 4
  failed**, every failure `com.lifecontrol.api.company.exception.CompanyNotFoundException: Company not found with
  id: bd003e28-…`. The fifth (`unknownCompanyIsRejected`) was already green because it expects the same
  exception. That is S2 reproduced without a database fixture to explain it away.
- **L3** — Decision D1, why the boundary stops being an annotation: `@Transactional(propagation = REQUIRES_NEW)`
  cannot state the scope. The proxy opens the transaction before the method body runs, and
  `TenantScopeJpaTransactionManager#doBegin` reads the holder at that moment — so a `TenantScope.with` inside the
  method body would state the tenant one boundary too late. `TransactionTemplate` with the same
  `PROPAGATION_REQUIRES_NEW` keeps the semantics the class's javadoc already argued for (the `AFTER_COMMIT`
  caller binds a completed transaction to the thread; `REQUIRED` would join it and never commit) and puts the
  scope statement where it belongs: around the boundary. `CompanyService#createCompany` and
  `TenantFixtures.inTenant` are the two existing precedents for the pair (scope around, boundary inside).
- **L4** — Decision D2, the end-to-end publisher case loses its scope instead of keeping it: its own comment said
  the gap — "a publisher with no scope at all … still gets a logged ERROR instead of a seed, and that gap is the
  applier's to close (reported, not patched here)" — and the applier is what closes it, so the case now pins the
  closed gap: publish with no transaction **and** no scope, and still assert 57 rows. The `fallbackExecution` pin
  it existed for is unchanged: no transaction is active in that case either way.
- **L5** — Evidence, GREEN: `./gradlew spotlessCheck spotbugsMain test --tests
  "com.lifecontrol.api.product.category.*"` → `BUILD SUCCESSFUL in 57s`; the catalog package's 93 tests report
  `failures="0" errors="0"` per XML (`Starter Product Category Applier Integration Tests` 5, `… Company Creation
  …` 2, `… Listener …` 2, `… Catalog …` 11, `… Migration …` 3, and the category controller suites).
- **L6** — Evidence, the gate CI runs: `./gradlew cleanTest test --no-daemon` in the same worktree → `BUILD
  SUCCESSFUL in 4m 10s`, and the result XMLs report **4 256 tests in 1 023 classes, 0 failed, 0 errors, 0
  skipped**. Reported, not inferred: the count comes from `build/test-results/test/TEST-*.xml`, not from the
  console tail.
