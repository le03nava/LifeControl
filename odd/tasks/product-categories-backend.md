# ODD feature: product-categories-backend

**Status**: implemented — this branch is the slice A backend and its schema: the company-scoped
`product_categories` tree, its depth/cascade/tenancy rules and their tests. The Categories screen
(S3), the product↔category N:M bridge table and the rule that refuses disabling a category holding
active products (S7) are deferred to their own slices, because the bridge cannot be integrity-checked
until `products` carries `company_id`.
**Created**: 2026-10-22 · **Risk**: high — new schema plus a tenancy boundary (item 1 and item 2 of
the Task Size list): a cross-company parent link would be a silent data-isolation defect, so the
guarantee lives in a composite foreign key and is pinned by an integration test.

## Objective

Ship the backend and the database of the product category tree: a `product_categories` table holding
an arbitrary-depth tree with a hard 3-level ceiling, company-scoped, with create / read / update /
soft-delete / enable-disable endpoints under the company path, plus the cascade and cycle rules the
tree needs to stay coherent.

## Problem

`products` has no classification beyond the free-text `product_type` (`Product.java:26`,
`V1__baseline_schema.sql:189`), and no category table exists anywhere: a grep for `categor` across
`life-control-api/src` and `life-control-app-angular/src` returns only exception classes whose names
happen to contain the word ("category" as in *error category*), never an entity. The Angular products
administration screen already renders a `Categories` card with `route: null, disabled: true`
(`life-control-app-angular/src/features/products/pages/products-admin/products-admin.component.ts:38-43`),
so the entry point exists and points at nothing.

## Why this shape

- **Company-scoped, not global.** Decision D1: the categories belong to a company. `products` is
  still global (`V1__baseline_schema.sql:184`), so a category tree scoped to a company and a product
  without one is a boundary that has to be enforced by construction, not by careful coding. The
  migration adds `UNIQUE (company_id, id)` and derives
  `FOREIGN KEY (company_id, parent_category_id) REFERENCES product_categories (company_id, id)`:
  the engine refuses a parent link across companies, so a service bug cannot create one. This is why
  the foreign key is **named** in a schema whose convention is unnamed FKs (`V19__hr_org_structure.sql:9-10`):
  the violation message must name the tenancy rule it broke.
- **`display_order`, not `order`.** `ORDER` is a reserved word in SQL; a column carrying it must be
  quoted in every statement forever. Every other catalog in this schema already uses
  `display_order` (`V19__hr_org_structure.sql:20`, `V5__store_areas.sql`), so the tree matches them
  instead of inventing a second spelling.
- **A unique *expression index* for sibling names.** `UNIQUE (company_id, parent_category_id,
  category_name)` does not stop two roots with the same name, because `NULL <> NULL` in SQL: the
  constraint is silently satisfied when both rows have no parent. The migration therefore indexes
  `(company_id, COALESCE(parent_category_id, <zero uuid>), lower(category_name))`, which makes
  siblings unique — roots included — while still allowing the same name under two different parents.
- **A hard 3-level ceiling validated in the service, not in SQL.** A depth `CHECK` cannot be
  expressed without a trigger, and the schema already resolved exactly this class of problem the same
  way: `CHECK` for the trivial cycle, service validation for the deep one
  (`V19__hr_org_structure.sql:8-12`, `positions.reports_to_position_id`). Same split here: the
  `CHECK (parent_category_id <> id)` blocks a self-parent in the engine, and the depth ceiling and the
  descendant cycle are service rules with their own exceptions and tests.
- **Mirror `Department`, the repository's company-scoped catalog.** Same path shape
  (`/api/companies/{companyId}/...`), same `resolveCompany` order (`verifyCompanyAccess` first, then
  load), same company-scoped repository lookups that make another company's row a 404, same soft
  delete, same `PATCH /{id}/enable`, same absence of `@Version` (a catalog has no optimistic-locking
  precondition — `Department.java:17-19`). No new pattern for a catalog that does not need one.
