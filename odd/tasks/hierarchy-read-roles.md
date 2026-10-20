# ODD feature: hierarchy-read-roles

**Repository**: LifeControl — `life-control-api/` + `life-control-app-angular/`
**Status**: delivered — S1, S2 y S3 implementadas y verificadas. El backend entró por PR **#474** (merge `7ab89e90`, 2026-10-20) y la migración del frontend con sus pins por PR **#475**. El trabajo restante de este registro es **G1** —el listado de comprobantes no honra el alcance—, que pide registro propio.
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
| T3 | Los `-read` de los ancestros entran en los GET de lista y detalle de los niveles descendientes, con su matriz de seguridad por controller | S1 | delegado (`gentle-ai-worker`) + revisión inline | `5adf82d` |
| T4 | Los consumidores del frontend dejan de usar `getCompanies(0, 1000)` como selector y pasan al selector nuevo | S2 | delegado (`gentle-ai-worker`), cerrado inline | `6e80b3c` |
| T5 | Pin de S3: `lc-company-read` no entra en `isCompanyRole` ni en las rutas de Companies | S3 | inline | `2e0e5d0` |

**Fuera de alcance de T4, con razón**: `company-context.service.ts`, `user-profile.component.ts`,
`company-list.ts` y `company-edit.ts` siguen sobre la lectura completa. `currentCompany()` se consume
sólo en `company-edit.ts:76`, que necesita el modelo entero; migrarlos exige mover esa pantalla a su
propia lectura, que es otro cambio con su propio riesgo. Queda como trabajo posterior con nombre
propio en vez de colarse en esta migración.

**Delegación, medida.** T1, T2 y T5 fueron inline; T3 y T4 se delegaron a un `gentle-ai-worker` cada uno
y el padre los revisó inline. La razón de planearlos inline al principio —una sola unidad de
comportamiento con dependencias entre slices (T4 depende de T2; T3 comparte el módulo Gradle con T1 y
T2), donde dos writers en el mismo worktree compiten por el mismo build— se sostuvo: **cada writer
trabajó con el slice anterior ya commiteado**, nunca dos a la vez sobre el mismo árbol. El de T4 murió
a mitad, sin veredicto (L7); el padre cerró el slice.

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

**L6** — 2026-02-21 · **T3 entregado** como `5adf82d`, con una exclusión deliberada. Doce controllers y
sus hermanos planos ampliaron sólo sus `@GetMapping` para admitir los `-read` de todos los ancestros;
cada `@PostMapping`/`@PutMapping`/`@PatchMapping`/`@DeleteMapping` quedó byte-idéntico. El aporte
llegó de un writer delegado que probó el RED de verdad (368 tests, 71 fallas, una por cada caso
ancestro nuevo) y después el GREEN (368/0). **Revisado inline, y el aporte traía un problema real**:

- **`GoodsReceiptController` quedó revertido.** `GoodsReceiptService#getAllReceipts:338` filtra a
  **todo** no-admin por `company_store_ids`, sin escalonar por rol. Un portador de `lc-company-read`,
  `lc-company-country-read`, `lc-company-region-read` o `lc-company-zone-read` habría pasado la
  anotación y recibido **200 con una página vacía** — un "no hay comprobantes" falso, en vez de un
  403 diagnosticable, justo para las personas que S1 admite. Es una unidad aparte: hay que escalonar
  el filtro por alcance, y eso **cambia lo que hoy ve `lc-company`**, que ya recibe 200-vacío. Se
  revirtió el controller y su matriz al estado base, y queda como gap con nombre propio abajo.
- **`CompanyStoreFlatControllerSecurityTest` es nuevo.** El propio writer marcó que el `GET
  /api/stores/{id}` ampliado no tenía matriz que lo cubriera: el controller anidado tiene su
  `@WebMvcTest` y el test plano corre en `standaloneSetup` sin method security. 13 tests: 11 roles
  admitidos por 200, `lc-sales` por 403 y un anónimo rechazado.
