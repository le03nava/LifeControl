# ODD feature: hierarchy-read-roles

**Repository**: LifeControl — `life-control-api/` + `life-control-app-angular/`
**Status**: open — queda por hacer S1 (`lc-company-read` otorgable), S2 (selector por claim), S3 (`-read` de ancestros en los GET de descendientes) y T4 (migración de los consumidores del frontend). Este header no afirma nada sobre push ni PR; ver el log.
**Created**: 2026-02-21 · **Risk**: alto — superficie de autorización (item 2) y criterios de lectura por nivel.

## Objective

Que los roles de lectura de la jerarquía de compañía sean utilizables de punta a punta: que se
puedan otorgar desde una plantilla de puesto, que alcancen todo el subárbol de su nivel, y que el
selector de compañías deje de exigir un rol de escritura.

## Specs

**S1 — un rol de lectura de nivel N lee la lista y el detalle de todo nivel ≥ N, dentro de su alcance.**
Decisión del usuario sobre la pregunta 1 del diseño (¿un rol de lectura de nivel N ve el subárbol?).
Respuesta verbatim: `1 si`. El alcance horizontal ya lo impone el claim; esto sólo amplía la lectura
vertical del subárbol. Roles afectados: `lc-company-read`, `lc-company-country-read`,
`lc-company-region-read`, `lc-company-zone-read`, `lc-company-store-read`.

**S2 — el selector de compañías no lleva rol nuevo: se autoriza por el claim `company_id`.**
Respuesta verbatim del usuario: `2 claim`. `GET /api/companies/selector` devuelve sólo compañías ya
presentes en el claim del JWT, con `isAuthenticated()` como gate de anotación. No se crea ningún
`lc-*` para el selector.

**S3 — `lc-company-read` conserva el acceso a los endpoints actuales y NO se le muestra la pantalla de Companies.**
Respuesta verbatim del usuario: `3 por lo pronto puede acceder a los end point actuales pero no le muestres la pantalla de companies`.
Consecuencia: `lc-company-read` entra en la allowlist de plantillas y pasa a ser grantable, pero
**queda fuera** de `isCompanyRole` (`header.ts`) y de `BASE_ROLES` / `COMPANY_CRUD_ROLES`
(`companies.routes.ts`). La pantalla de Companies sigue siendo de administración.

**S4 — un usuario de tienda debe poder leer los datos fiscales de su empresa (RFC, dirección).**
Requisito originario, verbatim: `si quisiera que ciertos usuarios puedan leer informacion de la empresa, por ejemplo el de una tienda debe de poder ver informacion de la empresa rfc, direccion etc`.
Por S3 esto se entrega **sólo en la API**: el acceso a los endpoints actuales de compañía. Mostrarlo
en las pantallas operativas (read model angosto / consumo de UI) queda fuera de este registro.

## Tasks

| ID | Task | Spec | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | `ScopeLevel.COMPANY` acepta `Roles.COMPANY_READ` y `GrantablePositionRoles.FROZEN` acepta `lc-company-read`, con los dos pins verdes | S1, S3 | inline | `6df7654` |
| T2 | `GET /api/companies/selector` filtrado por el claim `company_id`, con gate `isAuthenticated()` y su matriz de seguridad | S2 | inline | `0ac9e6c` |
| T3 | Los `-read` de los ancestros entran en los GET de lista y detalle de los niveles descendientes, con su matriz de seguridad por controller | S1 | delegado (`gentle-ai-worker`) | — |
| T4 | Los consumidores del frontend dejan de usar `getCompanies(0, 1000)` como selector y pasan al selector nuevo | S2 | delegado (`gentle-ai-worker`) | — |
| T5 | Pin de S3: `lc-company-read` no entra en `isCompanyRole` ni en las rutas de Companies | S3 | inline | — |

**Fuera de alcance de T4, con razón**: `company-context.service.ts`, `user-profile.component.ts`,
`company-list.ts` y `company-edit.ts` siguen sobre la lectura completa. `currentCompany()` se consume
sólo en `company-edit.ts:76`, que necesita el modelo entero; migrarlos exige mover esa pantalla a su
propia lectura, que es otro cambio con su propio riesgo. Queda como trabajo posterior con nombre
propio en vez de colarse en esta migración.