- **Writes are `lc-admin` only, like the products ABM.** `ProductController` writes are
  `hasRole('lc-admin')` and there is no `lc-product` write role. A new `lc-product-category` role
  would have to enter `ScopeLevel.COMPANY.roleNames()` and then the **frozen** security allowlist
  `GrantablePositionRoles`, widening a boundary whose own test says the widening is a decision
  (`GrantablePositionRolesTest`). That decision is not part of this feature, so it stays unmade.
  Reads require only authentication, plus the same `verifyCompanyAccess` every company-scoped read
  runs.
- **One query per list, ordered depth-first in memory.** Depth is capped at 3, so the whole company
  tree is small by construction: the service loads the company's rows once, groups them by parent and
  walks the roots in `(display_order, name)` order. No recursive CTE, no N+1, and the response is
  already renderable by a tree component.

## Specs

- **S1** (verbatim, L1): "Quiero crear un nuevo arbol de categorias para que se puedan relacionar
  con los productos, quiero poder agregar categorias y subcategorias y subcategorias a las
  ubcategorias".
- **S2** (verbatim, L1): "categories - id, category_name, parent_category_id, order, enabled,
  created_at, updated_at". Two corrections are applied, both recorded in L3: `order` is spelled
  `display_order` (reserved word), and the table is named `product_categories` (the schema prefixes
  product-owned tables: `product_variants`, `product_suppliers`).
- **S3** (verbatim, L1): "Seria crear la pantalla de Categorias dentro del menú de productos para
  agregar, editar, habilitar/inhabilidar categorias y subcategorias". Deferred to the frontend slice:
  this branch ships the endpoints that screen calls and nothing of the screen.
- **S4** (verbatim, L1): "Los servcios del backend y la base de datos". This slice.
- **S5** (decision D1): the tree is **per company**: `company_id NOT NULL`, unique per company, and a
  parent must belong to the same company — enforced by a composite foreign key, not by the service.
- **S6** (decision D2): **at most 3 levels** — root = level 1, so `categoria` → `subcategoria` →
  `sub-subcategoria` is the deepest legal chain. A create or a re-parent that would produce a fourth
  level is refused with 409.
- **S7** (decision D3): the product↔category relation is **N:M** through a bridge table
  `product_category_links(id, product_id, category_id)`. Deferred: it lands with the assignment slice
  together with `products.company_id`, because until then the product side of the bridge cannot be
  integrity-checked.
- **S8** (decision D4): the delivered slice is **backend + database only**.

## Rules this slice implements

| # | Rule | Failure mode it prevents |
| --- | --- | --- |
| R1 | A parent must belong to the same company | A category tree leaking across tenants |
| R2 | Two siblings (roots included) cannot share a name, case-insensitively | Two "Bebidas" cards under the same parent |
| R3 | Neither `parent_category_id = id` nor a descendant as parent | An infinite loop when the tree is rendered |
| R4 | Depth ≤ 3 | A tree the UI and the reports were never designed for |
| R5 | Creating or re-enabling a node requires an enabled ancestor chain | An active branch under a disabled parent |
| R6 | Disabling a node disables its whole subtree | Active products hanging off a dead category |
| R7 | Every read and write resolves the company scope first | A caller reading another company's tree by id |

## Tasks

| ID | S# | Route | Commit |
| --- | --- | --- | --- |
| T1 | S4 | V30 migration: table, tenancy composite FK, sibling unique index, self-parent CHECK | `feat(product): add product category tree schema` |
| T2 | S2, S5 | `ProductCategory` entity + `ProductCategoryRepository` | same commit as T1 |
| T3 | S1, S5, S6, S7 | DTOs + the five domain exceptions | `feat(product): add product category tree service and API` |
| T4 | S1, S6 | `ProductCategoryService`: depth-first list, create, update with re-parent, soft delete, enable | same commit as T3 |
| T5 | S1, S3 | `ProductCategoryController` under `/api/companies/{companyId}/product-categories` | same commit as T3 |
| T6 | R1–R4 | Schema integration test: cross-tenant FK, sibling unique index, self-parent CHECK | commit of T1 |
| T7 | R1–R7 | Service unit test: every rule, both directions | commit of T3 |
| T8 | R7 | Controller test + role-matrix security test | commit of T3 |

## Scope

### In scope
- `V30__product_categories.sql`.
- `com.lifecontrol.api.product.category.*`: entity, repository, DTOs, service, controller, exceptions.
- Tests: service unit, standalone controller, role matrix, Testcontainers schema integration.
- The tenancy guarantee at the database level.