- Los otros cuatro servicios de lista **sí** escalonan su filtro por rol (`CompanyCountryService:56`,
  `CompanyRegionService:75`, `CompanyZoneService:82`, `CompanyStoreService:105`): filtran por el claim
  angosto sólo cuando el portador tiene el rol angosto, y si no caen a la consulta sin filtro. Por eso
  la ampliación es correcta ahí y en goods receipts no.
- `spotlessApply` corrido sobre el módulo: los cinco renglones de `@ValueSource` que el writer dejó en
  123 columnas quedaron dentro del presupuesto. **Corregido el 2026-02-21 (L9): la afirmación era
  incompleta.** `spotlessApply` sí se corrió, pero `spotlessCheck` fallaba después sobre los bytes
  commiteados por un segundo archivo —`CompanyStoreFlatControllerSecurityTest`, con dos
  `import static` desordenados y un `mockMvc.perform(...)` pasado de columna—, así que el módulo no
  quedaba limpio: lo arregla `18faf8c`. La lección es que `spotlessApply` es una acción, no una
  verificación; lo que prueba es `spotlessCheck` sobre el commit.
- Verificación post-revert: las nueve clases de seguridad y la nueva del flat store,
  **BUILD SUCCESSFUL**.

**G1 — el listado de comprobantes no honra el alcance (arreglado en su propio registro).**

> **Actualización 2026-10-20**: el arreglo vive en `odd/tasks/goods-receipt-scope.md` (rama
> `fix/goods-receipt-scope`): el alcance efectivo se deriva del `ScopeLevel` más amplio otorgado y las
> dos lecturas admiten los `-read` de los ancestros. La descripción de abajo es el diagnóstico
> original y se conserva como evidencia de lo que estaba roto.

`GoodsReceiptService#getAllReceipts` no tiene escalón por rol: cualquier no-admin se filtra por
`company_store_ids` y un conjunto vacío devuelve página vacía. Consecuencia medida: `lc-company`,
`lc-company-read`, `lc-company-country-read`, `lc-company-region-read` y `lc-company-zone-read` reciben
200 con cero filas. El arreglo es escalonar el filtro por el alcance más amplio otorgado, igual que
los cuatro servicios hermanos, y con él se puede reabrir la anotación de las dos lecturas. Es un cambio
de comportamiento de una consulta con rationale de tenancy (`W2-D10`), así que pide su propio registro
y su propia verificación.

**L7** — 2026-02-21 · **T4 entregado** como `6e80b3c`: **45 archivos, +307/−96** sobre los bytes
commiteados (+280/−76 antes de que `lint-staged` corriera `eslint --fix` y `prettier --write`, que
reformatearon 47 líneas). Los doce consumidores del primer combo pasan a `getCompanySelector(0, 1000)`:
las ocho pantallas de countries/regions/zones/stores (listado y edición), `store-cascade-page.ts:106`,
el cascade de purchase orders (`company-cascade.service.ts:107`, que además conserva su búsqueda
pasando `term`), las cuatro pantallas de HR que pedían la lista entera para un `<mat-select>`, y el
tipo del input de `stores-form` (`Company[]` → `CompanySelectorOption[]`).

- **El writer delegado murió a mitad** y no dejó veredicto: sí dejó los edits aplicados, un RED real
  y dos corridas verdes aisladas (63 y 149 tests), y dos `bash` sin resultado al final de su
transcript. El padre re-corrió la suite completa en vez de confiar en esas dos cifras.
- **Veredicto de la suite completa: 184 archivos, 3664 tests, 0 fallas.** Los 184 archivos son
  exactamente los `src/**/*.spec.ts` del árbol, o sea que el pin nuevo de T5 quedó incluido. La cifra
  está medida **dos veces**: sobre el árbol previo al commit y **de nuevo sobre los bytes
  commiteados** (los que dejó `lint-staged`), con `npm run lint` y `npm run build` también verdes en
  las dos rondas — `TEST_EXIT=0`, `LINT_EXIT=0`, `BUILD_EXIT=0`.
