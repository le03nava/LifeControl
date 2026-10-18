# ODD feature: redis-cache-activation

**Repository**: LifeControl — module `life-control-api/` (`config/cache`, `status/service`, `status/repository`, `AGENTS.md`)
**Status**: merged — PR #371 (`fix/redis-cache-activation` @ `a402988`), 2026-10-17. The evidence is in the log (L3-L12). One decision is still open and needs the human: the Redis-outage failure policy (L9, D1). (**Repair, 2026-10-17: that decision was taken by the human and is implemented in its own record, `odd/tasks/redis-outage-failure-policy.md` — a bounded `spring.data.redis.timeout` plus `management.health.redis.enabled=false`; the sentence is kept as written and superseded here rather than erased, and the repair is recorded in L13**). Two follow-ups this work found and deliberately did not take are recorded in L10.
**Created**: 2026-10-17 · **Risk**: item 4 — the cache stops being per-JVM and becomes shared state across instances; independently verified by `gentle-ai-verify` (claims C1-C5 met, no blockers)
**Antecedent**: `odd/tasks/spring-boot-4-1-migration.md`, Gap 1

## Specs

S1 — the request, verbatim: "si has el gap 1" (answering "que esta pendiente" over the two Spring Boot records).

S2 — Gap 1 as the antecedent record states it, verbatim: "the Redis cache path was not exercised end to end".

S3 — the hypothesis that record left open, verbatim: "with no `CacheManager` visible to the auto-configuration, `application-prod.properties`' `spring.cache.type=redis` may not be honoured at all".

S4 — the property decides. `spring.cache.type` selects the cache manager: `redis`, and an absent value, mean the shared `RedisCacheManager` with a 1-hour TTL; `simple` means the in-memory manager the `test` profile uses; `none` means caching is off. No environment may end up with a manager other than the one its configuration names.

S5 — every value the Redis cache holds must be reconstructible by the Jackson 3 serializer. A Redis read has no declared type to fall back on, so a value that cannot be reconstructed is a defect and not a miss: the endpoint fails for the entry's whole TTL.

S6 — the HTTP contract of every cached endpoint must not change, including `GET /api/status-types`'s paging, sorting and total.

S7 — the documentation that describes the cache must describe what the code does.

## Tasks

| # | Task | S# | Route | Evidence |
| --- | --- | --- | --- | --- |
| T1 | Measure why the property was inert and fix the selection in `CacheConfig` | S3, S4 | inline | RED → GREEN in `CacheManagerSelectionTest`; the two extra measured facts in L3 |
| T2 | Restore `spring.cache.type=none` as a working configuration (`NoOpCacheManager`) | S4 | inline | `cacheTypeNone_DisablesCaching`; advisory A2 of the independent pass |
| T3 | Fix the cache-hit defect that activation exposed in the `statusTypes` region | S5 | delegated writer (`gentle-ai-worker`), two rounds | live 500 → 200 on a hit (L6); the in-memory-slice round was rejected because it dropped `?sort=` |
| T4 | Pin every cached value type against the production serializer | S5 | delegated writer | `CacheValueCodecTest`: 16 representatives plus a `@Cacheable` scan guard, proven able to fail |
| T5 | Pin the `statusTypes` region through the cache proxy | S5, S6 | inline | `StatusTypeServiceCacheTest`: cache hit, per-key entries, both key shapes, region-wide eviction |
| T6 | Repair the eight test comments that explained the selection with an exclusion that no longer exists | S7 | inline | those comments named a Boot 3 class as the reason Redis was excluded |
| T7 | Update `life-control-api/AGENTS.md`, whose cache section described the fallback behaviour that never worked | S7 | inline | advisory A1 of the independent pass |
| T8 | Verify live, differentially and independently | S2, S4, S6 | inline + `gentle-ai-verify` | L5-L8 |

## Log

**L1** (2026-10-17, original request, verbatim):

> que esta pendiente de spring-boot-4-1-migration ... si has el gap 1

