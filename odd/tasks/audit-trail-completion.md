# ODD feature: audit-trail-completion

**Status**: implemented — the three units below are the work: `T1` closes the seed gap that silently
discards HR, PROVISIONING and GOODSRECEIPT activity entries and replaces the hardcoded coverage list
with a derived guard; `T2` adds `employees.created_by` and writes the authenticated actor on creation;
`T3` adds a Reportes menu entry and the admin-only activity-log screen. Nothing is deferred.
**Created**: 2026-10-23 · **Risk**: high — `T2` is a schema migration on `employees` (item 1) and `T3`
adds an admin-only read surface over the audit trail (item 2).

## Specs

`S1` — **Seed coverage for every domain that has a controller.**
Quote (L1): `quien creo un empleado, quien le asignó pemisos etc para tener alguna rastreabilidad?`
The audit aspect derives the process name from the controller's package
(`com.lifecontrol.api.<domain>.controller` → `<DOMAIN>` uppercased), and `ActivityLogService.save`
discards any entry whose name is absent from `activity_processes`, with only a `WARN`. The migrations
that seed that table (`V3`, `V4`, `V16`) never seed `HR`, `PROVISIONING` or `GOODSRECEIPT`, so the
employee-create path, the access-request path and the goods-receipt path are unaudited today. Every
name derivable from a controller package must exist in `activity_processes`; a request to any of them
must leave exactly one `activity_logs` row.

`S2` — **`created_by` on `employees`.**
Quote (L2): `el punto dos si sería agregar el created_by en la tabla`
`employees` gains `created_by VARCHAR(255)` (nullable, no default). The employee-create path writes the
authenticated actor's **username** into it — the same actor source and representation the codebase
already uses for `inventory_movements.created_by` (`CurrentUserContext.getUsername()`) and
`GoodsReceiptService`'s `receivedBy`. It is written **once, at creation**, and has no update path: the
employee edit, enable and status routes must leave it untouched. The value is readable and never
typable — no request record carries it. It is exposed read-only as `EmployeeResponse.createdBy` so the
fact is verifiable through the public API.

`S3` — **Reportes menu and the activity-log screen.**
Quote (L2): `del 3 si seria hacer una pantalla para ver los logs que puedes estar en un nuevo menu de Reportes`
A new top-level menu entry labelled `Reportes` opens an activity-log screen that reads
`GET /api/activity-logs`. The screen lists one row per entry with `createdAt`, `username` (falling back
to `userId` when the username is absent), `process`, `event`, `httpMethod`, `httpStatus`,
`requestPath` and `ipAddress`, paginates server-side, and filters by date range (`from`, `to`), process,
event, userId and HTTP method — the six filters the endpoint accepts, no more. Visibility and access
are `lc-admin` only, mirroring the endpoint's `hasRole('lc-admin')`: the menu entry is hidden for every
other role, and the route is guarded so a direct URL is refused.

`S4` — **No regression on the surfaces this work touches.**
The aspect, the sanitizer, the existing seed migrations and the pre-existing employee routes keep their
current behavior. A seeded-domain request still produces its row exactly as before, and an employee
update still leaves `created_by` and `created_at` unchanged.

## Tasks

`T1` — Seed `HR`, `PROVISIONING`, `GOODSRECEIPT` in a new migration; replace the hardcoded process list
in `ActivityLogIntegrationTest` with a derivation from the controller packages, so a future domain with
no seed turns the suite red. Spec: `S1`, `S4`. Route: inline. Commit: `d973ec0` (slice 1).

`T2` — `V28` adds `employees.created_by`; `Employee` maps it with a named write path, `EmployeeService`
fills it from `CurrentUserContext`, `EmployeeResponse` exposes it; tests cover create, update and the
absence of a request field. Spec: `S2`, `S4`. Route: `gentle-ai-worker`. Commit: `77c3d71` (slice 2).

`T3` — `@features/reports` with an admin-gated route, the activity-log screen (server pagination plus the
six filters), a data service over `GET /api/activity-logs`, the `Reportes` entry in `header.ts` gated
the same way, and unit tests for the gate, the filters and the paging. Spec: `S3`, `S4`. Route:
`gentle-ai-worker`. Commits: `18a98a5`, `73eecbf`, `2945fd5`.

## Delivery

Five reviewable slices on `feat/audit-trail-completion`, each under or near the ~400 authored-line
review budget, in this order. Slices 1 and 2 both move the Flyway head pin, so they cannot be reordered
without moving it twice; slices 3-5 touch no backend file and can be dropped without affecting the
first two. Push and pull-request creation are the maintainer's decision, not this unit's.

