# ODD feature: product-category-sat-code

**Status**: implemented — the `sat_code` column on `product_categories` (V34), the starter catalog
seeded for existing companies (V35) and applied to every company created afterwards, and the field on
the Categories screen. Nothing is deferred.
**Created**: 2026-10-25 · **Risk**: high — item 1 of the Task Size list. V35 is a forward-only migration
that writes 57 rows into **every live tenant's** category tree at deploy time, so its effect is user
visible the moment it runs and cannot be undone row by row: the catalog is unrecoverable once applied,
and the seeded roots become the parents of whatever a company adds later. Name and SAT key claims are pinned
to the request by test, but a `description` that is wrong in both copies would pass every test — the one
remaining gap, reported as a follow-up instead of papered over.

## Objective

Give every product category an optional SAT product-service key (`sat_code`), wire it through the
API and the Categories screen, and ship a starter catalog of the twelve branches the user listed plus
a set of disabled proposals, so a company starts with a usable classification tree instead of an
empty one.

## Problem

`product_categories` (V31) has no fiscal classification: a category cannot carry the SAT
`ClaveProdServ` a CFDI needs, so the code would have to be typed per product (or lost). And the tree
starts empty: after V31 every company has to build its whole classification by hand, which is why the
user asked for a starting catalog.

## Why this shape

- **`sat_code VARCHAR(8)` plus a `CHECK`, not `CHAR(8)`, and no unique constraint.** `ClaveProdServ`
  is eight numeric digits; `VARCHAR` avoids the space padding `CHAR` adds on read, and the `CHECK`
  (`^[0-9]{8}$`) is what makes "optional but validated" real for a writer that bypasses the API. It is
  deliberately **not** unique: the SAT catalog legitimately repeats a code at different levels —
  `53110000` is the whole footwear segment and every footwear branch carries it — so a unique
  constraint would reject the very catalog the user asked for.
- **Nullable, unlike `measure_units.sat_code`.** A measure unit *is* a SAT catalog entry, so its code
  is its identity and it is `NOT NULL UNIQUE`. A category is a company's own classification that
  *may* name a SAT key, and a category created by hand before the operator looks the key up must still
  be savable — the reading the user chose in L3.
- **Two migrations, not one.** V34 is the column and its `CHECK`: additive, reversible by dropping a
  column, and reviewable on its own. V35 is the starter catalog, which writes data. Splitting them
  keeps the review slice of the schema change free of a thirty-row seed.
- **The starter catalog is seeded for existing companies by a migration *and* applied to new companies
  by a listener.** The user chose exactly that (L3), and the two halves answer different questions: a
  migration is what makes today's tenants start with the catalog without depending on an application
  start-up, and the `CompanyCreatedEvent` listener is what stops every company created tomorrow from
  starting empty. The catalog therefore exists twice — once as SQL in V35, once as
  `StarterProductCategoryCatalog` — and the duplication is **pinned by a test** that parses the
  migration's value rows and compares them to the Java list in both directions, the same coupling
  `KeycloakClaimMapperCoverageTest` applies to a shell script. Without that test the two halves would
  silently diverge and a company seeded today would not match one created tomorrow.
- **The listener is `@TransactionalEventListener(AFTER_COMMIT)`, like `KeycloakGroupEventListener`.**
  The catalog is convenience data; it must never roll back the creation of a company. A failure is
  logged as `ERROR` with its consequence (a company without any categories) instead of propagating.
- **The applier writes through the repository, not through `ProductCategoryService`.** That service's
  every public method starts with `verifyCompanyAccess`, an operator-context check that a system hook
  has no business passing. The applier holds the rules the catalog needs by construction: two levels,
  no name collisions, so it is a plain insert with a per-sibling guard and no cascade logic.
- **The disabled proposals carry no `sat_code`.** The user delegated which extra branches to ship
  (L2) but not invented fiscal keys: guessing a `ClaveProdServ` that may not exist would be worse than
  an absent one, and the column is optional precisely so that a category can exist before its key is
  verified. They ship disabled so nothing depends on them until someone reviews the codes.

## Specs

- **S1** (verbatim, L1): "le quiero agrega una nueva columna a las categorias de producto, sat_code y
  agregar un catalogo inicial, con estas categorias mas las que tu consideres deshabilitadas".
