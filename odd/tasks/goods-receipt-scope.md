# ODD feature: goods-receipt-scope

**Repository**: LifeControl — `life-control-api/`
**Status**: open — arregla el gap **G1** de `odd/tasks/hierarchy-read-roles.md` y está implementado y verificado en la rama `fix/goods-receipt-scope` (`9551c4c`, `5c1aa57`). Trabajo restante de este registro: la entrega (issue aprobado + PR), que es decisión del mantenedor. Riesgo: alto — superficie de autorización y rationale de tenancy (`W2-D10`).
**Created**: 2026-10-20 · **Risk**: alto — superficie de autorización (item 2).

## Objective

Que `GET /api/goods-receipts` devuelva exactamente los comprobantes que el **detalle**
(`GET /api/goods-receipts/{id}`) ya autoriza, para todo alcance de la jerarquía
compañía → tienda, y que las dos lecturas admitan los roles de lectura de los niveles ancestros.

## Specs

**S1 — el listado honra el alcance, y no responde 200 con página vacía a quien tiene alcance.**
Gap G1, verbatim del registro que lo abrió (`odd/tasks/hierarchy-read-roles.md`):
`GoodsReceiptService#getAllReceipts` no tiene escalón por rol: cualquier no-admin se filtra por
`company_store_ids` y un conjunto vacío devuelve página vacía. Consecuencia medida allí: `lc-company`,
`lc-company-read`, `lc-company-country-read`, `lc-company-region-read` y `lc-company-zone-read`
reciben 200 con cero filas.

El arreglo, verbatim de la misma fila: `El arreglo es escalonar el filtro por el alcance más amplio
otorgado, igual que los cuatro servicios hermanos, y con él se puede reabrir la anotación de las dos
lecturas.`

**S2 — el alcance efectivo se deriva del `ScopeLevel` más amplio otorgado, con la misma regla que el
detalle.** Pedido del usuario, verbatim: `arregla G1`. El alcance efectivo es el de
`CurrentUserContext#broadestGrantedScope`: COMPANY → todas las tiendas de los `company_id` del claim;
COUNTRY → las de los `company_country_id`; REGION → las de los `company_region_id`; ZONE → las de los
`company_zone_id`; STORE → los `company_store_ids`. Es la misma regla que
`verifyCompanyStoreAccess` aplica al detalle, así que lista y detalle no pueden discrepar.

**S3 — las dos lecturas admiten los `-read` de los ancestros; las escrituras no cambian.**
`GET /api/goods-receipts` y `GET /api/goods-receipts/{id}` suman `lc-company-read`,
`lc-company-country-read`, `lc-company-region-read` y `lc-company-zone-read` a lo que ya admitían
(los roles de escritura de todos los niveles, `lc-company-store-read` y `lc-receiving`). El `POST`
queda **byte-idéntico**: registrar un comprobante sigue siendo escritura.

**S4 — el cambio de comportamiento es deliberado y se declara.** Un portador de `lc-company`,
`lc-company-country`, `lc-company-region` o `lc-company-zone` que hoy recibe **200 con página vacía**
pasa a ver los comprobantes de su alcance. Un alcance efectivo **vacío** sigue devolviendo página
vacía y nunca cae a la consulta sin filtro; un admin sigue viendo la página sin alcance.

## Tasks

| ID | Task | Spec | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | Proyección de ids de tienda por compañía, país, región y zona en `CompanyStoreRepository`, y `CompanyStoreScopeResolver` que traduce el alcance más amplio otorgado a un conjunto de tiendas | S1, S2 | inline | `9551c4c` |
| T2 | `GoodsReceiptService#getAllReceipts` filtra por el conjunto del resolver en vez de por `company_store_ids` crudo | S1, S2 | inline | `9551c4c` |
| T3 | Las dos lecturas suman los `-read` de los ancestros, con su matriz de seguridad y el `POST` pinneado sin cambios | S3 | inline | `5c1aa57` |
| T4 | Evidencia: rojo antes de verde, suite completa, spotless y spotbugs; y la fila de entrega | S1-S4 | inline | este commit |

**Por qué todo inline**: una sola unidad de comportamiento en un módulo, con dependencia directa
entre T1 → T2 → T3; dos writers en el mismo worktree competirían por el mismo build.

## Log