### Out of scope (deliberately not implemented)
- The Angular screen, its routes and the enable of the existing dashboard card (S3).
- `product_category_links` and `products.category_id` (S7).
- The rule refusing to disable a category holding active products: with no link table there is
  nothing to count, so it lands with S7.
- Any new Keycloak role: writes are `lc-admin`, matching the products ABM.
- `@Version` / 412 preconditions: a catalog with no drag-and-drop reorder has none, like `Department`.
- Caching: a company-scoped read must run its scope check on every call, so these reads are not
  cached (`DepartmentService.java:24-38` explains the same decision).

## Constraints (non-negotiable)

- NO Lombok. Manual getters/setters + static `Builder`. DTOs are Java `record`s.
- Constructor injection only. `var` for obvious local types. Entities extend `Auditable`.
- Domain exceptions extend `ResourceNotFoundException` / `DuplicateResourceException` /
  `ConflictException`, so `GlobalExceptionHandler` maps them without a new handler arm.
- Named constraints and indexes (`uq_`, `ck_`, `idx_`, `fk_`) so a violation names its rule.
- Migrations are forward-only and idempotent where they seed; this one seeds nothing.

## Log

- **L1** (user, verbatim): "Quiero crear un nuevo arbol de categorias para que se puedan relacionar
  con los productos, quiero poder agregar categorias y subcategorias y subcategorias a las
  ubcategorias algo así: categories - id, category_name, parent_category_id, order, enabled,
  created_at, updated_at / Seria crear la pantalla de Categorias dentro del menú de productos para
  agregar, editar, habilitar/inhabilidar categorias y subcategorias / Los servcios del backend y la
  base de datos / En base a esta idea que sugieres?"
- **L2** (user decisions, verbatim answers): tenancy — "seria por empresa, en un futuro seria migrar
  productos tambien por empresa"; depth — "Tope 3 validado en el servicio"; relation — "N:M — tabla
  puente"; first slice — "Sólo A: backend + DB".
- **L3** (corrections applied before writing): `order` → `display_order` (SQL reserved word);
  `categories` → `product_categories` (repository table-prefix convention); `created_at`/`updated_at`
  are not written by hand because `Auditable` owns them; the product link is a bridge table owned by
  the assignment slice, not a column on this one.
- **L4** (decision, tenancy guarantee): the parent link is a composite foreign key on
  `(company_id, parent_category_id)`. Service validation alone would be a code-level promise on a
  data-level boundary; the engine now refuses a cross-company parent even if a future service change
  forgets to check.
- **L5** (decision, scope of writes): `lc-admin` only. Introducing `lc-product-category` would widen
  the frozen `GrantablePositionRoles` allowlist, which its own test defines as a deliberate security
  decision. Not made here.
- **L6** (finding, slice B unblocked): nesting the endpoints under
  `/api/companies/{companyId}/product-categories` means the gateway already routes them —
  `Routes.java` maps `/api/companies/**` (`api-gateway/src/main/java/com/lifecontrol/gateway/routes/Routes.java:40`).
  The frontend slice therefore needs **no** gateway route, unlike `store-zones-frontend` and
  `store-areas-frontend`, which both had to add one because their flat prefixes (`/api/store-zones/**`)
  were unrouted. Choosing the company path bought that for free.
- **L7** (decision, review size, not chained): this slice is ~2.1k authored lines of main plus test,
  against the advisory ~400-line budget. It is **not** chained, and the calibration is the repository's
  own standard for exactly this artifact: the `Department` company-scoped catalog — simpler, with no
  tree, no depth rule, no cycle rule and no cascade — is **1752** lines across the same eight files
  (`Department`, repository, service, controller and three test classes). This tree is ~2080, i.e.
  ~330 lines more for three rules the department catalog does not carry. Chaining a catalog the schema
  has shipped whole five times before would impose a shape the codebase does not use; the budget is
  advisory and the cohesive-unit clause applies.
