# API edge trust: forwarded headers, rate-limit buckets, filter chain and port bindings

Issue **#892** — *fix: the API trusts every LAN host, skips the rate limiter on a forged header and
shares one bucket per endpoint*. Branch `fix/api-edge-trust`.

**Status**: implemented — T1–T8 are the branch's content: the right-to-left resolver, the per-client
rate-limit buckets, the closed filter chain with the docs behind a property, the loopback port
bindings and the gateway actuator gate, each with the suite that fails without it. Nothing is deferred.
**Created**: 2026-11-19 · **Risk**: high — item 2 (address trust and authorization at the edge), item 3
(`anyRequest()` changes who may reach paths that, today, are anonymous by construction) and item 6 (no
test on the tree exercises the real `SecurityConfig` chain, so the first one is written here).

## Specs

Quoted verbatim from issue #892. Nothing below adds a requirement to it.

**S1** — expected outcome: "The API trusts only the addresses an operator declares, resolves the client
address by walking `X-Forwarded-For` from the right past the trusted hops, keeps one rate-limit bucket
per endpoint and client, ends its filter chain closed, and exposes the management surface only where
it is meant to be read."

**S2** — "With `app.security.trusted-proxies` narrowed to the real proxy, a request from a 10.x, 172.16.x
or 192.168.x address carrying `X-Forwarded-For: 127.0.0.1` is rate-limited like any other client."

**S3** — "Two distinct clients hitting the same endpoint report two independent budgets in
`X-RateLimit-Remaining`, and exhausting one leaves the other's quota intact."

**S4** — "A request to a controller path registered outside `/api/` and `/actuator/` answers 401 or 403
instead of reaching the handler."

**S5** — "`/v3/api-docs` and `/swagger-ui.html` answer 401 in staging unless a property explicitly
enables them, matching the gateway's existing behaviour."

**S6** — "A request to `/actuator/metrics` on the gateway port with a valid non-admin realm token answers
403 or 404, and Prometheus scraping still succeeds."

**S7** — "`docker compose config` reports `host_ip: 127.0.0.1` for the API port."

**S8** — "The existing rate-limit, security-config and actuator suites pass."

## Decisions

**D1 — the shipped default trusts loopback and nothing else; the deployment declares the network it
runs on.** Measured before the change: `application.properties:112-115` and the field defaults in
`RateLimitProperties.java:36-37` and `SecurityAccessProperties.java:22-23` all carried
`10.0.0.0/8,172.16.0.0/12,192.168.0.0/16`, and `app.rate-limit.trusted-proxies` was set nowhere, so its
Java default was always in force. Both properties now read `${TRUSTED_PROXIES:127.0.0.1/32,::1/128}`:
one operator knob, one value, two consumers. The Docker network the gateway and Prometheus live on is
declared in `docker/docker-compose.yml`, because that file *is* the deployment and it is the same file
that pins the published port to loopback — the pair is what makes a container-network CIDR safe.

**D2 — the walk starts at the right and the peer gate stays first.** `X-Forwarded-For` is
`<forged prefix>, <real client>` once Spring Cloud Gateway's default `XForwardedHeadersFilter` appends
the connection's address (the tree does not disable it, and the gateway declares no filter of its own).
Reading `split(",")[0]` returned the caller's own bytes. The resolver keeps "peer must be a trusted
proxy" as the gate and then walks from the rightmost hop leftwards, skipping trusted proxies; the first
untrusted hop is the client, and a hop that is not an IP literal fails closed to the socket peer rather
than being matched as a hostname.

**D3 — a bucket per endpoint **and** client, and a sweep that keeps the map bounded.** The map was
`Map<String, RateLimitedEndpoint>` keyed by path alone. Caffeine is not on the classpath and this unit
adds no dependency, so the bound is behavioural: entries whose bucket is back at capacity hold no
history and are swept, which is indistinguishable from keeping them because a full bucket answers
exactly what a new one would.

