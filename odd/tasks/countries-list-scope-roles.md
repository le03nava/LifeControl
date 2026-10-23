# ODD feature: countries-list-scope-roles

**Repository**: LifeControl — `life-control-api/` + the two documents that describe it
**Status**: implemented — the fix is the allow-list plus the claim filter, in `CompanyCountryController`, `CompanyCountryService` and `CurrentUserContext`, with the three suites that measure it. Nothing is blocked.
**Created**: 2026-10-23 · **Risk**: high — item 2 (authorization surface: it widens who may read an existing endpoint and moves a claim filter onto four more levels).

## Objective

Cerrar la brecha diferida de `GET /api/companies/{id}/countries`: el endpoint rechaza todo rol por
debajo de company-country y, si se lo admitiera sin tocar el filtro, le entregaría a un caller de
región/zona/tienda **todos** los países de la empresa. Issue aprobado: **#548**.

## Specs

**S1 — un rol por debajo de company-country lee la lista, acotado por su propio claim.** Reporte
verbatim del usuario: `a un empleado con posicion de region y rol de lc_region, no tengo permioss para
consultar el selector de paises de compañia, le tengo que asignar otro rol?` La respuesta operativa fue
`lc-company-country-read`; esta unidad elimina la necesidad del rol extra. El conjunto admitido no se
inventa: son los roles con los que las pantallas que consumen este endpoint son alcanzables —medidos en
`companies.routes.ts` y `purchases.routes.ts`— y está fijado en el issue **#548** aprobado
(`status:approved`, 2026-10-23).

**S2 — el filtro se ensancha con la allow-list.** Todo caller admitido cuyo alcance más ancho dentro de
company→store **no sea** company — incluido un rol funcional sin nivel en la jerarquía, como
`lc-employee` — recibe sólo los países nombrados por su claim `company_country_id`; claim ausente o
vacío devuelve lista vacía, nunca la consulta sin filtrar. Ampliar sólo la anotación es el over-grant
horizontal que la spec prohíbe.

**S3 — la composición existente no cambia.** `lc-admin` sigue sin filtro; `lc-company` sigue viendo
toda la empresa; `lc-company-country` y `lc-company-country-read` siguen filtrando por el claim; un
caller sin ningún rol de la jerarquía sigue recibiendo 403. Decisión por aprobación del issue: el
cambio se limita a los niveles que hoy no pueden entrar.

**S4 — lectura, no escritura.** Ningún `@PreAuthorize` de POST/PUT/DELETE cambia: los roles nuevos
entran sólo en el GET de lista.

## Tasks

| ID | Task | Spec | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | RED de la matriz de seguridad: un caso por rol admitido (región, región-read, zona, zona-read, tienda, tienda-read, `lc-receiving`, `lc-employee`) en `CompanyCountryControllerSecurityTest` | S1, S4 | inline | `5f9c626` |
| T2 | RED del filtro: el branch en `CompanyCountryServiceTest` (claim → filtrado, claim vacío → `[]` sin consulta sin filtrar, alcance company → `findByCompanyId`) | S2, S3 | inline | `4225ab3` |
| T3 | `CurrentUserContext.hasCompanyWideScope()`: una sola fuente, leída de `ScopeLevel.companyTo(STORE)` sin ningún literal de rol, con su test por rol | S2 | inline | `4225ab3` |
| T4 | `CompanyCountryController` (allow-list del GET) y `CompanyCountryService` (branch del filtro) | S1, S2, S3 | inline | `4225ab3` `5f9c626` |
| T5 | Docs: la fila de Países y el matiz de `life-control-app-angular/AGENTS.md`, el párrafo diferido de `odd/tasks/scope-assignments-per-level.md` | S1 | inline | this record's commit |
| T6 | Verde medido: `./gradlew test --tests '*CompanyCountry*'` y la suite completa | S1–S4 | inline | `4225ab3` `5f9c626` |
| T7 | Suite HTTP de aceptación sobre PostgreSQL real con `jwt()`: un caso por rol, cuerpo exacto, claim ausente → `[]`, empresa ajena → 403 | S1–S3 | inline | `5f9c626` |

## Decisions

**D1 — el conjunto admitido son los consumidores medidos, no la jerarquía.** `lc-company-region`,
`lc-company-region-read`, `lc-company-zone`, `lc-company-zone-read`, `lc-company-store`,
`lc-company-store-read`, `lc-receiving` (cascade de purchase orders, `purchases.routes.ts:10`) y
`lc-employee` (diálogos de alcance y de tienda de HR). **`lc-sales`, `lc-scheduling` y
`lc-scheduling-read` quedan fuera**: están en el nivel STORE de `ScopeLevel`, pero ninguna pantalla suya
lee este endpoint, y el repo ya trata estas allow-lists como listas planas de consumidores
(`roles.ts:80-95`), no como una jerarquía calculada. Un consumidor nuevo se agrega a sí mismo y su pin
lo obliga. La regla se escribe en el javadoc del controller para que la próxima vez no haya que
adivinarla.