- **Dos trampas de medición, ambas pagadas en esta sesión.** (1) `npm test 2>&1 | tail -40` devuelve el
  exit code de `tail`, no el de vitest: la primera corrida "salió 0" sin decir nada. La medición válida
  es `set -o pipefail` (o redirección a archivo) con el log completo. (2) `npx vitest run <archivo>`
  **no** resuelve los aliases: `companies.routes.ts:2` importa `@core/guards/auth-keycloak-guard` y la
  corrida aislada muere con `Cannot find package` y "no tests" — porque el alias lo aporta la config
  que genera `ng test`, no el `vitest.config` del proyecto. La invocación aislada correcta es
  `npx ng test --include='<spec>'` (medido: 2/2 en el pin de T5).
- **Consecuencia declarada de T4:** `employee-detail` derivaba el `emailDomain` de la empresa
  seleccionada de la lista que ya no lee (el selector proyecta sólo `id`/`companyKey`/`companyName`),
  así que ahora hace **una lectura id-addressed por selección** (`getCompanyById` dentro de un
  `rxResource`) en vez de reusar una lista de mil filas. Una lectura fallida deja el dominio
  **sin resolver**, no ausente, y por eso `apply`/`resend` siguen visibles — la misma semántica que
  T68/T85 ya tenían ("sin resolver no dispara el gate"), ahora pinnada con dos tests. La alternativa
  —extender la proyección del selector con `emailDomain`— se descartó porque ensancha el contrato de
  las doce pantallas, que es justo lo que S2 evita.

**L8** — 2026-02-21 · **T5 entregado** como `2e0e5d0` (`companies.routes.spec.ts`, 45 líneas): el pin
de S3 en su mitad de rutas. Dos casos, sobre `companyRoutes` recorrido en profundidad: ninguna ruta
del feature declara `lc-company-read` en `data.roles`, y el wrapper del CRUD conserva exactamente
`['lc-admin','lc-company']`. El segundo caso camina **hacia el wrapper** en vez de buscar cada ruta
llamada `create`, porque las hijas del CRUD no declaran roles propios — la primera versión del test
matcheaba por nombre y por eso la aserción se ancla al ancestro que sí tiene el gate. La mitad del
menú ya estaba pinnada en `header.spec.ts`.

**Frontera de slice, medida después de los dos slices.** `1db82e6..18faf8c` acumula **922 líneas
autoradas en 33 archivos** y 7 commits (registro, T1, T2, su evidencia, T3, la evidencia de T3 y el
fix de spotless) y `18faf8c..f28a792` otras **413 en 47 archivos** y 3 commits (T4, T5 y su
evidencia): **1.335 líneas de diff en 10 commits**. Los **dos** slices están por encima del
presupuesto de revisión de ~400 líneas, así que se publicaron como **PRs encadenados** — **slice 1 =
`1db82e6..18faf8c`** (backend completo de S1/S2/S3 en la API, con la evidencia de sus tres tasks
adentro) y **slice 2 = `18faf8c..f28a792`** (migración del frontend, su pin y la evidencia de
ambos)—, nunca un PR de 1.300 líneas. La evidencia de cada task viaja **en el mismo slice que la
entrega**, que es la razón por la que la frontera se movió de `5adf82d` a `721a45f` y después a
`18faf8c`: el commit de evidencia de T3 y el fix de formato de su test pertenecen al slice que
entrega T3.

**L9** — 2026-02-21 · **Entregado**: issue **#473** (`enhancement` + `status:approved`, con problema,
resultado esperado, criterios verificables y non-goals) y **dos PRs encadenados** — **#474** (base
`main`, `Closes #473`, 33 archivos, +922/−39) y **#475** (base `feat/hierarchy-read-roles-backend`,
`Refs #473`, 47 archivos, +413/−107). Ramas: `feat/hierarchy-read-roles-backend` @ `18faf8c` y
`feat/hierarchy-read-roles-frontend` @ `4b6513a` (con un merge del padre).

