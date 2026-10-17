# ODD feature: redis-outage-failure-policy

**Repository**: LifeControl — `life-control-api/` (`src/main/resources/application.properties`, `src/test/java/com/lifecontrol/api/config/cache/`, `AGENTS.md`), plus the antecedent record whose open decision it resolves: `odd/tasks/redis-cache-activation.md` (D1, in its L9).
**Status**: merged — PR #378 (`fix/redis-outage-failure-policy` @ `9c01300`), 2026-10-17, with the post-merge `main` CI green on all four workflows. Nothing remains open in this slice; the two follow-ups the antecedent record declares in its own L10 are separate units.
**Created**: 2026-10-17 · **Risk**: item 5 (operational configuration) — the change decides what the container probe sees and how long a cached read blocks while Redis is unreachable; to be independently verified by `gentle-ai-verify`
**Antecedent**: `odd/tasks/redis-cache-activation.md`, decision **D1** (L9), which recorded the measurement and left the policy to the human

## Specs

**S1** — The request, verbatim: *"arranca coon el d1"*.

**S2** — The decision, verbatim: *"Timeout acotado + Redis fuera del probe"*, described at the point of choice as *"Recomendada: 500 en ~2s en vez de 60s, y un Redis caído no voltea el pod; los endpoints no cacheados siguen sirviendo"*.

**S3** — What motivates it, quoted verbatim from the antecedent's L9: *"Nothing configures `spring.data.redis.timeout`, so the Lettuce default (60s) bounds the wait. Before this change production never touched Redis, so a Redis outage was invisible; now a cached read blocks for a minute and then fails, and the health endpoint blocks as well, which in an orchestrated environment means a timed-out probe."* The measured rows were `500 after 60.15s` and `500 after 60.06s` on a cached read, and `GET /actuator/health` with *"no response (>20s): the Redis health indicator blocks too"*.

**S4** — What the policy must deliver, stated as behaviour and not as the mechanism:

- **S4a** — A cached read against an unreachable Redis fails in **bounded time**, far below the 60-second Lettuce default, and it fails the same way on every request rather than once.
- **S4b** — The health endpoint that the container probe consumes must **neither block on Redis nor report the service down because of it**, so a Redis outage does not take down the endpoints that do not read the cache.
- **S4c** — No other endpoint's behaviour changes, and the HTTP contract of every cached endpoint is untouched.

**S5** — The cost of the chosen option is stated, not smoothed over: the health endpoint **stops reporting Redis**, so that signal moves to the log (`ConnectException`) and to the failing reads themselves. The alternative — a `CacheErrorHandler` degrading to a database read — was offered and **not** chosen, because it hides a broken cache behind restored availability; it remains constructible on top of this policy.

**S6** — The documentation that describes the cache must describe what the code does (carried forward from **S7** of the antecedent record).

## Tasks

| # | Task | S# | Route | Evidence |
| --- | --- | --- | --- | --- |
| T1 | Pin the bounded timeout and the absent Redis health contributor in a failing test | S4a, S4b | inline | RED observed before the fix |
| T2 | Implement the policy in `application.properties` | S4a, S4b | inline | GREEN on T1; no other property changed |
| T3 | Update the cache section of `life-control-api/AGENTS.md` | S6 | inline | the docs describe the policy and its cost |
| T4 | Repair the antecedent record: D1 resolved, pointer to this record | S2 | inline | `redis-cache-activation.md` header and D1 row |
| T5 | Run the gate and the differential against the baseline suite | S4c | inline + `bash_background` | baseline vs working tree counts |
| T6 | Independent verification of the finished diff | S4a, S4b, S4c | `gentle-ai-verify` | verdict per claim |
| T7 | Deliver it: issue, pull request, merge and the delivery row | — | inline | issue #377; PR #378 merged as `9c01300`; L9 |

## Log

**L1** (2026-10-17, original request, verbatim):

> arranca coon el d1

**L2** (2026-10-17, the inventory that grounded the decision, measured by the parent before asking):

Three facts, each verified with the command that produced it:

