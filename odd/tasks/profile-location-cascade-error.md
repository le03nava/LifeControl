# ODD record: profile-location-cascade-error

**Repository**: LifeControl — `life-control-app-angular/` (the `/profile` location cascade) and the two documents that describe it
**Status**: implemented — the cascade reads of `/profile` stop swallowing their errors in `UserProfileComponent`; the backend allow-list is deliberately untouched.
**Created**: 2026-10-23 · **Risk**: none — display of a failure on one screen: no data, no authorization surface, no wire contract and no concurrency changes.

## Objective

Cerrar **#550**: el selector de país de `/profile` se come el 403 del listado de países y renderiza un
combo vacío, indistinguible de "esta empresa no tiene países". Issue: **#550**.

## Specs

**S1 — el camino de error deja de ser silencioso.** Verbatim de #550 (*Expected outcome*): `Whichever way
it goes, the error path stops being silent: a failed read shows a message, the way ErrorBanner already
does on the company screens.` Un read fallido de la cascada de ubicación muestra su mensaje en la
pantalla, con el mismo `ErrorBanner` que usan las pantallas de compañía.

**S2 — el mensaje distingue el límite de permisos del fallo genérico.** Un `403` dice que falta acceso a
ese nivel —y no que la empresa no tenga países—; cualquier otro fallo dice que la carga falló y que se
puede reintentar. Se separa porque los dos diagnósticos son distintos y el silencio que #550 reporta es
justamente el que impedía distinguirlos.

**S3 — el alcance es la cascada entera, no sólo países.** Países, regiones, zonas y tiendas comparten el
mismo patrón de `error` vacío. Y no es hipotético: un caller al que la allow-list de países admite y la de
regiones no —`lc-employee` y `lc-receiving` están en `CompanyCountryController:68-70` pero **no** en
`CompanyRegionController:38-39`, ni en zona ni en tienda— recibe `200` filtrado un nivel y `403` el
siguiente. Arreglar sólo países deja el mismo defecto un nivel más abajo, alcanzable por el caller que
#550 nombra.

**S4 — la allow-list del backend no se amplía (decisión del dueño, 2026-10-23).** Decisión elegida:
`No ampliar: /profile muestra el error`. El conjunto admitido sigue siendo el de consumidores medidos de
#548. Además, ampliarlo no cerraría el modo de falla: un caller admitido **sin** `company_country_id`
recibe `200` con lista vacía (costo aceptado, decisión D3 de #548), y un `200` vacío no es un error que
un mensaje pueda reportar.

**S5 — el resto de la pantalla no cambia.** `error()` sigue siendo el estado de carga y guardado del
perfil; un read exitoso no deja mensaje; el formulario, el payload y el modo restringido (`assignedStores`)
quedan igual.

## Tasks

| ID | Task | Spec | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | RED del mensaje: 403 en la lectura de países deja mensaje y `app-error-banner` en el DOM; 403 en regiones hace lo mismo | S1, S2, S3 | inline | this record's commit |
| T2 | RED del camino feliz y del reintento: un read exitoso no deja mensaje; cambiar de empresa tras un fallo limpia el mensaje | S1, S5 | inline | this record's commit |
| T3 | `UserProfileComponent`: estado `locationError` y los cuatro callbacks de error de la cascada | S1, S2, S3, S5 | inline | this record's commit |
| T4 | Template: el `ErrorBanner` de la sección Location Preferences | S1 | inline | this record's commit |
| T5 | Docs: la fila de Países de `life-control-app-angular/AGENTS.md` nombra este consumidor | S3 | inline | this record's commit |
| T6 | Verde medido: spec focalizado, suite completa y `lint` del front | S1–S5 | inline | this record's commit |

## Log

**L1** — Pedido verbatim del usuario: `continua con el 550`. Sobre el issue **#550**
(`fix(profile): the country selector on /profile swallows a 403 from the countries list and renders
empty`), que deja abierta una decisión de producto.

**L2** — Decisión del dueño (2026-10-23), elegida contra las alternativas de ampliar la allow-list o
diferirla: `No ampliar: /profile muestra el error`. Razón medida que la sostiene: `/profile` lo alcanza
cualquier caller autenticado (`app.routes.ts:58-63`, `keycloakRoleGuard` sin `data.roles`), la sección de
ubicación es opcional, y el endpoint ya filtra por `company_country_id` a todo caller cuyo alcance más
ancho no sea company, así que ensanchar quién entra no elimina el combo vacío silencioso.

## Evidence log

**2026-10-23 — RED, observado antes de cablear nada.** Con el miembro `locationError` presente como stub
(para que el spec compile) y **sin** ningún callback de error ni banner, el spec del componente:
`npx ng test --no-watch --coverage=false --include='src/features/user/profile/user-profile.component.spec.ts'`
→ `Test Files 1 failed (1)`, `Tests 5 failed | 64 passed (69)`. Los cinco fallos son del caso nuevo y cada
uno dice el defecto con su propia voz: `AssertionError: expected null to be truthy` sobre
`locationError()` para el 403 de países, el 403 de regiones, el fallo genérico y el mensaje heredado
tras reintentar. El sexto caso nuevo —el read exitoso no deja mensaje— **pasa en RED**: es el guard de
que el camino feliz no cambia (`S5`).

**2026-10-23 — GREEN medido, sobre los mismos bytes que lleva el registro.** Spec focalizado:
`Tests 69 passed (69)` (63 previos + 6 nuevos). `npm run lint` → `All files pass linting.` `npm run build`
→ `Application bundle generation complete`, `dist/life-control-app-angular`. Suite completa del front:
`Test Files 190 passed (190)`, `Tests 3851 passed (3851)`. Y el guard del repo sobre este registro:
`./.github/scripts/tests/odd-status-headers.test.sh` → `78 passed, 0 failed` (104 registros, 118 merge
commits citados, `ancestry: ran`).

**2026-10-23 — lo que esta unidad NO hace, medido y a propósito.** No toca ningún `@PreAuthorize`
(`S4`): el diff completo son dos documentos, el componente, su template y su spec. No agrega un mensaje
para el caso `200`-con-lista-vacía: ese caller no recibió un error, y decirle que algo falló sería
mentirle; el costo sigue declarado en `CompanyCountryService.getCountriesByCompanyId` y en la nota de
Países de `life-control-app-angular/AGENTS.md`.

**2026-10-23 — entrega.** Unidad de un solo commit de review: `6758a23` (`fix(profile): surface a failed
location cascade instead of an empty selector`), **237 líneas autoradas** sobre `origin/main` `9c9a03f`,
muy por debajo del presupuesto de ~400. Va como **PR #564** (`type:bug`, `Closes #550`), con el issue
**#550** aprobado (`status:approved`) antes de abrirlo, que es lo que exige la plantilla. Este renglón es
evidencia de entrega: el header de arriba no afirma nada sobre push, PR ni merge, y no hace falta que lo
haga.
