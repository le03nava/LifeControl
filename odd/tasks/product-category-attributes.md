# ODD feature: product-category-attributes

**Repository**: LifeControl — `life-control-api/` (two migrations, a new `product/attribute` module, one read
endpoint, write validation in `ProductService` and `ProductVariantService`) and
`life-control-app-angular/` (the product form's attribute field-set and the variant dialog). No image pin, no
delivery script, no tenancy change.
**Status**: implemented — the catalog, its seed, the read endpoint, the write validation, both screens and the two
suites an independent verification wrote are the branch's content. One finding from that verification is
**deferred**, with the reason recorded in L14: variants are not re-validated when their product is reclassified,
which is a product decision rather than a detail. The dead-end save it found is fixed and pinned, not deferred.
**Created**: 2026-10-27 · **Risk**: **item 1 (data)** — `products.attributes` is `jsonb` that a form writes and
`product_variants.attributes` is a new column, so a defect here stores values nobody can interpret; and the write
rule changes from a shallow merge to an authoritative set, which is how a product loses an attribute silently.
Item 3 does not apply to the new endpoint (additive) but does apply to the write semantics (an existing contract
this unit changes on purpose). No security surface, no concurrency, no delivery.

## Specs

**S1** — *the user, 2026-10-27, verbatim*: «Quiero que al seleccionar una categoria del producto se agregen
inputs de atributos dependiendo de la categoria y se vayan en el json de los atributos, por ejemplo estos mas los
que consideres agregar, quitar, homologar» — followed by four blocks, reproduced verbatim in §Homologation.

**S2** — *the user, 2026-10-27, verbatim*: «1 a nivel variante, 2 global + override despues, 3 si se pueden
borrar los atributos actualmente no se usan» — the three decisions this unit is built on:

1. `Color` and `Talla` are **variant-level**, not product-level. A shoe in two colours is two barcodes, two
   stock rows and two prices.
2. The attribute catalog is **global, seeded**, with a per-company override seam that ships unused.
3. The attributes stored today **may be discarded**: they are unused, so the write rule may be replaced instead
   of migrated.

**S3** — *the user's example values, verbatim, are the vocabulary of the seed*: «Negro Mate, Charol Rojo, Café
Oxford», «Piel Vacuno, Sintético, Gamuza, Lona», «Agujeta, Velcro, Elástico, Slip-on», «1/2 pulgada, 3 metros,
10 milímetros», «PVC, Cobre, Acero Galvanizado, Alnico», «Calibre 12, Calibre 14», «NPT, Milimétrica, Estándar»,
«110V, 220V», «19 Kg», «55"», «1.5 Litros», «1200W, 3 HP», «Wi-Fi, Bluetooth, Smart, Ninguna», «500g, 1 Litro»,
«Congelado, Refrigerado, Temperatura ambiente».

**S4** — the form renders the attribute inputs **of the selected category** and nothing else: pick a category,
the field-set appears; change the category, the field-set is replaced.

**S5** — the values travel in the product's `attributes` JSON, keyed by **attribute code**, never by the label the
operator reads.

**S6** — `Color` and `Talla` are written on the **variant**, and `Talla` is *not* duplicated: the variant's
`variant_name` already is the size, so the variant attribute set carries `color` and `acabado` only.

**S7** — an attribute definition declares the **level** it applies to (`PRODUCT` or `VARIANT`), so one profile
serves both screens.

**S8** — a value that does not belong to its definition is refused by the **API**, not only by the form: an
unknown code, a value outside the allowed set, a measurement without its unit, or a missing required attribute is
a `400`.

**S9** — `Fecha de caducidad` is **not** an attribute: it belongs to a lot, not to a catalog row. Dropped from
the user's list on purpose, and the reason is recorded in L5.

### Homologation (S1's «agregar, quitar, homologar», decided)

| The user's attribute | Decision |
| --- | --- |
| `Color` (free text) | `color` **enum** at variant level, plus `acabado` enum for the "Mate / Charol" half the examples mix into it |
| `Talla` | **not** an attribute: it is `product_variants.variant_name`, already in the contract (S6) |
| `Material Externo`, `Material`, `Tipo de Ajuste`, `Conectividad`, `Temperatura de Conservación` | enums; `Conectividad` is multi-valued |
| `Medida / Dimensión`, `Capacidad / Tamaño`, `Contenido Neto`, `Calibre / Grosor`, `Potencia` | **one kind**: `MEASUREMENT` = `{value, unit}`. Six of the user's attributes are the same shape |
| `Voltaje / Consumo` | enum of `110v`, `220v`, `110_220v`, `ninguno` |
| `Tipo de Entrada / Rosca` (NPT, milimétrica) | split in two: `sistema_rosca` (the standard) and `medida_nominal` (the measurement). One field cannot be both |
| `Calibre / Grosor` | split by profile: `calibre_awg` in ferretería (a wire gauge) and `espesor` as a measurement (a plate). Different magnitudes |
| `Fecha de Caducidad` | dropped (S9) |
| `Alnico` | dropped: it is a magnet material, not a ferretería category |
| *(added)* | `marca`, `modelo` (the real differentiators in ferretería), `genero` and `altura_tacon_cm` for calzado, `alergenos` (multi) for abarrotes, `piezas_por_empaque`, `garantia_meses` |

## Tasks

- **T1** (S2-2, S5) — `V37__product_attribute_catalog.sql`: the four tables (`product_attribute_profiles`,
  `product_attribute_definitions`, `product_attribute_values`, `product_attribute_profile_roots`) and
  `product_variants.attributes`. Route: inline. Commit: `1be71da4`.
- **T2** (S1, S3, S7) — `V38__product_attribute_starter_catalog.sql`: the four profiles, their definitions and
  their allowed values, mapped to the eleven enabled roots of `V35` the four blocks cover. Route: inline.
  Commit: `1be71da4`.
- **T3** (S2-2, S4) — the `product/attribute` module: entities, repositories, and the resolution service
  (`category -> root -> profile`, a company row beating a global one). Route: inline. Commits: `84e4c8cb`,
  `d16a5281`.
- **T4** (S4, S7) — `GET /api/companies/{companyId}/product-categories/{id}/attribute-profile`, partitioned by
  level, `204` when the branch declares none. Route: inline. Commit: `a40b6e77`.
- **T5** (S8) — write validation in `ProductService` (product level) and `ProductVariantService` (variant level),
  and the merge-to-authoritative change in `ProductService:82`. Route: inline. Commit: `f6303cf4`.
- **T6** (S4, S5) — the product form: the JSON textarea is **replaced** by a rendered field-set driven by T4.
  There is no read-only JSON preview: the carrier control still holds the JSON, so a second rendering of the
  same data would be a debug view with no reader. Route: inline. Commit: `28a7328a`.
- **T7** (S6, S7) — the variant dialog renders and submits the `VARIANT`-level attributes. Route: inline.
  Commit: `c668f37e`.
- **T8** (S1–S9) — the API and Angular suites: schema, seed parity, resolution, each `kind`'s validation, the
  form's render-per-category and the variant's colour. Route: inline. Commits: `197883c1`, `5f3d44b7`,
  `6fd6c24a`, `e5f2b884`. **T8b** — the two probe suites an independent verification wrote for the write path and
  for the migrations on a populated database, kept because each case needs the real profile and a real refusal.
  Commits: `40dfc6db`, `cb7e4de0`.
- **T9** (S1–S9) — the root `AGENTS.md` rows and this record's evidence log. Route: inline. Commit: the
  closing slice.

## Slices

This unit is delivered as **chained pull requests**, and the measurement is why: the backend alone is
1461 authored lines before a single screen, far past the ~400-line review budget. Each slice below is a
reviewable unit that compiles on its own; a slice that cannot be split without committing a tree that
does not build is reported with its real size instead of being cut at an arbitrary line. This record
ships with slice 5, the first slice that changes an existing contract, because that is the one a reviewer
has to read with the record open.

| Slice | PR | Content | Authored lines | Tip |
| --- | --- | --- | --- | --- |
| 1 | #648 | V37 + V38: the four tables, the variant column and the seeded profiles | 430 | `1be71da4` |
| 2 | #649 | module: the four entities and their repositories | 542 | `84e4c8cb` |
| 3 | #650 | module: the three DTOs, the validation exception and the catalog service | 432 | `d16a5281` |
| 4 | #651 | module: the read endpoint | 57 | `a40b6e77` |
| 5 | #652 | the write path: authoritative set and effective-set validation, with its tests | 246 | `f6303cf4` |
| 6 | #653 | the schema and seed suite, including the V38 replay | 613 | `197883c1` |
| 7 | #654 | the catalog service suite and the HTTP contract suite | 878 | `6fd6c24a` |
| 8 | #655 | the frontend types, the form model and the profile read | 446 | `74a94629` |
| 9 | #656 | the field-set in the product form, its spec and the breakpoint tokens | 704 | `e44fa740` |
| 10 | #657 | the variant dialog and the edit page | 428 | `90700b98` |
| 11 | #658 | the verifier's two probe suites and the dead-end fix | 580 | `09242245` |
| 12 | #659 | the root `AGENTS.md` rows and this record | 96 | this PR (its tip is derived, never stored) |

The breakpoint fix ships in slice 9, not later, and that is deliberate: the guard it satisfies scans the
whole tree, so a slice that introduces the literal and leaves the fix two slices away is a slice that is
red on its own. Measured on the first version of the chain: `#656` and `#657` failed `Unit tests (2)`
until the fix moved to where the code lives.

Slices 2 and 3 exceed 400 on their own and both compiles: the model cannot be committed without the
repositories that own it, and the service cannot be committed without the DTOs it returns. Cutting them
further would have meant committing a tree that does not build, which is worse for a reviewer than 130
lines over budget.

## Log

- **L1** — *the user, 2026-10-27, verbatim*: «Quiero que al seleccionar una categoria del producto se agregen
  inputs de atributos dependiendo de la categoria y se vayan en el json de los atributos, por ejemplo estos mas
  los que consideres agregar, quitar, homologar», followed by the four blocks now reproduced in S3.
- **L2** — *the user, 2026-10-27, verbatim*: «1 a nivel variante, 2 global + override despues, 3 si se pueden
  borrar los atributos actualmente no se usan» — answer to the three decisions the analysis asked for, and the
  authorization to implement.
- **L3** — evidence, 2026-10-27, measured on the dev stack (`lifecontrol-dev-lifecontrol-postgres`):

  ```
  products: 3   |   attributes no vacío: 0   |   con categoría: 0
  product_categories: 119   |   product_variants: 4
  ```

  The catalog is greenfield, which is what makes L2-3 safe. It also bounds the feature: nothing that reads
  `attributes` exists to break — `idx_products_attributes` (V1:199) is a GIN index no query uses.
- **L4** — measurement, 2026-10-27, the defect that forces T5. `ProductService:82-94` merges shallowly:

  ```java
  Map<String, Object> merged = existingAttrs != null ? new HashMap<>(existingAttrs) : new HashMap<>();
  merged.putAll(request.attributes());
  ```

  Under a per-category field-set that means a key can never be removed, and the attributes of the previous
  category survive invisibly. The shallow merge is therefore replaced by an authoritative set in T5, not
  preserved: it is the one behavior this unit changes on purpose (Risk, item 3).
- **L5** — decision, 2026-10-27. `Fecha de caducidad` is dropped (S9) because a catalog row cannot hold the
  expiry of the lot that arrived today; two batches of the same product would have two dates and one row. It
  belongs to goods receipt, not here. `Alnico` is dropped for the same class of reason: it is a magnet material
  listed as a ferretería category.
- **L6** — decision, 2026-10-27. Where the profile attaches. A company's categories are rows
  (`product_categories`, `company_id NOT NULL`) while the catalog is global, so a global definition cannot
  reference a company's category id. The attachment is therefore **by root name** in
  `product_attribute_profile_roots(profile_id, root_name, company_id)` — `company_id` null is the seeded global
  row, a company row overrides it. No column on `product_categories` and no change to
  `StarterProductCategoryApplier`, so a company created tomorrow is covered by the same rows. The cost, named:
  renaming a root stops resolving, and the endpoint answers `204` instead of inventing a profile.
- **L7** — decision, 2026-10-27. `Talla` is not added as an attribute (S6). `product_variants.variant_name` is
  already the size and is `NOT NULL` with `(product_id, variant_name)` unique (D3 of the variant unit), so a
  second `talla` key would be a second source of truth for the same fact.
- **L8** — evidence, 2026-10-27. The migrations are verified by being applied, not by being read: with V37 and V38
  on the tree, `./gradlew test --tests 'com.lifecontrol.api.product.*'` boots PostgreSQL 16 through
  Testcontainers, runs Flyway from V1, and passes with `ddl-auto=validate`, i.e. every entity of the new module
  matches the schema the migration created. **346 tests in 77 suites, 0 failed** (measured on this branch).
  That first run was 20 failures, all `NullPointerException` in `ProductServiceTest` and
  `ProductVariantServiceTest`: both are `@InjectMocks` suites and the new
  `ProductAttributeCatalogService` collaborator had no `@Mock`, so `@InjectMocks` left it null. The fix is the
  missing mock in the two suites, plus rewriting `shouldMergeAttributes_PartialUpdate` into
  `shouldReplaceAttributes_FullUpdate`, which now pins L4's rule instead of the merge it replaced. The change of
  that test is the item-3 half of the Risk line, recorded here rather than left for a reviewer to notice.
- **L9** — evidence, 2026-10-27. The full backend suite, not only the product package, after the write-path
  contract change: `./gradlew test` → **991 suites, 4032 tests, 0 failed, 0 skipped**. This is the measurement the
  item-3 half of the Risk line needs, because `ProductService` and `ProductVariantService` are reached from
  inventory, sales and goods receipt — the merge-to-replace change was checked against the whole backend instead
  of argued from the package it lives in.
- **L10** — evidence and one correction, 2026-10-27. The Angular half was delivered by a delegated writer and
  **re-verified here**, not accepted on its report: `npm run lint` clean, `npx prettier --check` clean,
  `npm run build` complete, and `ng test --include 'src/features/products/**'` → **30 files, 600 tests, 0 failed**.
  The writer's own risk list named a data-clearing path and it was real: with the profile at `null` (a branch that
  declares no attributes) the form wrote `{}` into the carrier, so saving a product with stored attributes cleared
  them, invisibly — nothing is rendered, so the operator could not see what the save deleted. That is exactly what
  the unit's Risk line forbids, so the write was removed: a branch with no profile is not the form's to author, the
  carrier is left as it arrived, and an incompatible stored set is refused by the API with a message naming the
  code. The regression is pinned by an assertion in `products-form.spec.ts` (the same test that checks the 204
  note now also checks the carrier is byte-identical). The writer also reported, honestly, that it did not capture
  RED test-first and substituted a mutation probe; that is recorded rather than smoothed over.