- **S2** (verbatim, L1): the twelve branches and their SAT keys, quoted in full in L1 — every root and
  every child keeps the user's exact name, including the parentheticals that are part of a name
  ("Cocina (Microondas/Licuadoras)", "Zapatos de Vestir (Oxford, Mocasines)", "Calzado Infantil
  (Niños y Bebés)"), and the parenthetical the user wrote after the key ("Martillos, pinzas") becomes
  the row's `description`.
- **S3** (verbatim, L3): "Backend + pantalla Angular".
- **S4** (verbatim, L3): "Migración + alta de empresa".
- **S5** (verbatim, L3): "Opcional pero validado".
- **S6** (verbatim, L2): "las que tu consideres deshabilitadas" — the extra branches ship with
  `enabled = false` and `sat_code = NULL` (see *Why this shape*).

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1, S3, S5 | inline (parent) | slice 1, `e6df3caf` — `feat(product-category): add optional sat_code to the category tree` |
| T2 | S2, S4, S6 | `gentle-ai-worker` (background, disjoint surfaces) + parent review | slice 3, `08ceb91e` — `seed the starter catalog for existing companies`; slice 4, `cc67e78a` — `apply the starter catalog to new companies` |
| T3 | S3 | `gentle-ai-worker` (background) + parent review | slice 2, `b05e6095` — `edit and show sat_code on the Categories screen` |
| T4 | S1-S6 | inline (parent), `gentle-ai-verify` twice for the high-risk slice | slice 5 — `prove the seed end to end and pin the catalog` (this commit) |

## Log

- **L1** (user, verbatim):

  > le quiero agrega una nueva columna a las categorias de producto, sat_code y agregar un catalogo inicial, con estas categorias mas las que tu consideres deshabilitadas
  > • Ferretería (Categoría Raíz) ➔ Clave SAT: 27110000 (Herramientas de mano)
  >     • Herramientas Manuales ➔ Clave SAT: 27111600 (Herramientas de mano no asistidas / Martillos, pinzas)
  >     • Herramientas Eléctricas ➔ Clave SAT: 27112700 (Herramientas herramientas eléctricas / Taladros, esmeriles)
  > • Materiales de Construcción (Categoría Raíz) ➔ Clave SAT: 30110000 (Concreto, cemento y yeso)
  >     • Fijaciones y Tornillería ➔ Clave SAT: 31161500 (Tornillos)
  >     • Cerraduras y Herrajes ➔ Clave SAT: 31162400 (Candados y cerraduras)
  >
  > • Plomería (Categoría Raíz) ➔ Clave SAT: 40140000 (Distribución de fluidos y gas)
  >     • Tubos y Conexiones ➔ Clave SAT: 40141700 (Tuberías y tubos)
  >     • Válvulas y Llaves ➔ Clave SAT: 40141600 (Válvulas)
  > • Baños y Cocinas (Categoría Raíz) ➔ Clave SAT: 30180000 (Instalaciones de fontanería)
  >     • Muebles de Baño ➔ Clave SAT: 30181500 (Inodoros, lavabos y tinas)
  >     • Grifería y Mezcladoras ➔ Clave SAT: 30181700 (Grifos y tarjas)
  >
  > • Electrodomésticos (Categoría Raíz) ➔ Clave SAT: 52140000 (Aparatos electrodomésticos)
  >     • Lavadoras y Secadoras ➔ Clave SAT: 52141600 (Aparatos de lavandería domésticos)
  >     • Cocina (Microondas/Licuadoras) ➔ Clave SAT: 52141500 (Aparatos de cocina domésticos)
  > • Electrónica y TV (Categoría Raíz) ➔ Clave SAT: 43220000 (Equipos de TI y telecomunicaciones)
  >     • Pantallas y Video ➔ Clave SAT: 45111800 (Televisión y video)
  >     • Cómputo y Accesorios ➔ Clave SAT: 43211500 (Computadoras e impresoras)
  >
  > • Alimentos (Categoría Raíz) ➔ Clave SAT: 50000000 (Alimentos, Bebidas y Tabaco)
  >     • Panadería y Dulces ➔ Clave SAT: 50181900 (Pan dulce, galletas y bollería)
  >     • Lácteos y Huevos ➔ Clave SAT: 50131700 (Productos de leche y mantequilla)
  >
  > Calzado para Dama (Categoría Raíz) ➔ Clave SAT: 53110000 (Calzado)
  >
  > • Zapatos de Tacón y Plataformas ➔ Clave SAT: 53111600 (Calzado de etiqueta / Zapatos de vestir y tacones)
  > • Baletas y Flats ➔ Clave SAT: 53111500 (Calzado informal / Zapatos de piso y casuales)
  > • Botas y Botines ➔ Clave SAT: 53111800 (Botas)
  > • Sandalias y Pantuflas ➔ Clave SAT: 53112100 (Sandalias y pantuflas)
  >
  > 👞 Calzado para Caballero (Categoría Raíz) ➔ Clave SAT: 53110000 (Calzado)
  >
  > • Zapatos de Vestir (Oxford, Mocasines) ➔ Clave SAT: 53111600 (Calzado de etiqueta)
  > • Calzado Casual y Clásico ➔ Clave SAT: 53111500 (Calzado informal)
  > • Botas Casuales o de Trabajo ➔ Clave SAT: 53111800 (Botas)
  >
  > 👟 Calzado Deportivo y Confort (Categoría Raíz) ➔ Clave SAT: 53110000 (Calzado)
  >
  > • Tenis para Correr / Entrenamiento ➔ Clave SAT: 53111900 (Calzado deportivo / Sneakers, running)
  > • Tenis Casuales (Urbano) ➔ Clave SAT: 53111500 (Calzado informal)
  >
  > 👦 Calzado Infantil (Niños y Bebés) (Categoría Raíz) ➔ Clave SAT: 53110000 (Calzado)
  >
  > • Calzado Escolar ➔ Clave SAT: 53111500 (Calzado informal)
  > • Zapatitos para Bebé ➔ Clave SAT: 53111500 (Calzado informal)
  >
  > 👜 Accesorios y Cuidado (Categoría Raíz) ➔ Clave SAT: 53000000
  >
  > • Bolsos y Carteras ➔ Clave SAT: 53121600 (Maletas, bolsos y mochilas)
  > • Limpieza y Cuidado del Calzado ➔ Clave SAT: 53112300 (Accesorios para calzado / Agujetas, plantillas, cremas, cepillos)

- **L2** (user decision, verbatim): "las que tú consideres" — the extra, disabled branches are the
  feature's own proposal; every extra ships `enabled = false` and `sat_code = NULL`.
- **L3** (user decisions, verbatim, from the three-question questionnaire): scope "Backend + pantalla
  Angular"; seeding "Migración + alta de empresa"; obligation "Opcional pero validado".
- **L4** (agent, evidence): anchor checks (`git fetch origin`, `git status --short` empty,
  `origin/main` at `149d5e11`), worktree `feat/product-category-sat-code` created with
  `./.github/scripts/new-unit.sh`.
- **L5** (agent, correction): the user wrote "Herramientas herramientas eléctricas" as the SAT gloss of
  `27112700`; the duplicated word is a typo and the row's `description` reads "Herramientas eléctricas /
  Taladros, esmeriles". Nothing else of S2 is paraphrased: names are verbatim, including the
  parentheticals, and the emoji and "(Categoría Raíz)" markers are formatting, not part of a name.
- **L6** (agent, evidence — T1): RED observed before the fix, twice: the schema suite failed on the
  missing column, the missing `CHECK` and the V33 head
  (`./gradlew test --tests "...ProductCategorySchemaMigrationIntegrationTest"` → 5 failed of 12), and the
  two malformed-`satCode` HTTP tests failed with 201 before the `@Pattern`
  (`./gradlew test --tests "com.lifecontrol.api.product.category.*"` → 3 failed of 82). GREEN after:
  `./gradlew spotlessApply` clean and the same two selections → `BUILD SUCCESSFUL`, 0 failures.
- **L7** (agent, delivery slice): T1 alone was `478` authored changed lines on the single branch, past the ~400-line
  review slice, so the delivery is a five-slice chain on `origin/main`: 330 / 142 / 1356 / 478 / ~330 changed
  lines. No commit content is rewritten, only split; `gentle-ai-chained-pr` is the runbook.
- **L8** (agent, evidence — T3): the Angular worker delivered models, dialog field and list column and reported
  RED before GREEN (`Tests 4 failed | 39 passed` focused, then `3912 passed` for the whole suite). Parent review
  replaced the worker's `EIGHT_DIGITS_WHEN_PRESENT` wrapper: its documented reason — "`Validators.pattern` refuses
  the empty string" — is false in Angular (`patternValidator` returns `null` when `isEmptyInputValue(value)`, read
  in `@angular/forms` `forms.mjs`), so the wrapper was machinery over a wrong premise. The literal
  `Validators.pattern(/^\d{8}$/)` keeps the field optional because the framework skips empty values, and a
  whitespace-only key is refused instead of silently dropped; a spec pins that difference. Re-verified:
  `npx ng test --no-watch --include 'src/features/products/**/*.spec.ts'` → `Test Files 30 passed (30)`,
  `Tests 558 passed (558)`; `npm run lint` clean; `npx prettier --check` clean. Committed as slice 2.