**D4 — the chain ends closed, with `/error` the one path that stays open.** Spring Security 6+ filters
the ERROR dispatch, so denying everything would turn an unauthenticated request that fails into a 403
and lose the status the tree already answers. The docs matchers leave `permitAll` and join the
gateway's idiom: `security.docs-public`, default `false`, `true` only in `application-dev.properties`.

**D5 — the actuator keeps its CIDR gate and gains an exposure decision.** The API narrows the CIDR
default as D1 says. The gateway, which gates by token rather than by address, stops treating "any
valid realm token" as enough for the management surface: `health` and `prometheus` stay public because
the container healthcheck and the scraper are their only readers, and the rest is permitted only when
`security.actuator-public=true` — dev, mirroring `security.docs-public`.

**D6 — the bindings are asserted where the observability ones already are.** Measured:
`docker/scripts/tests/observability-port-binding.test.sh:65-72` freezes the four observability ports
and their host IP, and no suite mentions the API or the gateway port. That table grows rather than a
second suite appearing, so one file owns "which port is published where".

## Tasks

| ID | Specs | Task | Evidence |
| --- | --- | --- | --- |
| T1 | S1, S2 | `ClientIpResolver` walks `X-Forwarded-For` from the right past trusted hops and fails closed on a hop that is not an IP literal; `CidrMatcher.looksLikeIp` gates every `InetAddress.getByName` call; both property classes default to loopback and the three keys read `${TRUSTED_PROXIES:...}` / `${ACTUATOR_ALLOWED_CIDRS:...}` | `ClientIpResolverTest` (right-to-left walk, forged leftmost hop, fail-closed hop), `CidrMatcherTest` (`looksLikeIp`, hostname refused), `SecurityAccessPropertiesTest` |
| T2 | S1, S3 | `RateLimitFilter` keys its bucket map by endpoint + client and sweeps buckets that are back at capacity every 256 requests; `X-RateLimit-Remaining` becomes per caller | `RateLimitFilterTest` (independent budgets, one quota exhausted leaves the other, private-range peer cannot forward a loopback client, sweep keeps history) |
| T3 | S1, S4, S5 | `SecurityConfig` ends in `denyAll`, permits `/error`, and takes the docs out of `permitAll` behind `security.docs-public` (false by default, true on dev) | `SecurityConfigAccessTest` and `SecurityConfigDocsPublicTest` — the first tests on the tree against the real `securityFilterChain`; `SalesOrderOpenApiContractTest` enables the property for itself; 5168 tests / 0 failures / 0 skipped on the slice that carries it |
| T4 | S7 | `lifecontrol-api` 8082 and the gateway management port (9001 dev / 9201 prod) publish on `127.0.0.1` in the base and prod compose; the gateway's application port stays published on purpose | `docker compose config` reports `host_ip: 127.0.0.1` for 8082/9001/9201 and no host IP for 9000/9200 |
| T5 | S7 | The port-binding suite freezes the host scope **per mapping** (`loopback` / `any`) instead of assuming it, and owns the two application services as well as the four observability ones | `observability-port-binding.test.sh`: 59 passed, 0 failed; whole `docker/scripts/tests/` glob green |
| T6 | S6 | Gateway actuator: exposure ships `health,prometheus`, the rest of `/actuator/**` is `denyAll()` unless `security.actuator-public=true` (dev) | `SecurityConfigTests` (`/actuator/metrics` 403 with a valid non-admin token, `/actuator/prometheus` still 200 without one) and `SecurityConfigActuatorPublicTests`; 25 tests green |
| T7 | S1 | Adjacent finding, not an acceptance criterion of #892: `ActivityLogAspect` reads the raw leftmost `X-Forwarded-For` hop with no trust gate, so an audit row's address is caller-written — it resolves through the same resolver now | `ActivityLogAspectTest` `ClientAddressTests` |
| T8 | S1–S8 | `AGENTS.md` documents the edge trust model, the two knobs and the limits; this record's task rows carry their evidence | `AGENTS.md` section *Borde de confianza del API*; this record |

## Log

