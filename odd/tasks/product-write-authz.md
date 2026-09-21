# product-write-authz

**Work unit**: close the missing method-level authorization on the three `/api/products` write endpoints.
**Branch**: `fix/product-write-authz` · **Base**: `main` @ `7958dd0` · **Worktree**: `~/workspace/LifeControl-worktrees/fix-product-write-authz`
**Related**: `odd/tasks/product-variant-admin-ui.md` — the feature whose independent security review surfaced this as finding **F1**. This branch is its own PR, deliberately **not** stacked on that feature.
**Risk**: **high** — auth/permission guard change (`assets/risk-classification-matrix.md`: "Auth, permissions, role, or guard change").

## Problem

`POST /api/products`, `PUT /api/products/{id}` and `DELETE /api/products/{id}` carried no
`@PreAuthorize`. `SecurityConfig` maps `/api/**` to `.authenticated()` and nothing else
(`SecurityConfig.java:58-59`), so the three writes were reachable by **any** authenticated principal:
an `lc-sales` user, or an authenticated user holding no roles at all.

| Endpoint | Guard before the fix | Exposure |
|---|---|---|
| `POST /api/products` | none beyond `.authenticated()` | Any authenticated principal could create a product, choosing the SKU. |
| `PUT /api/products/{id}` | none | Any authenticated principal could rewrite any product's attributes. |
| `DELETE /api/products/{id}` | none | Any authenticated principal could soft-delete any product (`enabled = false`). |

The sibling endpoints in the same controller were already guarded — `getProductsBySupplier`, and the
whole `/{productId}/suppliers` and `/{productId}/variants` families — which is exactly what made the
three bare writes stand out.

**Pre-existing, not introduced by the variant work.** It was found by the independent adversarial
security review run for `product-variant-admin-ui`, which was set the task of *refuting* the claim
that the new Angular route gating was the whole authorization surface of the product ABM. The route
guard was the only barrier in practice, and a route guard is not an authorization control: it
protects the UI, never the API.

## The documentation gap that hid it

`life-control-api/AGENTS.md` → "Endpoint Map" described `/api/products` as `authenticated (read)` and
said nothing about the writes. Read literally, the row covers only reads; read charitably, it implies
the writes were handled somewhere else. Either way the row never named the write role, so no reader
of the endpoint map could see that the writes were unguarded. The row now names it.

## Fix

`@PreAuthorize("hasRole('" + ADMIN + "')")` on the three writes, using the `Roles.*` constants the
class already imports and the same string-concatenation shape as its siblings.

`hasRole`, not `hasAnyRole`, is deliberate. The neighbouring `lc-product-supplier` grant covers
supplier *assignments* and the `lc-sales` grant covers *variants*; neither is a product-definition
write.

## Deliberately unchanged: the reads

`GET /api/products`, `GET /api/products/{id}` and `GET /api/products/by-supplier/{supplierId}` keep
their current reach. `GET /api/products/{id}` is consumed by the variant screens, which `lc-sales`
must reach (`VARIANT_ROLES`). Locking the reads to admin would break that path, and the reads expose
catalog data the sales flow already needs. This is a decision, not an oversight.

## Non-goals

- The variant endpoints under `/api/products/{productId}/variants` — already guarded with
  `lc-admin`/`lc-sales`.
- `/api/product-variants/search` and the store-scope guards — closed by `fix/store-claim-hardening`
  (merged, PR #136).
- Narrowing the reads. Declared above.

## Tasks

- [x] T1 — `@PreAuthorize("hasRole('" + ADMIN + "')")` on the three writes.
- [x] T2 — `ProductControllerSecurityTest`: a `@WebMvcTest` slice that declares its **own**
      `SecurityFilterChain` in a `@TestConfiguration`, so the test pins the endpoint behaviour rather
      than inheriting the app's configuration. 16 tests across 5 `@Nested` groups. `POST`, `PUT` and
      `DELETE` each assert the positive case (`201`/`200`/`204` for `lc-admin`), `403` for `lc-sales`,
      `403` for an authenticated role-less user, and `401` for an unauthenticated request; the
      non-admin cases also assert the service was never reached. Two further groups pin that the
      reads stay reachable by a non-admin.
- [x] T3 — `life-control-api/AGENTS.md` "Endpoint Map" row for `/api/products` names the write role.
- [x] T4 — Mutation proof of the control.

## Evidence

### The control is load-bearing, proven by mutation

A green suite is not evidence that a guard works; it is evidence that it was not obviously removed.
The control was falsified deliberately:

| Run | Bytes | Result |
|---|---|---|
| Baseline | all three guards present | 16 tests, **0 failures** |
| Mutated | `@PreAuthorize` removed from `POST` only | 16 tests, **3 failures** — the entire non-admin half of the `POST` group |
| Restored | `git show HEAD:<path>` back over the mutated file | `git write-tree` == `HEAD^{tree}` == `bb29aa67…` and `git diff HEAD` empty, i.e. the restored bytes are provably the green bytes |

### Trap: a focused run on a `@Nested`-only class looks vacuous

`./gradlew test --tests '…ProductControllerSecurityTest'` writes one XML report **per nested class**
plus one for the outer class. The outer-class report says `tests: 0`, because every test lives in a
`@Nested` class. Reading only
`TEST-…ProductControllerSecurityTest.xml` therefore reports a confident, green, empty run. The count
is in the sibling reports (`…$CreateProductSecurity.xml`, etc.). Sum the nested reports before
believing a focused run.

## Constraints

- Role literals must come from `Roles` (`Roles.java`: "*every `@PreAuthorize` in the codebase must
  reference these constants instead of hardcoding role literals*").
- `@PreAuthorize` needs method security enabled; the application has it, and the test slice enables it
  explicitly rather than assuming it.