- **L11** — evidence, 2026-10-27. The Angular half's full suite was run, not only the products specs, and it
  found a defect this unit introduced: `product-attribute-fields.scss` declared its two column breakpoints as
  literals (`576px`, `1024px`) and the project's `breakpoints.spec.ts` source guard rejected the first, because a
  literal re-declares the mobile boundary that `$bp-mobile-max` owns. Measured: **3954 of 3955 green, 1 failed**,
  and the failing test was in `src/shared/constants/` — nowhere near the package being changed, which is exactly
  why the whole suite runs. The fix uses `$bp-sm`/`$bp-md` plus an explicit `@use` of the variables partial
  (component styles do not see it globally). Final: **192 files, 3955 tests, 0 failed**, and `npm run build`
  green.
- **L12** — evidence, 2026-10-27. Final measurements for this record, both sides, on this branch: backend
  `./gradlew test` → 991 suites / 4032 tests / 0 failed; frontend `ng test` → 192 files / 3955 tests / 0 failed;
  `spotlessCheck` clean; `npm run lint`, `prettier --check` and `npm run build` clean.
- **L13** — finding and fix, 2026-10-27. An independent read-only verification (`gentle-ai-verify`, five questions
  over `f6303cf4`) returned **PASS** on: no write path reaches the repositories without validating, no value is
  stored uninterpretable, the level check holds in both directions, the effective-set rule is atomic for a
  reclassification (the row is untouched when the write is refused), and the migrations are safe on a database
  that already holds products, variants and categories (measured on a real V36 database: rows survive, the new
  variant column arrives `NULL`, the seed replay is a no-op). It returned one **blocker**, F1, and it was real:
  the API validates the set that will be STORED while both screens omitted the field when the branch does not
  declare it, so a product or variant holding a code its category no longer declares could never be saved
  again — not even to fix its name — with no field on screen able to remove the key. Fixed in `09242245`: the
  save projects the set the branch declares and the screen states which stored codes will be discarded, so
  nothing is erased unannounced and nothing is unsavable. Three specs pin it (`603` products-area tests).
