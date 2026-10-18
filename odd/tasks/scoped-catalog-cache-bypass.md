# ODD feature: scoped-catalog-cache-bypass

**Repository**: LifeControl — `life-control-api/` (`company/service/CompanyRegionService.java`, `company/service/CompanyZoneService.java`, `config/cache/CacheConfig.java`, `AGENTS.md`), closing **G12** of `odd/tasks/hr-org-structure.md`.
**Status**: **closed** — the defect G12 declares is fixed: commit `8be0436` on `fix/scoped-catalog-cache-bypass` off `main @ 135cdbe`, with the RED in L6, the independent verification in L7 and the gates in L8. Nothing in this scope remains open. The other follow-up `odd/tasks/redis-cache-activation.md` declares in its own L10 (`application-test.properties`' dead Boot 3 exclusion) was never in scope here and stays open there.
**Created**: 2026-10-17 · **Risk**: item 2 (authorization) — the change closes a scope-check bypass on merged endpoints; to be independently verified by `gentle-ai-verify`
**Antecedent**: `odd/tasks/hr-org-structure.md` (G12, T16), `odd/tasks/redis-cache-activation.md` (L3.1, L10)

## Specs

S1 — the request, verbatim:

> puedes revisar  De redis-cache-activation L10 (siguen abiertos por su propia letra, L13 lo confirma):
>  - application-test.properties excluye
>    org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration — nombre de Boot
>    3. En 4.1.1 es DataRedisAutoConfiguration, así que la exclusión es muerta y todo contexto
>    de test levanta un LettuceConnectionFactory.
>  - hr-org-structure G12: lectura cross-tenant preexistente de companyRegions/companyZones, con
>    el chequeo de scope adentro del método cacheado. Hoy hay una sola réplica, así que la
>    exposición no cambia; con la cache ya activa, deja de ser un detalle local.

S2 — the follow-up S1 quotes, verbatim from the antecedent's L10 (second bullet):

> `odd/tasks/hr-org-structure.md` G12 records a pre-existing cross-tenant read of `companyRegions`/`companyZones` whose scope check lives inside the cached method. A shared cache widens an entry from one JVM to every instance; production runs a single `lifecontrol-api` replica today, so the exposure is unchanged for now, and it is the same pre-existing defect either way.

S3 — the defect, verbatim from `odd/tasks/hr-org-structure.md` G12:

> **A pre-existing cross-tenant read: the company-scoped catalogs of the store tree cache their reads by a key that omits the company, with the scope check inside the cached method.** On a cache hit the check never runs, so a caller holding one of the admitted roles for company A is served company B's row or list once any legitimate read has warmed the entry.

and, in the same row:

> **Not this slice's to fix, and not fixed here**: `life-control-api/src/main/java/com/lifecontrol/api/company/service/CompanyRegionService.java:58,85` and `CompanyZoneService.java:66,92` pair `@Cacheable` (keys `'all-' + #companyCountryId + '-' + #includeDisabled` and `#id`) with `verifyCompanyRegionAccess`/`verifyCompanyZoneAccess` *inside* the cached method, and the read endpoints admit a role set rather than a claim.

> Fixing it is its own record: the options (drop the cache, key by company, or verify outside the cached call through a second bean) change behaviour of merged endpoints, which is why it is declared and not patched inside an HR slice.

S4 — the mechanism, decided by the user on 2026-10-17 and quoted verbatim from the option selected:

> **A — Sacar la cache de los cuatro reads**: Aplica el precedente T16 ya escrito en el árbol. Cero cambio de contrato observable; se pierde la cache de esos dos catálogos.
>
> **Qué se toca**
> - `CompanyRegionService`: quitar `@Cacheable` de `getAllRegions:58` y `getRegionById:85`; quitar los `@CacheEvict(allEntries=true)` de los writes.
> - `CompanyZoneService`: lo mismo en `:66`, `:92` y writes.
> - `CacheConfig`: las 8 regiones bajan a 6.
> - Tests: `CacheManagerSelectionTest.cacheTypeSimple_RegistersTheCacheRegions` y el set de representantes de `CacheValueCodecTest`.

S5 — the repository's own rule for the text that describes the cache (`AGENTS.md`, "Development Commands"): the documentation that describes the cache must describe what the code does. Its "Caching" section names the eight regions, so it moves with S4.

S6 — the pinning rule this record inherits from `hr-org-structure` T16, verbatim:

> A `@Cacheable` on a method whose body starts with the scope check serves a cache **hit** without executing that check at all — the annotation wraps the whole method. So caching a scoped read trades its authorization for a cache lookup.

## Tasks

| # | Task | S# | Route | Evidence |
| --- | --- | --- | --- | --- |
| T1 | Two proxy-based scoped-read tests for the four reads, written first and observed RED | S4, S6 | inline | RED: all four fail `TooFewActualInvocations: Wanted 2 times … But was 1 time`, each raised from the cached service method |
| T2 | Remove the four `@Cacheable` and the eight `@CacheEvict` from the two services | S4 | inline | the same four tests GREEN, and the 43 existing `company/` controller+service+security test groups at 0 failures |
| T3 | Reduce `simpleCacheManager`'s regions from eight to six and move the two tests that count them | S4 | delegated writer (`gentle-ai-worker`), one round | `CacheManagerSelectionTest` 6/0, `CacheValueCodecTest` 3/0 |
| T4 | Stop calling the unscoped repository query "cached" in the test that names it that way | S4 | delegated writer | `CompanyRegionServiceTest` 26/0 |
| T5 | Move every piece of text that describes the cache with the region list: `AGENTS.md`, the two HR javadocs that named `CompanyRegionService` as the caching pattern, and the two remaining "eight" strings | S5 | inline + delegated writer | docs only; `spotlessCheck` covers Java only, so none of it is linted |
| T6 | Close G12 in `hr-org-structure.md` with the evidence and this record named | S2, S3 | inline | the record's own G12 cell, plus the T16 correction it needed and L14 in `redis-cache-activation.md`, whose L13 sentence "L10's two follow-ups … remain open" the closure falsified |
| T7 | Gates and independent verification | S3, S4, S5 | inline + `gentle-ai-verify` | suite in the worktree against the baseline in the anchor, `spotlessCheck`, `spotbugsMain` |
| T8 | The work unit as a commit on `fix/scoped-catalog-cache-bypass` | S4 | inline | `8be0436` — 12 files, 327 insertions, 71 deletions, 398 authored changed lines; the records ride the second commit |

## Log

**L1** (2026-10-17, original request, verbatim):

> puedes revisar  De redis-cache-activation L10 (siguen abiertos por su propia letra, L13 lo confirma):
>  - application-test.properties excluye
>    org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration — nombre de Boot
>    3. En 4.1.1 es DataRedisAutoConfiguration, así que la exclusión es muerta y todo contexto
>    de test levanta un LettuceConnectionFactory.
>  - hr-org-structure G12: lectura cross-tenant preexistente de companyRegions/companyZones, con
>    el chequeo de scope adentro del método cacheado. Hoy hay una sola réplica, así que la
>    exposición no cambia; con la cache ya activa, deja de ser un detalle local.

**L2** (2026-10-17, what the review confirmed, measured on the anchor at `135cdbe`):

- Both follow-ups are open, and neither is stale:
  - `life-control-api/src/test/resources/application-test.properties:28` still carries the Boot 3 name. Boot is `4.1.1` (`build.gradle:3`); `spring-boot-data-redis-4.1.1.jar`'s `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` registers only `org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration`, and `dependencyInsight --configuration testRuntimeClasspath --dependency spring-boot-autoconfigure` selects `4.1.1` only — the `3.5.16` jar in the Gradle cache is not on this classpath, so the excluded name is absent from it. Because `build.gradle:101` sets `spring.profiles.active=test` for the whole suite and the suite is green, Boot 4 provably does not reject an exclusion name that is not on the classpath. **Left out of this record's scope on purpose**: it is its own unit, exactly as the antecedent's L10 says.
  - G12's code shape is exactly as recorded: `CompanyRegionService:58,85` and `CompanyZoneService:66,92`.
- **One correction to G12, and it is why this record exists**: G12 frames the defect as cross-tenant and states that exploitability "needs the row or parent UUID to be known or guessed". There is a cheaper variant it does not name. The key `'all-' + #companyCountryId + '-' + #includeDisabled` serves **two different result semantics**: a caller holding `lc-company-region`/`lc-company-region-read` (or `lc-company-zone`/`lc-company-zone-read`) gets the list filtered by their `company_region_id`/`company_zone_id` claim (`getCompanyRegionIds()` / `getCompanyZoneIds()`), while `lc-admin`, `lc-company` and `lc-company-country` callers get the full country list. Both are stored under the same key, so whoever warms the entry decides for every caller for the entry's whole life. Broader-caller-first → a region-scoped caller receives regions outside their claim: **scope escalation with no UUID guessed and with a single replica**. Reverse order → silent truncation for the broader caller. Both role sets are provisioned (`docker/scripts/keycloak-setup.sh:487-488`), so both callers exist.
- Two more corrections that change the multi-replica calculus the antecedent states:
  - "The exposure is unchanged for now" holds only for the cross-tenant variant. The intra-tenant one was already live under the always-active `SimpleCacheManager` (antecedent L3.1), and that manager has **no TTL** while the Redis one has 1h — so activation did not widen it, it bounded it.
  - `docker/docker-compose.yml:302-313` runs Redis with `--appendonly yes` on the persisted `${VOLUMES_ROOT}/redis/data`, so an entry survives a Redis restart inside its TTL and is shared by every replica that exists.
- The sweep that bounds the work: the four sites above are the **only** `@Cacheable` methods with a scope check inside. `EmployeeService`, `DepartmentService`, `PositionService`, `PositionRoleService`, `PositionSalaryBandService` and `ContractService` already carry javadoc saying they are deliberately uncached for exactly this reason (T16), and the remaining regions (`countries`, `measureUnits`, `paymentMethods`, `seniorityLevels`, `statuses`, `statusTypes`) hold global data with no scope check.
- The fact that decided against the "cache the canonical value" option: the two branches of the current method do **not** share an order. The filtered path uses `findByIdInAndCompanyCountryId` (`CompanyRegionRepository:26`) with **no `ORDER BY`**, so its order is already unspecified by Postgres; the unfiltered path uses `findByCompanyCountryIdOrderByRegionNameAsc`. Caching one canonical value would therefore have forced a new, observable order on the filtered response — a contract change this record does not need to take.

**L3** (2026-10-17, the decision, verbatim from the option the user selected):

> **A — Sacar la cache de los cuatro reads**: Aplica el precedente T16 ya escrito en el árbol. Cero cambio de contrato observable; se pierde la cache de esos dos catálogos.

Its declared cost, accepted rather than hidden: each regions/zones screen load returns to the database. The queries are per country/region and small, and nobody ever measured the benefit — the cache that was nominally serving them was an in-heap `SimpleCacheManager` with no TTL, chosen by accident (antecedent L3.1), not by measurement.

**L4** (2026-10-17, the second consequence of S4, decided here because S4 names it but does not settle it): the eight `@CacheEvict(allEntries = true)` on the write methods go, together with the four `@Cacheable`. Leaving an eviction whose region is never cached is the same class of dead configuration this whole review started from (the dead exclusion in S1's first bullet), and the alternative — keeping the two regions declared in `simpleCacheManager` with no `@Cacheable` pointing at them — would do it in `CacheConfig` instead. **Nothing else about the writes changes**: every write still verifies its scope and every write's ordering of checks is untouched.

**L5** (2026-10-17, where the work lives): the user authorized the sibling worktree the repository's convention requires, `/home/leo3nava/workspace/LifeControl-worktrees/fix-scoped-catalog-cache-bypass`, branch `fix/scoped-catalog-cache-bypass` off `main @ 135cdbe`. The anchor stays clean on `main`.

**L6** (2026-10-17, T1 and T2, measured — the RED is the proof the defect was live):

- `CompanyRegionServiceScopedReadCacheTest` and `CompanyZoneServiceScopedReadCacheTest` were written first and run **before** any production change. All four fail, and they fail for exactly the reason this record exists:

```
org.mockito.exceptions.verification.TooFewActualInvocations:
currentUserContext.verifyCompanyRegionAccess(…, null);
Wanted 2 times: -> at CurrentUserContext.verifyCompanyRegionAccess(CurrentUserContext.java:236)
But was 1 time: -> at CompanyRegionService.getAllRegions(CompanyRegionService.java:61)
```

the same for `getRegionById` (`:88`), `getAllZones` (`:70`) and `getZoneById` (`:95`). The scope check ran **once** for two identical calls: the cache served the second one, which is the bypass G12 declares, demonstrated rather than argued. This is also why the tests are proxy-based — a plain unit test cannot see the annotation at all.
- After the four `@Cacheable` and eight `@CacheEvict` were removed, the same four tests pass, alongside all 43 existing `company/` test groups (service, controller and controller-security classes, filtered run, 0 failures). The HTTP contract is therefore unchanged, which is S4's "cero cambio de contrato observable" measured on the existing tests rather than asserted.
- The worker's round moved the two tests that count regions (`CacheManagerSelectionTest` 6/0, `CacheValueCodecTest` 3/0) and the one that named the removed cache (`CompanyRegionServiceTest` 26/0).
- **Two defects in this record's own brief, found by the delegated writer and fixed by the parent**: the brief said "change nothing else in the class" about `CacheManagerSelectionTest`, which left its `@DisplayName` claiming "the eight cache regions" — now false and visible in every test report — and the writer also reported `RedisOutagePolicyTest`'s class javadoc saying "Only eight cache regions exist". Both are the S5 defect class (text describing a cache that no longer exists), so both were corrected. Recorded because the instruction, not the writer, was wrong.

**L7** (2026-10-17, independent verification of the finished tree, read-only):

`gentle-ai-verify` returned **MET on all five claims it was given**, and **no finding that contradicts this record**:

- **C1** — the whole main tree carries 14 live `@Cacheable` annotations and none of them has a scope check inside its body; the two services carry zero cache annotations. No fifth site.
- **C2** — the two new tests are proxy-based, assert on the scope check itself with `times(2)`, and would fail if a `@Cacheable` came back: either the second call is served from the cache (`TooFewActualInvocations`) or the region is absent and Spring throws `Cannot find cache named 'companyRegions'` on the **first** call. The four methods they exercise are exactly the four that were changed.
- **C3** — `git diff --ignore-all-space 135cdbe` over the two services shows only cache, import and javadoc lines: same branches, same repository methods, same orderings, and `@Transactional` options intact including which reads are `readOnly`. The controller tests are untouched.
- **C4** — `simpleCacheManager`'s six regions equal exactly the distinct regions the remaining `@Cacheable` names, and the scan guard still fails on an unregistered cached return type.
- **C5** — every non-historical hit is correct; the literal `companyRegions`/`companyZones`/`eight` hits that remain are all frozen history in `odd/tasks/redis-cache-activation.md`, `odd/tasks/hr-org-structure.md` and this record's own specs.

**What its residue leaves open, and what closes it**: it did not execute Gradle, by instruction, because the parent's full-suite run was in flight — so C2's "would fail if reintroduced" and C4's "the guard still bites" rest on the tree plus the RED this record measured. That residual is closed by L6's observed `TooFewActualInvocations` on all four tests and by L8's full suite, not by argument.

**Three probe tests it proposed, declined and declared rather than taken**: (1) a test asserting the scanned `@Cacheable` region set exactly equals `simpleCacheManager`'s; (2) an annotation-absence test on the two services; (3) a repository-wide scan asserting no `@Cacheable` declaring class touches `CurrentUserContext`. The first two pin at a second level what the proxy tests already pin, and S4 asked for none of the three. The third is the only one covering something nothing covers today — a **new** `@Cacheable` naming a region nobody registered, on an already-represented return type, which `CacheValueCodecTest`'s `contains` (not `equals`) and `CacheManagerSelectionTest`'s manager-side set both miss. It is a declared follow-up, not a defect of this change.

**L8** (2026-10-17, the gates, measured, and the commit):

- Baseline, previous code: the anchor at `main @ 135cdbe` → **920 classes, 3520 tests, 0 failures, 0 errors, 0 skipped**.
- This tree: **922 classes, 3524 tests, 0 failures, 0 errors, 0 skipped**, with `spotlessCheck` and `spotbugsMain` green (`./gradlew spotlessCheck spotbugsMain test`, exit 0).
- The delta is exactly the two new classes and their four tests. No class disappeared, no class changed its count, and the 43 existing `company/` groups (service, controller, controller-security) ran unchanged — which is S4's "cero cambio de contrato observable" measured against the previous code rather than asserted.
- The work unit is commit **`8be0436`**: 12 files, 327 insertions, 71 deletions, **398 authored changed lines**, inside the ~400-line review slice, so it needs no chaining. The records ride the second commit.
- **Declared follow-up this record leaves, from L7**: a repository-wide guard that a *new* `@Cacheable` cannot name a region nobody registered on an already-represented return type — the one case today's `contains`-based scan and the manager-side exact set both miss.

**L9** (2026-10-17, the delivery slicing, declared because the tooling counts the whole branch): the
feature is **two commits** and the slice boundary is between them. `8be0436` — behaviour, tests and the
docs that describe them, 12 files, **398 authored changed lines** — is the review unit; it is inside
the ~400-line slice on its own, so it needs no chaining. The **records commit** carries the rest of the
branch: this record, G12's closure in `hr-org-structure.md`, and L14 in `redis-cache-activation.md`.
The branch total is above the slice once this record is counted, and that total is deliberately not a
review unit: the second commit is documentation with no behaviour to verify and is revertable without
touching the first. The branch total carries **no number here on purpose** — it moves every time this
record is edited, which is the same trap the antecedent's header fell into — and the count that
decides is the review unit's: 398. The records commit is named by its content rather than by its own
commit id for the same reason: it holds this file, so any later amend of it would falsify the id
written here. The repository's own precedent splits the same way — the antecedent's L12 records
`06f53b3` for behaviour and tests and `40179ff` for the records.

**L10** (2026-10-17, delivery): **PR #413**, `fix/scoped-catalog-cache-bypass` → `main`, carrying the
two commits above (`8be0436` behaviour, tests and docs; `4f62e7f` the records) and labelled `type:bug`,
exactly one `type:*` as the repository's label guard requires.

**The issue, and the one step this record did not take by itself.** The repository requires an
approved issue before a pull request is opened — `CONTRIBUTING.md` §1, and the label guard's
`A linked, approved issue` job is a required status check on `main` — so **#411** was opened from this
review with the RED pasted as its evidence, and `status:approved` was then applied through the
authenticated maintainer session on the maintainer's explicit instruction. Recorded that way on
purpose: `references/labels.md` invariant 4 and `.github/ISSUE_TEMPLATE/config.yml` both state that
`status:approved` is a **maintainer decision applied after approval**, and the guard reads the label
name only (invariant 6) — so the decision was the human's, and this record names it as the human's
rather than presenting it as an independent approval it was not.

**L11** (2026-10-17, the PR's CI, and one citation of mine that was wrong):

- All three checks pass on `a092642`, the pre-merge head: **`API CI` in 4m41s** (`spotlessCheck` +
  `spotbugsMain` + the suite), **`Exactly one type:* label`** in 11s and **`A linked, approved issue`**
  in 6s. The two guard jobs are the only **required** status checks on `main` and the only workflows
  without a `paths:` filter — re-verified against `origin/main`'s `pr-label-guard.yml`, whose trigger
  set is `[opened, reopened, synchronize, labeled, unlabeled, edited]` — so they re-run on every push
  and keep the branch mergeable without `API CI` having to run again.
- **Repair to L8's citation, caught by checking `main` before pushing anything else**: L8 records the
  baseline as "the anchor at `main @ 135cdbe`". That is the commit this work branched from, but it is
  **not** the commit the anchor held when the run started. `git reflog show main` dates the moves: the
  anchor was at `135cdbe` until 18:58, at `ee1ca8f` from 20:14:50, and at `58cec5e` from 20:53:18,
  while the baseline suite ran at 20:23-20:28 — so the baseline was measured on **`ee1ca8f`**. The count
  itself is unaffected and stays valid: `git diff --stat 135cdbe..58cec5e -- life-control-api/` is
  empty, so the Java tree and its test count are identical on all three commits. L8 is left standing
  rather than rewritten, the same discipline the antecedent's L13 applied to its own header, and the
  pull request body carries the correction.
- The branch was then updated onto `main`, which branch protection's `strict: true` requires before a
  merge: merge commit **`2e32676`**, no conflicts, and none of main's eight commits touches
  `life-control-api/**`. Two of them do touch what this slice depends on —
  `.github/workflows/pr-label-guard.yml` (the `synchronize` trigger and the queued-second-event fix,
  PR #412) and the root `AGENTS.md` — which is why the guard jobs, and not `API CI`, are what a
  records-only push has to satisfy.
- **A divergence this record measured and deliberately did not resolve**: `CONTRIBUTING.md`'s table
  reads "Required approving reviews | 1", while the live branch protection on `main` reports
  `required_approving_review_count: 0` — alongside `strict: true`, `dismiss_stale: true`,
  `conversation_resolution: true` and the two guard jobs as the only required checks. No record under
  `odd/tasks/` documents the applied value. Whichever is right, the merge is the human's decision, so
  this record keeps the measurement instead of acting on it.