**Por qué todo inline y no delegado**: una sola unidad de comportamiento con dependencias entre
slices (T4 depende de T2; T3 comparte el módulo Gradle con T1 y T2), así que dos writers en el mismo
worktree competirían por el mismo build. El tamaño no alcanza como razón para delegar por sí solo.

## Log

**L1** — Pedido originario, verbatim: `si quisiera que ciertos usuarios puedan leer informacion de la empresa, por ejemplo el de una tienda debe de poder ver informacion de la empresa rfc, direccion etc, o que recomiendas?`

**L2** — Pedido de diseño, verbatim: `quisiera dos enfoques, uno si seria de company-read que pueada ver la pantalla en modo lectura y otro rol por ejemplo para la lectura del selector de compañias que ocupa la pantalla de tiendas y asi para toda la jerarquia revisar que nuevos roles y a que servicios se les daria permisos`

**L3** — Las tres decisiones, verbatim: `1 si, 2 claim, 3 por lo pronto puede acceder a los end point actuales pero no le muestres la pantalla de companies`

**L4** — 2026-02-21 · Medición que funda las specs, leída del árbol y no del registro. `GET /api/companies` —el
alimento del primer combo de ~12 pantallas— sólo acepta `lc-admin`, `lc-company` y `lc-company-read`
(`CompanyController:42`), así que ningún `-read` puede pintar el selector y la cascada entera queda
inutilizable para todo rol de sólo lectura. El patrón se repite hacia abajo: cada GET de lista acepta
los roles de **escritura** de todos sus ancestros más el `-read` de **su propio** nivel, y nunca los
`-read` de los ancestros (`CompanyRegionController:36`, `CompanyZoneController:37`,
`CompanyStoreController:38`, `StoreZoneController:48`). Los catálogos ya son `isAuthenticated()` y no
necesitan roles. **Cero roles nuevos hacen falta**: los cinco `-read` existen en `Roles.java`, en
`roles.ts` y en Keycloak; el único huérfano es `lc-company-read`, que no es grantable, no muestra menú
y está subsumido por `lc-company` en todos los endpoints que lo aceptan.

**L5** — 2026-02-21 · **T1 y T2 entregados**, cada uno en su commit porque cada uno toca superficie de
autorización. `6df7654` hace `lc-company-read` grantable: `ScopeLevel.COMPANY` pasa a declarar
`Roles.COMPANY_READ` junto a `Roles.COMPANY`, `Roles.DEPARTMENT` y `Roles.POSITION`, y la allowlist
congelada pasa a dieciséis literales. Los dos pins del `GrantablePositionRolesTest` quedaron verdes en
la misma corrida (2 tests, 0 fallas), que es la prueba de que ambos lados se movieron juntos; los tres
javadocs que decían "fifteen" ahora dicen "sixteen". `0ac9e6c` agrega `GET /api/companies/selector`
con gate `isAuthenticated()`: el `getAllCompanies` se refactorizó sobre un `findCompaniesForCurrentUser`
privado que ambos comparten, así que el filtro por claim tiene **una** copia y los siete tests que ya
existían para `getAllCompanies` siguen verdes sin tocarlos — esa es la evidencia de que el
comportamiento previo se mantuvo (S2). El modelo `CompanySelectorResponse` lleva `id`, `companyKey` y
`companyName` y nada más. La matriz nueva: 4 tests de seguridad (una persona de tienda con `lc-sales`
y sin ningún rol de compañía recibe 200, un autenticado sin roles recibe 200, un anónimo es rechazado,
y `/selector` no colisiona con `/{id}`) más 3 tests de servicio (proyección, filtro compartido por
claim, claim vacío sin lecturas). Total de la corrida enfocada: **54 tests, 0 fallas**, con
`CompanyServiceTest$GetCompanySelectorTests` 3 y `CompanyControllerSecurityTest$CompanySelectorAccess`
4.