**D2 — el branch del filtro se decide por alcance, no por roles.** La condición pasa de
`!isAdmin && !hasCompanyRole && (countryRole || countryReadRole)` a
`!isAdmin && !hasCompanyWideScope()`. **Corregida durante la implementación**, con la consecuencia
medida en vez de supuesta: la primera versión de esta decisión afirmaba que
`lc-company-read` + `lc-company-country-read` seguiría filtrado por claim, y el cálculo dice lo
contrario — el alcance más ancho de ese caller **es** company, así que lee toda la empresa. Se acepta la
corrección porque el repo ya aplica la regla de unión en el mismo lugar (`verifyCompanyStoreAccess`:
"the broader zone role must not be narrowed by the store role that accompanies it") y porque no hay
privilegio nuevo: `lc-company-read` **solo** ya devuelve todos los países de la empresa (caso `[2]` de
la suite HTTP, verde antes del cambio). Lo que sí cambia es el nombre del accessor: `hasScopeBelowCompany`
no podía expresar la regla elegida en D3 (un rol sin nivel no está "por debajo de company", no tiene
nivel), así que quedó `hasCompanyWideScope()`.

**D3 — `lc-employee` se filtra por su claim, como todo caller que no sea company-wide.** *Reversada por
el dueño el 2026-10-23* (L5): el primer borrador la dejaba sin filtrar, porque es un rol funcional
(ausente de todo `ScopeLevel`) cuyos diálogos de asignación cargan el catálogo entero de países de la
empresa (`scope-assignment-dialog.ts:296-305`, `store-assignment-dialog.ts:179-188`). La decisión
vigente es la del issue aprobado: `lc-employee` se acota por `company_country_id` igual que un rol de
nivel, y un operador de HR cuyo token no traiga ese claim ve el selector **vacío**. La consecuencia se
declara en vez de esconderse: es el precio de que ningún rol funcional reciba el catálogo completo por
la puerta de atrás.

**D4 — el `-read` puro no llega a las pantallas de regiones ni de zonas.** `REGION_ROLES` y `ZONE_ROLES`
listan sólo los nombres de escritura (`companies.routes.ts:8-10`), a diferencia de `STORE_ROLES`. Es una
brecha de rutas del frontend, distinta de esta, y queda **fuera de alcance**: acá no se toca el
frontend porque el caso reportado ya entra a `/companies/regions` con `lc-company-region`.

## Log

**L1** — Pedido originario, verbatim: `a un empleado con posicion de region y rol de lc_region, no tengo permioss para consultar el selector de paises de compañia, le tengo que asignar otro rol?`

**L2** — Verbatim: `ok arreglemos el registro diferido, abre el issue` — la instrucción que convirtió la
brecha diferida en unidad de trabajo con issue propio.

**L3** — Verbatim: `apruebalo` — la aprobación del alcance escrito en **#548**, aplicada con
`status:approved` sobre el objeto del host (`bug` + `status:approved`, sin labels perdidos). Es la
autoridad de S1–S4: el conjunto de roles y el criterio de filtro están en el cuerpo del issue, no en
este registro.

**L4** — Evidencia leída del árbol antes de la primera escritura. El `@PreAuthorize` del GET
(`CompanyCountryController.java:41-43`) admite cinco roles y ninguno de la jerarquía baja; el filtro
(`CompanyCountryService.java:58-70`) sólo se activa para el nivel país y cae a `findByCompanyId` para
todo lo demás; el frontend consume el endpoint desde regiones, zonas, seis pantallas de tienda, los dos
diálogos de HR y el cascade de purchase orders. `life-control-app-angular/AGENTS.md:964-967` ya declaraba
el matiz (un `lc-company-region` recibiría todos los países sin filtrar) y
`odd/tasks/scope-assignments-per-level.md:80-83` ya lo declaraba diferido.

**L5** — Verbatim, la decisión del dueño sobre D3: `Filtrado por su claim (literal del issue)` — elegida
sobre la opción que el registro declaraba, con la consecuencia escrita en la opción: *"Cumple el
criterio al pie de la letra, pero limita los diálogos de asignación al país del operador"* y *"claim
ausente o vacío devuelve lista vacía"*.

**L6** — RED y GREEN medidos, en este orden. T1: `40 tests, 9 fallos` (los ocho roles nuevos en 403 y el
pin del `@PreAuthorize` del GET contra el set viejo). T3: compile-RED por `cannot find symbol`, después
`170 tests` verdes con el accessor. T2: `4 fallos` de aserción con el branch viejo. T2b: `24 tests, 17
fallos` — ocho roles y siete casos sin claim en 403, `lc-employee` en 403, y `lc-company-read` +
`lc-company-country-read` devolviendo **1** país donde la regla de unión pide 2 (la medición que
corrigió D2). GREEN tras T4: `283 tests` verdes en `*CompanyCountry*` + `*CurrentUserContextTest*`.

**L7** — La regla del branch, una sola vez y sin literales de rol: `hasCompanyWideScope()` deriva de
`ScopeLevel.companyTo(STORE)`. Un rol que se agregue a un nivel más angosto queda filtrado sin tocar el
service; uno que se agregue a `COMPANY` deja de serlo. El pin del controller es el que impide que la
allow-list crezca sin que el filtro lo acompañe.

**L8** — Entrega técnica: `4225ab3` (la decisión de alcance, `CurrentUserContext` + service) y
`5f9c626` (la allow-list, el pin y la suite HTTP); este registro y los dos documentos viajan en el mismo
PR. Gates del CI del API corridos sobre los bytes finales: `spotlessCheck spotbugsMain test` →
**964 suites / 3862 tests / 0 fallos / 0 errores / 0 skipped**. `gh pr checks` y el merge son decisión
del dueño, no parte de esta unidad.