- **L9** (agent, evidence — T2 review): two changes on top of the worker's delivery. (a) A child's `enabled` is
  now composed with its parent's in both V35 (`(s.enabled AND p.enabled)`) and the applier, because a company
  that already had a root disabled would otherwise receive enabled children under it — the state the service
  cascade exists to prevent. RED observed: `Starter Product Category Applier Integration Tests > a disabled
  pre-existing root is reused and its children arrive disabled too` failed with `expected: 0 but was: 2`.
  (b) The migration's own data path had no test (Flyway runs V35 on a database with no companies, so the seed
  inserts nothing); `StarterProductCategoryMigrationIntegrationTest` now replays the shipped script inside a
  rolled-back transaction. Its first version failed on a test bug — `save` left the company unflushed, so the
  script's `JOIN companies` saw no company and the FK rejected the fixture — fixed with `saveAndFlush`, documented
  in the helper. The SQL half of (a) is therefore pinned by a test whose RED came from the Java half of the same
  rule; the two copies are parity-pinned.
- **L11** (agent, independent verification — first pass): `gentle-ai-verify` returned PASS on S1-S6 with six
  findings. Closed here: (a) the company-creation path had no end-to-end test —
  `StarterProductCategoryCompanyCreationIntegrationTest` now creates a company through `CompanyService` and
  asserts 57 rows, and publishes the event with no transaction active to pin `fallbackExecution = true` on the
  listener (without it, a non-transactional publisher created a tenant with no catalog and every test stayed
  green); (b) the parity test's SQL→Java direction was a row *count* with a fixed four-space indentation, so a
  stray row with other indentation passed — it is now a set comparison of the parsed rows, and the 27 enabled
  children are pinned by name, parent and key against L1, not only the 12 roots; (c) the Tasks table and this
  risk line were corrected. Accepted and recorded rather than changed: the whitespace asymmetry (the client
  refuses a whitespace-only key, the server normalizes a blank to `null` — a liberal server and a strict form is
  the intended split, and both halves are pinned), and the destructive `@BeforeEach` in
  `StarterProductCategoryApplierIntegrationTest`, which mirrors `ProductCategorySchemaMigrationIntegrationTest`
  on the same shared container. Follow-up found by the gate itself: the Flyway-head assertion is a literal
  `"33"` in six suites besides this feature's, so adding a migration breaks six green suites unless all seven
  are bumped — updated to `"35"` here, and that duplication deserves its own task.