| Fact | Evidence |
| --- | --- |
| Nothing bounds the Redis timeout | `grep -n "timeout" src/main/resources/application*.properties` → 0 matches. The Lettuce default (60s) is what L9 measured |
| No `CacheErrorHandler` exists anywhere | `grep -rn "CacheErrorHandler" src/main/java` → 0 matches. A cache failure is a 500, not a fallback |
| The health contributor's id is `redis` | `DataRedisHealthContributorAutoConfiguration` (Boot 4.1.1) registers the bean `redisHealthContributor`, so the switch is `management.health.redis.enabled` |

The point that decided the framing, and that the antecedent's L9 does not state: **only eight cache regions exist**, so a Redis outage leaves the rest of the API working. But because `/actuator/health` includes `redis`, the probe blocks and the whole pod is declared down — losing the endpoints that never touch the cache. The real probe was measured too: `docker/docker-compose.yml` healthchecks the API with `wget -qO- http://localhost:8082/actuator/health`, `timeout: 3s`, `retries: 3`.

Four options were offered; **the first was chosen**. The full set, verbatim:

1. *"Timeout acotado + Redis fuera del probe"* — *"Recomendada: 500 en ~2s en vez de 60s, y un Redis caído no voltea el pod; los endpoints no cacheados siguen sirviendo"*
2. *"Lo anterior + degradar a base en falla"* — *"Máxima disponibilidad: el sitio anda sin Redis (más lento, más carga en la base), pero esconde el cache roto salvo que instrumentemos la degradación"*
3. *"Solo acotar el timeout"* — *"Cambio mínimo: el 500 llega rápido, pero el health sigue reportando Redis abajo y el pod queda unhealthy"*
4. *"Dejarlo como está y documentar"* — *"El cache es dependencia dura y su caída es una caída; cero código nuevo, se acepta el probe timeout en orquestado"*

The human chose **option 1** — the bounded timeout plus the health exclusion — which is the choice recorded in **S2**.

**L3** (2026-10-17, T1 — the RED, observed):

The new suite `RedisOutagePolicyTest` (4 tests) was written against unmodified `main @ 944c964` and run
before the fix. **3 of 4 failed**: both settings are absent from `application.properties` (each assert
reported `null`), and the timing test failed at context startup because `spring.data.redis.timeout=null`
is not a `Duration`. The fourth — the control — **passed**, which is its job: the Redis health contributor
*is* registered when the indicator is enabled, so the exclusion assertion cannot pass vacuously.

What the tests read matters: `/application.properties` from the test classpath, which is the artifact the
application ships, not values handed in by the test. The lesson the antecedent record paid for — a property
Boot never reads is inert, exactly as `spring.cache.type` was — is why every assertion checks the effect
(the value reaching the connection factory, the contributor absent from the context) and not only the file.

**L4** (2026-10-17, T2 — the GREEN, and the effect measured rather than inferred):

`application.properties` gains `spring.data.redis.timeout=2s` beside the Redis host/port and
`management.health.redis.enabled=false` beside the other `management.*` keys, each carrying its reason in a
comment. **GREEN: 4/4 pass** at this point.