| Slice | Commit | Lines | Content |
| --- | --- | --- | --- |
| 1 | `d973ec0` | 382 | `S1` — V27 seed + derived coverage guard |
| 2 | `77c3d71` | 292 | `S2` — `employees.created_by` |
| 3 | `18a98a5` | 276 | `S3` — the typed read path (models + service + spec) |
| 4 | `73eecbf` | 607 | `S3` — the screen (component, template, styles, spec); irreducible: a template without its logic does not compile |
| 5 | `2945fd5` | 207 | `S3` — the wiring where the access decision lives (route + menu + e2e mock + their specs) |

Slices 3-5 are a history split of one tree that was already verified green as a single commit
(`bc4dd00`, reflog); `git diff bc4dd00 2945fd5` is empty, so the split changed no content. The tip is
the tree the gates ran on; the three intermediate commits were not separately exercised, and the first
PR of the chain is where CI picks each of them up.

## Log

`L1` — 2026-10-23, pedido original del usuario, verbatim:
> tenemos alguna tabla tipo auditoria o de eventos, donde se registre los logs de ciertos movimientos, por ejemplo de quien creo un empleado, quien le asignó pemisos etc para tener alguna rastreabilidad?

`L2` — 2026-10-23, alcance autorizado, verbatim:
> arma el documento odd, el punto dos si sería agregar el created_by en la tabla, y del 3 si seria hacer una pantalla para ver los logs que puedes estar en un nuevo menu de Reportes,

`L3` — 2026-10-23, evidencia de la exploración (lectura, sin escritura):
- `life-control-api/src/main/resources/db/migration/V1__baseline_schema.sql:232` — `activity_logs`
  (inmutable: `user_id`, `username`, proceso, evento, método, status, path, ip, user-agent,
  `payload_json`, `created_at`), con las tablas de referencia `activity_processes`/`activity_events`.
- `life-control-api/src/main/java/com/lifecontrol/api/activity/aspect/ActivityLogAspect.java` — el
  proceso se deriva del paquete y el evento del verbo HTTP; `@ActivityLog` existe pero no lo usa ningún
  controller; en excepción no se escribe fila.
- `.../activity/service/ActivityLogService.java` — `processRepository.findByName` vacío ⇒ `WARN` y
  `return`: el descarte es silencioso.
- Seeds: `V3`, `V4`, `V16` siembran 22 nombres y ninguno es `HR`, `PROVISIONING` ni `GOODSRECEIPT`.
- `.../activity/controller/ActivityLogIntegrationTest.java` — la lista de procesos del test está
  hardcodeada y tampoco nombra esos tres, por eso pasa en verde sobre el agujero.
- `.../db/migration/V20__employee_registry.sql` — `employees` no tiene actor; `V10__inventory.sql:39`
  define el único `created_by` del esquema, cuya semántica es el **username** (`GoodsReceiptService:230`
  toma `currentUserContext.getUsername()`).
- `life-control-app-angular/src/core/layout/header/header.ts` — el menú es un `computed` de items con
  `id`/`routeLink`/`textLink`/`icon`, gateado por rol; no existe ninguna entrada de reportes ni pantalla
  que consuma `/api/activity-logs`.

`L4` — 2026-10-23, T1 ejecutado (inline).
- Se extrajo la derivación del proceso a `activity/util/ProcessNameResolver` y el aspecto delega en ella,
  para que el guard y el aspecto no puedan divergir.
- RED: `./gradlew test --tests "com.lifecontrol.api.activity.controller.ActivityLogIntegrationTest"` →
  `8 tests completed, 2 failed`, con `activity process 'GOODSRECEIPT' should be seeded —
  ActivityLogService discards its entries with only a WARN` y la variante de persistencia. El guard
  descubrió los tres nombres por escaneo de paquetes, no por lista.
- GREEN: `V27__seed_hr_provisioning_goodsreceipt_processes.sql` siembra `HR`, `PROVISIONING` y
  `GOODSRECEIPT` con el guard idempotente de V4/V16; `./gradlew test --tests "com.lifecontrol.api.activity.*"`
  → `BUILD SUCCESSFUL`.
- Regresión encontrada y corregida: siete tests fijaban la cabeza de Flyway en `"26"`
  (`GoodsReceiptIntegrationTest`, los cinco de `hr/` y `AccessProvisioningSchemaMigrationIntegrationTest`);
  ahora exigen `"27"` y siguen verificando `pending().isEmpty()`.