- **El gate de spotless falló sobre los bytes commiteados de T3** y no fue cosmético: `spotlessJavaCheck`
  rechazaba `CompanyStoreFlatControllerSecurityTest`. `18faf8c` aplica el formateador y la corrida
  medida después es `BUILD SUCCESSFUL in 47s` — 148 clases / **762 tests / 0 fallas / 0 errores**, con
  `spotbugsMain` en la misma invocación. Esa es la evidencia del PR #474; la afirmación vieja de L6
  quedó corregida arriba.
- **`Refs #473` en #475 es obligatorio, no una elección**: el gate acepta `Refs` sólo fuera de la rama
  default y exige keyword de cierre en ella, así que al retargetear #475 a `main` después del merge del
  padre el body tiene que pasar a `Closes #473` o el job falla. El encabezado del PR lo dice.
- **Residuo declarado**: la rama remota `feat/hierarchy-read-roles` quedó apuntando al pre-rebase
  `aea74ee`. La política de seguridad bloqueó el force-push, así que el slice 2 se publicó bajo
  `feat/hierarchy-read-roles-frontend`; esa ref vieja hay que borrarla al cerrar el ciclo.

**L10** — 2026-02-21 · **La CI rechazó #474 y el defecto era real: el slice 1 viajó sin los pins de
T1.** El job `Test` de #474 falló con **3621 tests / 2 fallas**, y las dos son las aserciones que T1
tenía que haber movido: `CurrentUserContextTest > ScopeLevel.COMPANY lists exactly the company roles`
y `PositionRoleServiceTest > getGrantableRoles > returns the frozen allowlist sorted`.

- **Causa, medida**: la sesión anterior dejó esos dos archivos modificados y sin commitear. Al armar el
  commit de T4 se usó `git add -u`, que se lleva **todo el árbol**, así que los dos pins de T1 —ajenos
  al slice del frontend— viajaron dentro del commit del frontend y el backend quedó con el
  ensanchamiento **sin sus pins**. Es un error mío de staging, no del writer: `git add -u` en un
  worktree con cambios de otra sesión arrastra lo que no corresponde; el staging va por superficie.
- **Arreglo sin reescribir historia**: `5de5d75` mete los dos pins en la rama del backend, en el slice
  al que pertenecen, con el comentario que explica por qué el número de la allowlist es dieciséis;
  `4b6513a` mergea el padre en la rama del frontend, así el diff de #475 contra su base ya no
  arrastra ningún test de Java (**45 archivos, +432/−105**) y el caso queda visible en la historia en
  vez de esconderse tras un rebase. El force-push está bloqueado por política, así que el merge es la
  ruta no destructiva.
- **Verificación antes de volver a la CI**: suite **completa** de la API en local sobre el slice del
  backend — `BUILD SUCCESSFUL in 2m 26s`, **3621 tests / 0 fallas / 0 errores**, que es exactamente
  el conteo que la CI corre.
- **Rangos finales**: slice 1 = `1db82e6..5de5d75` (**35 archivos, +928/−42**, 8 commits), slice 2 =
  `5de5d75..` (**45 archivos, +432/−105**, 5 commits contando el merge y esta evidencia).

**L11** — 2026-10-20 · **Merge del slice backend.** PR **#474** mergeado como **`7ab89e90`**
(`--merge`, nunca squash) con los 9 checks verdes, incluido `Test` con 3621 tests. El merge cerró el
issue #473 por el `Closes` del body, y el issue **se reabre a mano antes del merge del hijo**: el gate
`check-issue-reference.sh` exige un issue **abierto** con `status:approved` para cada PR de la cadena,
así que el cierre temprano del padre deja al hijo sin referencia válida. Al escribir esta fila, #475
sigue abierto con su base en la rama del padre: falta retargetearlo a `main` y cambiar el body de
`Refs #473` a `Closes #473`, porque `Refs` se acepta **sólo fuera de la rama default** (el script lo
dice en `:334-336`).