The effect the decision promised is measured against a server that **accepts the TCP connection and never
answers** (the suite's `HungRedisServer`), because a closed port refuses the connection immediately and
therefore measures the connect failure rather than the command timeout. The independent verifier's raw probe
on that shape measured **2.3s @2s** against **12.09s @12s**; this record's own control run measured
**12.283s** at 12s. The assertion stays `elapsed < 10s` — far above the shipped 2s so a loaded machine is
not flaky, and far below the 60s Lettuce default so the default cannot come back unnoticed. The **1.495s**
figure this entry first carried was the whole test's wall time including Spring context startup against a
closed port; it is not a controlled differential and is replaced by the numbers above.

Why 2s and not a tunable: it is a design constant, not an operator knob. A Redis GET on the compose network
answers in milliseconds, so 2s is roughly twenty times the worst legitimate case. It is deliberately **not**
wired to a `REDIS_TIMEOUT` variable, because the compose passes only the variables it declares — such a
variable would promise an override that does not work.

**L5** (2026-10-17, T3 — the documentation):

`life-control-api/AGENTS.md`: the Caching section gains the outage policy (both settings, why the probe must
not see Redis, and the deliberate cost), and the Observability table's Health row now records that Redis is
excluded on purpose.

**L6** (2026-10-17, T4 — the antecedent repair):

`odd/tasks/redis-cache-activation.md`: the header's *"One decision is still open and needs the human"* is
superseded in place with a dated repair and a pointer to this record, the original sentence is kept as written
rather than erased, and **L13** there records the repair. That record's two L10 follow-ups are untouched and
remain open.

**L7** (2026-10-17, T5-T6 — the gate, the independent verification, and the two findings it raised):

Gate on the finished tree, `./gradlew spotlessCheck spotbugsMain test`: **919 suites / 3517 tests / 0 failures
/ 0 errors**, with `spotlessCheck` and `spotbugsMain` green. The delta against the inherited 3515 tests is
exactly the two health-endpoint tests added to `RedisOutagePolicyTest` (6 tests there now).

Independent verification of the diff: **5 claims MET, no blockers, two advisory findings (F1, F2)**. Both were
fixed:

- **F1 — the timing test did not discriminate.** It pointed the client at a *closed* port, which refuses the
  connection immediately, so the test passed whatever the timeout was; the verifier rewrote the shipped value
  to 60s and it still passed (raw probe: 283ms @2s, 4ms @60s). Fixed by replacing the closed port with a
  `HungRedisServer` that accepts and never answers, and by asserting through the real `HealthEndpoint` (both
  directions: disabled → no `redis` contributor and `UP`; enabled → `redis` present and `DOWN`) as well as
  through the contributor bean. The control that proves the fix is not vacuous: with `application.properties`
  temporarily set to `12s`, the timing test alone fails with
  `Expecting actual: 12.283125032S to be less than: 10S`; restored to `2s`, the diff against `main` for
  `application.properties` is again exactly the two added properties. L4's `1.495s` claim is corrected there.
- **F2 — a dropped character falsified a "kept as written" claim.** In `odd/tasks/redis-cache-activation.md`
  the header repair had removed the period that ended the original sentence
  (`…the Redis-outage failure policy (L9, D1).`), while the repair text says that sentence "is kept as
  written". The period is restored, so the claim is true.

**L8** (2026-10-17, the slice boundary):

Two work-unit commits carrying **449 authored changed lines**, past the ~400-line slice the convention aims
for. Chaining was considered and rejected: this record is the only place the policy's RED/GREEN evidence, the
gate counts and the two findings the independent verification raised live, so a docs-only pull request would
carry the evidence for a change the reviewer cannot see, and the code pull request would arrive without the
record that says what it measured. The antecedent record faced the same choice at 894 lines and rejected
chaining for a stronger reason — a knowingly broken intermediate state; here a split would be merely
inconvenient. The volume is also concentrated in one new test file (`RedisOutagePolicyTest`, six tests) rather
than spread across production code: the two production files carry **20 lines** between them.

**L9** (2026-10-17, T7 — delivery):

Merged as **PR #378** (`fix/redis-outage-failure-policy` @ `9c01300d0ec07ad8127ba91273aa8c134fc93512`),
2026-10-17T22:23:19Z, closing **issue #377**. Three commits: `855d3e6` (the policy, its test and the docs),
`4bdb9da` (the records and the antecedent repair) and `d7ab083` (the slice boundary, L8).

The merge is proven tree-identical to what was verified: the merge commit has **two parents** (`5b2c7ee`,
the `main` of the moment, and `d7ab083`, the reviewed head), and `git diff d7ab083 9c01300 -- <this unit's
five files>` is **empty**. Note the trap: the same diff *without* restricting it to this unit's files is not
empty, because `main` had advanced with the access-provisioning records (#375/#376).

CI on the pull request: **`API CI` pass in 3m28s** and the **`PR Label Guard` pass in 5s**, with the single
label `type:bug`. CI on `main` after the merge: **all four workflows green** — `API CI` 4m39s, `Angular CI`
6m25s, `Gateway CI` 40s, `Docker Build Integrity` 25s. The worktree and the branch were removed after the
merge, the anchor is clean on `main @ 9c01300`, and no orphaned worktree metadata remains.

One hole is declared rather than papered over: this records-only pull request carries **no linked issue**, the
same declared gap the antecedent's own delivery record left (`odd/tasks/redis-cache-activation.md`'s #372).
The template requires one, and the linked-issue rule exists to gate *code* behind an approved decision — this
change only writes down what already merged.