- El `setUp` del test ya no crea procesos: crear `ACTIVITY`/`COMPANY`/`ORDER` a mano era justo lo que
  enmascaraba el hueco.

`L5` — 2026-10-23, flake pre-existente encontrado en la verificación de T1 (fuera de alcance, se
reporta y no se toca).
- Una corrida completa de `./gradlew test` falló `14` tests, **todos** de
  `EmployeeCrudIntegrationTest`, con `DataIntegrityViolationException`; la clase pasa aislada.
- Diferencial contra `origin/main` (`9ba860f`): `EmployeeCrudIntegrationTest.java` y
  `AbstractPostgresIntegrationTest.java` son byte-idénticos entre rama y base, y dos corridas completas
  posteriores (rama y base) terminaron `BUILD SUCCESSFUL` con `EmployeeCrud` 14/14 en verde.
  ⇒ dependencia de orden/estado entre clases sobre el contenedor PostgreSQL estático compartido, no
  una regresión de esta unidad. Mecanismo estructural identificado y no reproducido: el `setUp` de
  `EmployeeCrudIntegrationTest` borra solo `employees` mientras tres tablas (`employee_contracts`,
  `employee_store_assignments`, `employee_scope_assignments`) le ponen FK, así que el orden de clases
  decide si el `DELETE` choca con una fila huérfana. Sigue como defecto abierto de test isolation, con
  issue propia si se decide arreglarlo.

`L6` — 2026-10-23, T2 ejecutado (`gentle-ai-worker`), commit `77c3d71`.
- `V28` agrega `employees.created_by VARCHAR(255)`, nullable, sin default y **sin backfill**: las filas
  previas quedan `NULL` porque su creador nunca se registró; un creador adivinado es peor que un `NULL`
  honesto. Mapeada con `updatable = false` para que Hibernate no pueda emitirla en un `UPDATE`.
- `Employee.markCreatedBy` es el único camino de escritura (sin setter público, sin builder) y lanza
  `IllegalStateException` en la segunda llamada; un latch `@Transient` evita que un primer `NULL` parezca
  "no escrito". Único call site: `EmployeeService.createEmployee`.
- RED 1 (compilable): `28 tests completed, 5 failed` — columna inexistente en `information_schema` y
  `BadSqlGrammarException: SELECT created_by FROM employees`. RED 2: `cannot find symbol: markCreatedBy`.
  GREEN: `./gradlew test --tests "com.lifecontrol.api.hr.*"` 722 tests, 0 fallos; suite completa 3810
  tests, 0 fallos, exit 0 (en esa corrida el flake de `EmployeeCrud` no apareció).
- Los siete pins de la cabeza de Flyway pasan a `"28"`.

`L7` — 2026-10-23, T3 ejecutado (`gentle-ai-worker`) y commiteado en `18a98a5`, `73eecbf`, `2945fd5`.
- RED: `TS2307: Cannot find module './activity-log.service'`, `expected 8 to be 9` en `header.spec.ts`
  (230 y 537) y `expected undefined to be defined` en el caso del menú; `5 failed | 82 passed`.
- GREEN: `npx prettier --write` exit 0, `npm run lint` exit 0 (`All files pass linting.`), `npm run test`
  exit 0 (`190 passed`, `3845 passed`, coverage 87.03% statements), `npm run build` exit 0.
- **Corrección de una decisión del padre**: el handoff pedía `data: { role: 'admin' }`, la forma legacy
  que el guard resuelve contra `realm_access.roles`. `lc-admin` es un rol de cliente de
  `life-control-client`, así que esa forma habría rechazado a todo tenedor legítimo. La ruta usa
  `{ roles: [LC_ADMIN], clientId }`, que es la forma que `app.routes.ts` ya usa para `/users-admin`, y el
  `app.routes.spec.ts` la fija. Sin esa corrección el menú se veía y la pantalla respondía 403.
- El worker no tenía `node_modules` en el worktree y creó un symlink gitignoreado al del anchor para
  correr los gates; se borró antes del commit, así que el slice no lo incluye.
- Reparto: el commit único de la pantalla eran 1090 líneas, por encima del presupuesto de revisión. Se
  cortó en tres (lectura / pantalla / cableado) y el árbol quedó idéntico al verificado: `git diff
  bc4dd00 2945fd5` vacío.