**L1** — Pedido del usuario, verbatim: `arregla G1`.

**L2** — 2026-10-20 · Medición que funda las specs, leída del árbol. `GoodsReceiptService:338-356`
filtra a todo no-admin por `currentUserContext.getCompanyStoreIds()` y devuelve `Page.empty` cuando
ese conjunto está vacío; `GoodsReceiptController:76-83` admite en el listado los roles de escritura
de los cinco niveles, `lc-company-store-read` y `lc-receiving`, y **ningún** `-read` de ancestro.
`CurrentUserContext:334` ya tiene `broadestGrantedScope(ScopeLevel)` —privado—, que es exactamente la
regla que `verifyCompanyStoreAccess` usa para el detalle, y `ScopeLevel.companyTo` la hace pública
en forma de orden. `CompanyStore` no tiene columnas de país/región/compañía: cuelga de
`companyZone → companyRegion → companyCountry → company`, así que resolver tiendas por alcance pide
JPQL con joins y proyección de ids.

**L3** — 2026-10-20 · **Implementado y verificado, con el rojo medido antes de la lógica.**

- **RED**: 41 tests, **18 fallas**, escritas antes de que existiera la derivación de alcance — las cinco
  derivaciones del resolver (COMPANY, COUNTRY, REGION, ZONE, STORE), los cuatro casos de scoping del
  listado y los ocho casos nuevos de roles de ancestro en las dos lecturas. El primer `compileTestJava`
  falló por el constructor nuevo, que también es rojo pero no prueba nada: el rojo que vale es el de
  aserción, y es el que se midió después.
- **GREEN**: las tres clases enfocadas en verde, y después el gate completo del módulo —
  `spotlessCheck spotbugsMain test` → **`BUILD SUCCESSFUL in 2m 34s`, 3639 tests / 0 fallas / 0
  errores** (los 3621 previos más los 18 nuevos). `spotlessApply` se corrió **antes** del gate, así
  que los bytes verificados son los commiteados.
- **La decisión de diseño que el registro tiene que dejar escrita**: el listado y el detalle aplican
  ahora **la misma regla** (`broadestGrantedScope`), así que no pueden discrepar. Se descartaron dos
  alternativas: filtrar siempre por `company_store_ids` (es el defecto: 200-vacío para todo alcance
  más ancho) y filtrar sólo cuando el portador tiene el rol angosto y si no dejar pasar sin filtro
  (lo que hacen los cuatro servicios hermanos, e imposible acá porque un listado plano sin `company`
  en el path no tiene contra qué verificar antes de filtrar).
- **Cambio de comportamiento declarado (S4)**: `lc-company`, `lc-company-country`, `lc-company-region`
  y `lc-company-zone` pasan de **200 con página vacía** a ver los comprobantes de su alcance, y los
  cuatro `-read` de esos niveles pasan de **403** a 200 con la misma página. Un alcance efectivo vacío
  sigue devolviendo página vacía sin tocar la consulta sin filtro, y el `POST` no cambia para nadie.

**L4** — 2026-10-20 · **La verificación independiente encontró un bloqueante real en S2**, sobre un
candidato que la review nativa ya había aprobado.

- **Lo que dijo el verificador, medido con sus propias sondas**: `CompanyStoreScopeResolver`
  resolvía el alcance desde el claim del nivel más profundo **sólo**, mientras el detalle verifica
  toda la cadena (`verifyLevelsUpTo`, con `company_id` y `company_country_id` obligatorios).
  Reprodujo dos divergencias en la dirección insegura: un lector de país con `company_id` de otra
  compañía y un lector de región sin claims de ancestros recibían la fila en la lista y
  `AccessDeniedException` al abrirla. **Sobre `origin/main` la misma sonda devolvía página vacía**:
  el defecto lo introdujo el arreglo, no venía de antes.
- **Corrección (`3dd61a1`)**: las cinco proyecciones pasan a recibir **todas** las constraints de
  ancestro y el resolver niega sin consultar cuando falta **cualquier claim de la cadena** —no
  «cuando falta un claim requerido»: `isRequired()` no decide acá, la guarda exige los cinco niveles
  que el alcance alcanza—. Un portador de tienda también queda constreñido por la cadena, así que el
  filtro de la lista y el del detalle son literalmente la misma regla en vez de dos que coinciden por
  casualidad.