- **L14** — deferred, 2026-10-27, and the reason it is not fixed here. **F2**: `updateProduct` never re-validates
  the variants of the product it reclassifies, so a variant keeps a code its new branch does not declare; the
  value is still returned by the API until someone edits the variant, and then the dialog now names it and
  projects it away. Fixing it means deciding whether a reclassification may fail because of a variant the
  operator is not looking at — a product decision with its own blast radius, so it goes to its own issue rather
  than into this unit. **F3**, accepted rather than deferred: an API client that sends `attributes: null` on a
  product whose stored set the branch does not accept is still refused; that is the strict rule working, and the
  screens now resolve it with the projection above instead of the API relaxing the check.
- **L15** — delivery evidence, 2026-10-27. Issue **#647** (`feat: render per-category product attributes from a
  seeded catalog`), created from `feature.yml` and approved with `status:approved` on the maintainer's explicit
  instruction. The unit ships as a chain of twelve stacked slices, **#648** to **#659**, one work unit per pull
  request, each based on its parent and each with exactly one `type:*` label; only the last carries the closing
  keyword. The first version of the chain put the breakpoint fix in slice 11 while slice 9 introduced the literal
  it fixes, and CI proved the defect: `#656` and `#657` failed `Unit tests (2)` while the later slice passed
  because it contained the fix. The four affected refs were rebuilt and republished by the maintainer, after the
  agent runtime's safety policy refused the force-push it had already been authorized to run; slices 9 to 12 were
  rebased onto the new tips and the record's slice table now cites the published hashes only, because the rebase
  orphaned six of the old ones. Nothing is merged: the slices land in order, each retargeted to `main` as its
  parent merges, and the merge is the maintainer's decision.