- **L8** (correction, migration number taken): the branch was based on `882307ef`, and `main` had
  meanwhile merged `V30__purchase_order_fractional_quantity.sql` (#596). This tree's migration was
  therefore renumbered `V30` &rarr; `V31`, and every migration-head assertion in the suite moved with
  it — the seven pre-existing `flywayHeadIsV30` tests plus this feature's own, 18 references in all,
  because the head version is one fact and eight tests state it. Two consequences worth recording:
  the version is not a property of this feature, it is a property of the merge order, so a long-lived
  branch has to re-read `origin/main`'s `db/migration` directory before it lands; and the head
  assertion is what makes that collision loud instead of silent — a `V30` that never applied would
  have left a pending migration and failed the same test that named the wrong number.
- **L9** (verification, measured): `./gradlew test` in `life-control-api` — **3978 tests, 0 failures,
  0 errors, 0 skipped** across 991 test classes, run on `f0dcf83c` merged into the branch. The
  feature's own slice is 67 of them: 6 schema integration tests (composite tenancy FK, sibling
  expression index on roots and under different parents, self-parent `CHECK`, `ddl-auto=validate`),
  31 service unit tests covering R1–R7 in both directions, 18 controller tests (status codes per
  endpoint, validation, 404/409 mapping) and 12 role-matrix security tests (`lc-admin` writes, any
  authenticated read).
- **L10** (independent verification, two blocking defects found and fixed): the independent verifier
  re-ran the focused suite in an isolated scratch copy — 67 tests green, matching L9 — and then wrote
  its own probes against the same code, which found two rules that the writer's tests had asserted in
  only one direction:
  - **R4/S6, the ceiling measured the node and not its subtree.** `updateCategory` checked
    `depthOf(parent) + 1` and nothing else, so a level-2 node carrying a level-3 child could move
    under another level-2 node: the node landed legally at level 3 and its child silently became
    level 4. The list then dropped that child as unreachable (`isVisibleInTree` refuses a chain
    deeper than the ceiling), so the defect produced a category the API accepted and the API never
    returned. Fixed by measuring the deepest descendant the move drags along
    (`subtreeHeightBelow`) and re-checking the ceiling against it, only when the parent actually
    changes — a rename cannot deepen anything.
  - **R6, `PUT /{id}` was a second, quieter way to disable a node.** Only `PATCH /{id}/enable` and
    `DELETE` ran the cascade, while the update copied `request.enabled()` straight onto the row: a
    root disabled through `PUT` kept an enabled child, which is exactly the state R6 exists to
    prevent and one the list hides while `GET /{id}` still returns it. Fixed by running the same
    subtree cascade inside the update whenever the resulting flag is `false`, so all three write
    paths answer with one rule.
  Both fixes carry a regression test that asserts the refusal or the cascade **and** that nothing
  else was written, and the same "nothing written" check was added to the three refusals that were
  missing it (a duplicate root, a duplicate sibling, and a refused enable), so every rejecting test
  now asserts the stored state it left behind and not only the exception. Two schema tests joined
  them: the composite tenancy FK admits a same-company parent — the legal half of S5, so the key is
  proven not over-strict — and `product_categories` carries no `version` column.
- **L11** (verification, measured after the fixes): focused slice **72 tests, 0 failures, 0 errors,
  0 skipped** (67 from L9 plus the two service regressions, one controller wire test for the
  forwarded `enabled=false`, and the two schema tests); full `life-control-api` suite **3983 tests,
  0 failures, 0 errors, 0 skipped**. The fix commit is `fix(product): keep the category ceiling and
  the disable cascade honest`.
- **L12** (advisories, dispositions): the verifier also raised three non-blocking observations.
  `description` (`V31`, `ProductCategoryRequest`) is **not** in S2's field list — it is a deliberate
  addition, nullable and never required, kept because a classification tree without a note field
  invites a second migration the day someone needs one; recorded here rather than silently widening
  the spec. The primary key and the `companies` foreign key stay unnamed, matching every other
  migration in this schema (`V19`, `V9`): the record's naming constraint is about the rules this
  feature introduces, and those three are named. The remaining advisory — `depthOf` reporting a
  stored cycle as `MAX_DEPTH + 1` and `setCategoryEnabled(true)` then echoing that depth — is only
  reachable from data that predates the rules, and the fixes above close the write paths that could
  create it.