- **L12** (agent, independent verification — second, delta pass): a delta pass returned no blocking
  finding and four advisories. Closed: the Java→SQL parity direction compared a raw substring with four literal
  spaces (both directions now compare stripped rows), and the set comparison silently collapsed a duplicated V35
  row — a duplicate is not cosmetic, because V35 would fail `uq_product_categories_sibling_name` while applying on
  every deployed database, so a dedicated test now reads the rows as a list and asserts `hasSize` plus
  `doesNotHaveDuplicates`. The twelve root `description`s are pinned to L1: they are user-facing text and were the
  only catalog claim no test could catch if wrong in both copies; the 27 child descriptions stay pinned only
  across the two copies, which is the trade-off this line records. Accepted and recorded rather than changed: the
  new end-to-end test also fires the real `KeycloakGroupEventListener`, so it stays green only because the Keycloak
  client wraps its failures into `IdentityProviderException` and that listener warns — a stub is not worth adding
  here, and the coupling is named so a future change to that wrapping is not a surprise.
- **L10** (agent, decision — worker deviations accepted): the applier is
  `@Transactional(propagation = REQUIRES_NEW)`, not the `@Transactional` this record's brief asked for: the only
  production caller is an `AFTER_COMMIT` listener, where a `REQUIRED` write would join an already-committed
  transaction and never commit. Accepted, and it is what the class Javadoc states. `toMigrationTuple` renders a
  row without the trailing comma so the parity test can match it as a substring of the SQL; the tuple shape is
  asserted separately.