- **Evidencia (`9bdfdb2`)**: `GoodsReceiptScopeAgreementTest` corre `Jwt` real → `CurrentUserContext`
  real → resolver real → servicio, y cubre los dos casos que divergían más los controles de tienda y
  de admin; `CompanyStoreScopeProjectionTest` corre las proyecciones **contra una base** y fija que
  un ancestro que no cubre la fila la excluye, que una tienda deshabilitada sigue alcanzable por id y
  que nada se cruza entre compañías. **Corrección (L5): cuatro de las cinco proyecciones tenían pin
  negativo, no las cinco** — el de `findIdsByStoreIdIn` faltaba y se agregó después.
- **Fallas propias, declaradas**: el primer resolver test verificaba `never()` sobre el mismo método
  que sí se invoca — tres fallas del test, no del código (`3655 tests / 3 fallas`). Se corrigió a
  verificación de argumentos exactos, que es justamente lo que hace visible la constraint de
  ancestro.
- **Advisorys cerrados**: la descripción de Swagger de la lista seguía prometiendo
  `company_store_ids`; el `POST` tenía pins para dos de los cuatro roles de lectura nuevos y ahora
  los tiene para los cuatro; el chequeo `claimIds == null` era código muerto y se fue con la
  reescritura.
- **Advisory declarado y no arreglado**: un portador de tienda que además tenga un rol de nivel
  compañía (`lc-position`, `lc-department`) se amplía a toda la compañía. Es consistente con el
  detalle, que ya autorizaba así, pero queda escrito acá en vez de descubrirse después.
- **Gate del candidato corregido**: `spotlessCheck spotbugsMain test` → **`BUILD SUCCESSFUL in 2m
  25s`, 3655 tests / 0 fallas / 0 errores**.
- **Sobre las reviews**: el recibo aprobado (`review-927ba805be3b24ff`, 1 advisory R3-001 sobre las
  queries) cubre el candidato de 556 líneas y se reconoció **antes** de tocar el código; el candidato
  corregido se revisa aparte, porque un recibo no se hereda.

**L5** — 2026-10-20 · **Re-verificación independiente del candidato corregido: sin bloqueantes.**

- **El bloqueante quedó arreglado a nivel de query**, no de mock: el verificador corrió el JPQL real
  contra la base y comparó, para **nueve formas de token**, `list ∋ SA ⇔ detailAdmits(SA)`; las dos
  que antes divergían (país con compañía extranjera, región sin ancestros) ahora coinciden, y las
  siete restantes también. Sin bleed entre compañías.
- **El camino de tienda no regresó y además se endureció**: un portador de tienda con la cadena
  completa sigue leyendo su tienda; si le falta un ancestro, la lista queda vacía **y el detalle
  niega** — coinciden. Respecto de `66c3555` (donde STORE usaba sólo el claim), una tienda cuyo claim
  contradice a un ancestro ahora queda oculta, que es lo que el detalle ya hacía.
- **A1 cerrado**: quitarle los predicados de ancestro a `findIdsByStoreIdIn` dejaba toda la suite en
  verde (la mutación no la mataba nadie). `CompanyStoreScopeProjectionTest` gana los dos casos
  negativos del finder de tienda — tienda correcta con zona ajena, y tienda correcta con compañía
  ajena —, así que la mutación ahora falla.
- **A2 declarado, no arreglado (preexistente en `main`)**: la lista filtra `enabled = true`
  (`GoodsReceiptService:366-368`) mientras el detalle (`findById`) admite un comprobante
  deshabilitado, así que un comprobante deshabilitado es invisible en la lista y abrible por id. Es
  la misma asimetría que en `origin/main`: no la introdujo este arreglo, y corregirla cambia lo que ve
  cualquier portador, así que pide su propio registro en vez de colarse acá.
- **A3 cerrado**: el `claim == null` de `allows()` era inalcanzable (los getters de claim devuelven
  siempre un set inmutable, vacío si el token no trae nada); se eliminó y el guard queda en
  `isEmpty()`.
- **Test durable sumado**: `CompanyStoreScopeAgreementTest` corre el JPQL real y resuelve el alcance
  desde un `Jwt` real, comparando forma por forma con el veredicto del detalle. Es el único test que
  rompe si un finder pierde un predicado de ancestro — los de repositorio mockeado no pueden verlo.