**L2** (2026-10-17, the antecedent's Gap 1, quoted verbatim from `odd/tasks/spring-boot-4-1-migration.md`):

> **Gap 1, the Redis cache path was not exercised.** The API logs `Redis not available — using SimpleCacheManager fallback (in-memory, no TTL)` and `redis-cli dbsize` is 0, so dev caches in memory. ... **Second consequence, worth its own investigation outside this migration**: with no `CacheManager` visible to the auto-configuration, `application-prod.properties`' `spring.cache.type=redis` may not be honoured at all — a hypothesis from the log and the bean conditions, not a measured fact.

**L3** (2026-10-17, the cause, measured — three independent facts):

1. `CacheConfig.redisCacheManager` was guarded by `@ConditionalOnBean(RedisConnectionFactory.class)` inside a user `@Configuration`. That condition is evaluated before `DataRedisAutoConfiguration` registers the factory, so it was false in every environment; `@ConditionalOnMissingBean(RedisConnectionFactory.class)` on `simpleCacheManager` therefore won everywhere. Live proof: dev logs `Redis not available — using SimpleCacheManager fallback` while `REDIS_HOST=redis` and the Redis container is healthy, with `redis-cli dbsize` at 0.
2. Nothing else read the property either: `./gradlew dependencyInsight --dependency spring-boot-cache` returns "No dependencies matching" for `compileClasspath`, `runtimeClasspath` and `testRuntimeClasspath`. The module declares no `spring-boot-starter-cache`, so Boot's `CacheAutoConfiguration`, `CacheProperties` and `SimpleCacheConfiguration` are all absent and `CacheConfig` is the only source of a `CacheManager`. Boot's own `RedisCacheConfiguration` is an auto-configuration and *would* see the factory; a user configuration cannot.
3. `application-test.properties` excludes `org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration`, a Boot 3 class name. In 4.1.1 it is `org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration`, and the old name survives only in `spring-boot-autoconfigure-3.5.16.jar`. Boot's `AutoConfigurationImportSelector.checkExcludedClasses` only rejects an exclusion when `ClassUtils.isPresent(...)` is true, so a name that no longer exists is silently ignored and every test context still gets a `LettuceConnectionFactory`. That line is dead configuration and is recorded as an open follow-up, not fixed here.

**L4** (2026-10-17, the fix, T1-T2 and T6):

- `CacheConfig`: `@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)` on `redisCacheManager`, `havingValue = "simple"` on `simpleCacheManager`, and a third branch `havingValue = "none"` returning `NoOpCacheManager` so that the property's "disable caching" value is a working configuration instead of a context with no manager at all.
- RED before it, GREEN after: `CacheManagerSelectionTest` failed with `SimpleCacheManager` where a `RedisCacheManager` was required, even with the factory present in the context (3 of 5 cases), and passes with 6 cases now.
- Eight `*CacheTest` comments explained the selection as "Redis auto-configuration is excluded"; that exclusion is the dead one from L3, so the reason they state was replaced with the property that really decides.
- `life-control-api/AGENTS.md` still documented "Redis when available, in-memory `ConcurrentMapCache` fallback" — behaviour that never ran. Its three cache lines now describe the property, the eight regions and the serializer constraint.

**L5** (2026-10-17, the live exercise — the first time this path ever ran):

- After a redeploy of the API, the container logs `Initializing Redis cache manager with 1-hour TTL and JSON serialization` and zero occurrences of the fallback line.
- `GET /api/countries` → 200 in 1.22s, the key `countries::all-false` appears in Redis with a TTL of 3595 counting down, the second call is 200 in 0.098s with the TTL unchanged, and the two bodies are identical. The stored payload carries `@class` type ids, so Jackson 3 default typing is on the wire.

**L6** (2026-10-17, the defect that only activation could reveal — T3):

- The first independent verification of this change (`gentle-ai-verify`, read-only) found it live: `StatusTypeService.getAllStatusTypes` was `@Cacheable` and returned `Page<StatusTypeResponse>`. In memory the value stayed an object reference and a hit could not fail; in Redis it was serialized and `PageImpl` has no Jackson creator, so `GET /api/status-types` answered **500 on every cache hit** for the entry's whole TTL:

```
org.springframework.data.redis.serializer.SerializationException: Could not read JSON: Cannot construct
instance of `org.springframework.data.domain.PageImpl` (no Creators, like default constructor, exist)
Caused by: tools.jackson.databind.exc.InvalidDefinitionException ...
```

- A sweep of every `@Cacheable` site showed this was the only value type that could not be reconstructed: the other eleven methods cache `List<*Response>` or `*Response`.
- Fix: a new `StatusTypeCatalog` owns the region's paged read and caches a `StatusTypePage(List<StatusTypeResponse> content, long total)` record; `StatusTypeService` wraps it with the request's own `Pageable`. The first attempt sliced an unpaged list in memory and was **rejected** because it silently dropped `?sort=` (and in-memory string ordering would have diverged from the database collation); the repository query stayed paged, so ordering and counting still come from JPA.
- Live after the fix: `status-types` 200 in 1.22s on a miss and 200 in 0.10s on a hit, identical bodies, TTL 3597, `?sort=statusTypeName` honoured in both directions, `page=1` and `search=order` at 200, the other four regions at 200, and zero `SerializationException` in the log.
- Guard (T4): `CacheValueCodecTest` round-trips a representative of all 16 cached return types through the production serializer and fails when a `@Cacheable` return type has no registered representative; the guard was proven able to fail by removing a representative. `CacheManagerSelectionTest` and `StatusTypeServiceCacheTest` pin the selection and the region's caching, key shapes and eviction.

**L7** (2026-10-17, differential — the suite before and after):

- Baseline: a clean worktree of `main` at `31a15b8` → **3498 tests, 0 failures, 0 errors, 915 suites**.
- Working tree: **3505 tests, 0 failures, 0 errors, 917 suites**. The delta is exactly the two new suites, no suite disappeared and no suite changed its count.
- Note: `main` advanced to `6ee5752` during this work (PR #370, a docs-only merge touching four `odd/tasks/*.md` files and no code or test), so the code delta in that comparison is entirely this change.
- Final gate on the finished tree, after the `spring.cache.type=none` branch and the tests adopted from the independent pass were added: **3511 tests, 0 failures, 0 errors, 918 suites**, with `spotlessCheck` green.

**L8** (2026-10-17, independent verification of the final diff):

`gentle-ai-verify` on the finished change: C1 (selection) MET, C2 (every cached type round-trips, guard not vacuous) MET, C3 (no request shape differs from `HEAD`) MET, C4 (key scheme and region-wide eviction) MET, C5 (no other consumer of the old shape) MET. No blockers. Its advisories are what T2, T6 and T7 closed; it also confirmed the serializer round-trips the list shapes production really produces (`Stream.toList` → `ListN`, `Page#map(...).getContent()` → `UnmodifiableRandomAccessList`), which is now pinned in the codec test.

**L9** (2026-10-17, the operational consequence, measured — the open decision):

With Redis stopped, in dev, with the API otherwise healthy:

| Measurement | Result |
| --- | --- |
| cached read, request 1 | **500 after 60.15s** |
| cached read, request 2 | **500 after 60.06s** — every request, not a one-off |
| `GET /actuator/health` | no response (>20s): the Redis health indicator blocks too |
| log | `java.net.ConnectException: Connection refused` |
| after Redis returns | automatic recovery; first read 12.5s, then 200; health 200 |

Nothing configures `spring.data.redis.timeout`, so the Lettuce default (60s) bounds the wait. Before this change production never touched Redis, so a Redis outage was invisible; now a cached read blocks for a minute and then fails, and the health endpoint blocks as well, which in an orchestrated environment means a timed-out probe. **D1 — the decision this leaves open:** whether to ship that behaviour as the honest consequence of a shared cache, or to add a failure policy (a bounded `spring.data.redis.timeout`, a `CacheErrorHandler` that degrades a cache failure to a database read, and/or excluding Redis from the health indicator). Each has a real trade-off — degrading to the database restores availability but hides a broken cache — so it is the human's call and it is not implemented here.

**L10** (2026-10-17, follow-ups this work found and did not take):

- `application-test.properties` excludes a Boot 3 class name (L3.3): the exclusion is dead and every test context still creates a `LettuceConnectionFactory`. The fix is the renamed class; it is a test-infrastructure change of its own.
- `odd/tasks/hr-org-structure.md` G12 records a pre-existing cross-tenant read of `companyRegions`/`companyZones` whose scope check lives inside the cached method. A shared cache widens an entry from one JVM to every instance; production runs a single `lifecontrol-api` replica today, so the exposure is unchanged for now, and it is the same pre-existing defect either way.
- The value shape under the `statusTypes` keys changed from `PageImpl` to `StatusTypePage`. No Redis entry can predate this, because before this change nothing ever wrote cache data to Redis.

**L11** (2026-10-17, delivery slicing — 894 authored changed lines, 820 insertions and 74 deletions, in two commits):

Chaining this into two PRs was considered and rejected. The `statusTypes` payload fix is only *reachable* through the cache activation, and the activation is unsafe without it: the moment Redis is real, `GET /api/status-types` answers 500 on every cache hit. The two halves therefore cannot be reviewed as independent slices — the smaller half would be a knowingly broken intermediate state, and a stacked chain would have to carry that broken base. The slice boundary is stated in the PR body instead, so the reviewer can see where the volume is: four production files (153 lines, of which `CacheConfig` is 70) carry the behaviour, and three new suites plus the rewritten unit test (574 lines) carry the evidence. The split was also measured before rejecting it: the payload-fix half is 516 lines and the activation half 378, so chaining would not have brought either under the ~400-line slice the convention aims for.

**L12** (2026-10-17, delivery):

Merged as **PR #371** (`fix/redis-cache-activation` @ `a402988`), two commits: `06f53b3` (behaviour and tests) and `40179ff` (the records). CI on the PR: **API CI pass in 4m33s** (`spotlessCheck` + `spotbugsMain` + the suite) and the **`PR Label Guard` pass in 8s**; the label was `type:bug`, exactly one `type:*`, which the repository's own script confirms locally. Gates run before opening the PR: the full suite on the finished tree (3511 tests, 0 failures) and a differential against `main` at `31a15b8` (3498 tests, 0 failures) whose only delta is the new suites; `spotlessCheck` and `spotbugsMain` on the branch; the live end-to-end check on the final artifact; and an independent verification pass that met all five of its claims with no blockers. The native review was assessed and not due (`medium` risk, `under_budget`), so none was started. The branch and its worktree were removed after the merge and the anchor is clean on `main`.

**L13** (2026-10-17, the repair this record's header needed):

D1 was the human's call and this record said so; it was taken on 2026-10-17, choosing the bounded
timeout plus the health exclusion over degrading a cache failure to a database read. The policy is
implemented in its own record, `odd/tasks/redis-outage-failure-policy.md`: `spring.data.redis.timeout=2s`
bounds the wait L9 measured at 60.15s per request, and `management.health.redis.enabled=false` keeps
Redis out of `/actuator/health`, the endpoint the container healthcheck reads. The header's claim that
the decision "is still open and needs the human" is false now, so it is superseded in place: the
original sentence is kept as written rather than erased, and this entry is the repair's record. L10's
two follow-ups are untouched and remain open.

**L14** (2026-10-17, half of L13's last sentence, falsified and repaired): L10's **second** bullet —
G12 — is **closed**. It was taken as its own record on the user's decision,
`odd/tasks/scoped-catalog-cache-bypass.md`: both services lost their four `@Cacheable` and their eight
`@CacheEvict(allEntries = true)`, `simpleCacheManager` went from the eight regions L4 named to six, and
two proxy-based tests pin the scope check running on every call (commit `8be0436`). L13's "L10's two
follow-ups are untouched and remain open" is therefore true of the **first** bullet and false of the
second, so it is superseded here rather than erased — the same repair discipline L13 applied to the
header. The **first** bullet, `application-test.properties`' dead Boot 3 exclusion that L2 and L3.3
measured, **is still open and still untouched**: no jar on `testRuntimeClasspath` carries
`org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration` at Boot 4.1.1, whose registry
offers `org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration` instead, so the
line excludes nothing. L4's "the eight regions" is left alone on purpose: it is that entry's record of
what that change did, frozen history rather than a live claim, and this L14 is where the count moves.