- **L13** (agent, delivery shape): `origin/main` moved six commits while this unit was in flight (PR #626,
  docker image revisions) and the rebase was clean — the changed paths did not overlap (`AGENTS.md`'s new
  paragraph is docker-only, `docker/scripts/**`, `odd/tasks/docker-*`). The work is delivered as five slices on
  `origin/main`, each carrying its own tests and each green on its own base: `sat_code` column + API (366,
  including the seven Flyway head assertions moved to 34 with the migration), the Categories screen (142), the
  V35 seed and the Java catalog plus the head moved to 35 (926 — 410 of it is the catalog content itself, the
  rest is the parity/invariant test that pins it), the applier and the listener (478), and the end-to-end seed
  proofs with this record (544). The split is not cosmetic: the six suites that pin the applied Flyway head as
  a literal make any slice that adds a migration red until the assertion moves with it, which is why that bump
  lives in slice 1 and slice 3 rather than at the end. Every slice compiles and runs the suite it touches, and
  the last slice's tree is identical to the branch that ran the full gates (`./gradlew test` 982 suites / 3973
  tests, `npm test` 192 files / 3913 tests), so that evidence applies to it unchanged.
- **L12** (agent, independent verification — second, delta pass): a delta pass returned no blocking
  finding and four advisories. Closed: the Java→SQL parity direction compared a raw substring with four literal
  spaces (both directions now compare stripped rows), and the set comparison silently collapsed a duplicated V35
  row — a duplicate is not cosmetic, because V35 would fail `uq_product_categories_sibling_name` while applying on
  every deployed database, so a dedicated test now reads the rows as a list and asserts `hasSize` plus
  `doesNotHaveDuplicates`. The twelve root `description`s are pinned to L1: they are user-facing text and were the
  only catalog claim no test could catch if wrong in both copies; the 27 child descriptions stay pinned only
  across the two copies, which is the trade-off this line records. Accepted and recorded rather than changed: the
  new end-to-end test also fires the real `KeycloakGroupEventListener`, so it stays green only because the Keycloak
  client wraps its failures into `IdentityProviderException` and that listener warns — a stub is not worth adding
  here, and the coupling is named so a future change to that wrapping is not a surprise.
- **L10** (agent, decision — worker deviations accepted): the applier is
  `@Transactional(propagation = REQUIRES_NEW)`, not the `@Transactional` this record's brief asked for: the only
  production caller is an `AFTER_COMMIT` listener, where a `REQUIRED` write would join an already-committed
  transaction and never commit. Accepted, and it is what the class Javadoc states. `toMigrationTuple` renders a
  row without the trailing comma so the parity test can match it as a substring of the SQL; the tuple shape is
  asserted separately.
- **L13** (agent, delivery shape): `origin/main` moved six commits while this unit was in flight (PR #626, docker
  image revisions) and the rebase was clean — the changed paths did not overlap (`AGENTS.md`'s new paragraph is
  docker-only, `docker/scripts/**`, `odd/tasks/docker-*`). The 2263 authored lines are delivered as a five-slice
  chain on `origin/main`, each slice carrying its own tests: slice 1 `sat_code` column + API (330),
  slice 2 the Categories screen (142), slice 3 the V35 seed and the Java catalog (1356 — the catalog content
  itself is 410 of that, and the rest is the parity/invariant test that pins it), slice 4 the applier and the
  listener (478), slice 5 the end-to-end seed proofs, the seven Flyway head bumps and this record (~330).
  Every slice compiles and runs the suites it touches; the chain tip's tree is identical to the branch that ran
  the full gates (`./gradlew test` 982 suites / 3973 tests, `npm test` 192 files / 3913 tests), so that evidence
  applies to it unchanged. Once this record travels with the last slice, its header is final there.
