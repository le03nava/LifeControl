# ODD feature: product-category-attributes

**Repository**: LifeControl — `life-control-api/` (two migrations, a new `product/attribute` module, one read
endpoint, write validation in `ProductService` and `ProductVariantService`) and
`life-control-app-angular/` (the product form's attribute field-set and the variant dialog). No image pin, no
delivery script, no tenancy change.
**Status**: implemented — the backend of the unit is written and its regression baseline is green: V37 and V38,
the `product/attribute` module, the read endpoint and the write validation. The attribute test suite and both
screens remain in `## Tasks`.
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
  `product_variants.attributes`. Route: inline. Commit: `_pending_`.
- **T2** (S1, S3, S7) — `V38__product_attribute_starter_catalog.sql`: the four profiles, their definitions and
  their allowed values, mapped to the ten enabled roots of `V35`. Route: inline. Commit: `_pending_`.
- **T3** (S2-2, S4) — the `product/attribute` module: entities, repositories, and the resolution service
  (`category -> root -> profile`, a company row beating a global one). Route: inline. Commit: `_pending_`.
- **T4** (S4, S7) — `GET /api/companies/{companyId}/product-categories/{id}/attribute-profile`, partitioned by
  level, `204` when the branch declares none. Route: inline. Commit: `_pending_`.
- **T5** (S8) — write validation in `ProductService` (product level) and `ProductVariantService` (variant level),
  and the merge-to-authoritative change in `ProductService:82`. Route: inline. Commit: `_pending_`.
- **T6** (S4, S5) — the product form: the JSON textarea becomes a rendered field-set driven by T4, with the raw
  JSON kept read-only. Route: inline. Commit: `_pending_`.
- **T7** (S6, S7) — the variant dialog renders and submits the `VARIANT`-level attributes. Route: inline.
  Commit: `_pending_`.
- **T8** (S1–S9) — the API and Angular suites: schema, seed parity, resolution, each `kind`'s validation, the
  form's render-per-category and the variant's colour. Route: inline. Commit: `_pending_`.
- **T9** (S1–S9) — the root `AGENTS.md` rows and this record's evidence log. Route: inline. Commit:
  `_pending_`.

## Slices

This unit is delivered as **chained pull requests**, and the measurement is why: the backend alone is
1461 authored lines before a single screen, far past the ~400-line review budget. Each slice below is a
reviewable unit that compiles on its own; a slice that cannot be split without committing a tree that
does not build is reported with its real size instead of being cut at an arbitrary line. This record
ships with slice 5, the first slice that changes an existing contract, because that is the one a reviewer
has to read with the record open.

| Slice | Content | Authored lines | Commit |
| --- | --- | --- | --- |
| 1 | V37 + V38: the four tables, the variant column and the seeded profiles | 430 | `1be71da4` |
| 2 | module: the four entities and their repositories | 542 | `84e4c8cb` |
| 3 | module: the three DTOs, the validation exception and the catalog service | 432 | `d16a5281` |
| 4 | module: the read endpoint | 57 | `a40b6e77` |
| 5 | the write path: authoritative set + effective-set validation, with its tests | ~230 | `_pending_` |
| 6 | the attribute suites: schema and seed, catalog service, HTTP contract | ~1490 | `_pending_` |
| 7 | the Angular field-set and the variant dialog, with their specs | `_pending_` | `_pending_` |
| 8 | the root `AGENTS.md` rows and this record's final header | `_pending_` | `_pending_` |

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