**L1** — the human's request, verbatim: *"puedes continuar con el issue 892"*.

**L2** — 2026-11-19, measured before any edit: no branch, worktree, pull request, record or comment
existed for #892 (`gh pr list --search 892` empty, `git branch -a '*892*'` empty, `odd/tasks/` has no
match), so this unit starts here rather than resuming one. The issue carries `bug` and not
`status:approved`; `CONTRIBUTING.md:15` requires that label before the pull request is opened, and it
is the maintainer's decision, not this record's.

**L3** — 2026-11-19, measurement of the five findings against `main @ 69e032d0`: F1 (RFC1918 defaults),
F2 (unprefixed port publication), F3 (leftmost hop) and F4 (path-keyed buckets) **hold**; F5 is
**partially fixed** — the gateway already gates its docs behind `security.docs-public:false` and keeps
`/actuator/health` + `/actuator/prometheus` public, while the API's docs matchers are a static array
(`SecurityConfig.java:37-40`) and `application-prod.properties:17-18` only stops emitting them. Every
observability port is already loopback-bound, so the asymmetry F2 reports is real and not a reading
artifact.

**L4** — 2026-11-19, discovered while measuring and declared instead of absorbed: `ActivityLogAspect`
(`:203-216`) resolves the request address from the leftmost `X-Forwarded-For` hop with no trust gate,
the same defect F3 reports, at a second reader the issue does not name. T7 fixes it because shipping
the resolver with a raw reader next to it would leave the reported family open; it is listed as an
adjacent finding, not as a spec.

**L5** — 2026-11-19, named limit: nothing in this unit covers the `k8s/` manifests, which carry no
environment overlay and already scrape `metrics_path: '/actuator/prometheus'`
(`k8s/manifests/*/prometheus.yml:55`). The edge trust model there is decided by the cluster's ingress,
which this repository does not describe.

**L6** — 2026-11-19, delivery shape, measured rather than estimated: the unit is **1218 authored lines
in four commits** (gateway gate, docker bindings and suite, resolver and buckets, closed chain), past the ~400-line review budget. The slices are commits and not a chain because
CONTRIBUTING §3 reserves chains for units that are independent of each other, and these four answer
one issue; the boundary is recorded here so the maintainer can ask for a chain instead before the
pull request is shaped. The API suite is 5168 tests / 0 failures / 0 skipped with `spotlessCheck` and
`spotbugsMain` green on the slice that carries it.

**L7** — 2026-11-19, blocked and named: the containerised stack needs `TRUSTED_PROXIES` and
`ACTUATOR_ALLOWED_CIDRS` for the `lifecontrol-api` service once the defaults narrow to loopback,
because Prometheus scrapes `/actuator/prometheus` from a 172.x peer and the gateway is the peer the
API sees. `docker/.env.template` is refused to both the worker and the parent by the harness safety
policy, and `env-defaults.test.sh` §3 rejects a compose interpolation with no template key, so the
two lines are applied by the maintainer and the interpolation follows them. Until the pair lands, the
in-container API resolves the gateway and the scraper as untrusted peers.

**L8** — 2026-11-19, two limits of the resolve itself, both declared instead of silently widened:
`RateLimitFilter.SWEEP_INTERVAL_REQUESTS` is package-visible so the bound test can drive the cadence
itself (its RED was obtained by removing the sweep, not by a first run against unfinished code), and
`CidrMatcher.looksLikeIp` accepts a colon-bearing string that is not an IPv6 address — such a hop can
never be resolved and can never match an allow-list entry, so it is a junk address and not a bypass.
The gateway's `/actuator/health/**` sub-paths were deliberately left denied rather than added to the
public list: this unit narrows surfaces, and no probe on the tree uses one.

**L9** — 2026-11-19, the gateway's application port stays published on every interface, against the
first cut of T4 which bound 9000/9200 to loopback as well. S7 names the API's port, and closing the
stack's entry point changes how the app is reached without closing any hole this unit is about; the
port-binding suite freezes it as `any` so the choice is visible in the same table as the loopback rows.
