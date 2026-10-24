# ODD feature: purchase-order-region-filter

**Repository**: LifeControl — `life-control-api/` y `life-control-app-angular/`
**Status**: implemented — el filtro jerárquico de `/purchases/orders` está escrito en las dos capas (consulta filtrada en la API, componente de cascada con preselección desde el perfil, compuerta de la página) con sus tests. Nada queda diferido.
**Created**: 2026-10-24 · **Risk**: ninguno — sólo se agregan parámetros de consulta opcionales y filtros de UI; no hay escrituras, ni cambios de guard, ni de contrato consumido por terceros (ver `S6`).

## Specs

**S1 — los cinco filtros existen, en ese orden, y aparecen preseleccionados con las preferencias.**
Pedido del usuario, verbatim:
`quiero agregar los filtros de Empresa, Emprea Pais, Region, Zona, Tienda en /purchases/orders los cuales aparezcan preseleccionados con las preferencias como en /companies/stores`.
Los rótulos visibles son exactamente `Empresa`, `País`, `Región`, `Zona`, `Tienda`. La preselección
usa `LocationPreference` (la preferencia de ubicación del perfil), el mismo origen que
`/companies/stores`: la cadena es todo-o-nada — si el perfil nombra una tienda, sus ancestros vienen
de la preferencia, nunca se combinan niveles de dos fuentes. Cada nivel se selecciona **sólo si su id
aparece en las opciones de ese nivel**; un id que el nivel no tiene no se adivina y queda vacío. La
página no lee ids de query params: no tiene enlaces entrantes que los aporten.

**S2 — sólo Empresa, País y Región son obligatorios; con la región elegida se ven las órdenes de esa región.**
Verbatim del usuario:
`y solo  Empresa, Emprea Pais, Region son obligatorios, desde que se filtre la region se deben de ver las ordenes de compra de la region`.
Mientras falte alguno de los tres, la página **no consulta** el listado y muestra el aviso
`Elegí empresa, país y región para ver las órdenes de compra.`; el buscador queda deshabilitado. Con los
tres elegidos, el listado muestra las órdenes de compra de esa región (no las de todo el sistema).

**S3 — Zona y Tienda son opcionales y en cascada.**
Decisión del usuario, verbatim: `Después de elegir Zona (cascada, como el editor de OC)` — *Región
elegida → lista las órdenes de la región; Zona opcional → acota a la zona; Tienda opcional → acota a la
tienda (requiere zona)*. `Zona` se habilita con la región elegida; `Tienda` se habilita con la zona
elegida. Cambiar un nivel limpia todos los niveles por debajo.

**S4 — el filtro se aplica en la API sobre el listado paginado, no en el cliente.**
`GET /api/purchase-orders` acepta cinco parámetros **opcionales** de tipo UUID — `companyId`,
`companyCountryId`, `regionId`, `zoneId`, `companyStoreId` — combinados con `AND` entre sí, con el
`search` existente y con el `enabled = true` que ya tenía. El orden (`createdAt DESC`) y la paginación
no cambian. Un parámetro ausente no acota nada.

**S5 — el `search` conserva su comportamiento exacto y ahora se combina con los filtros.**
Sigue matcheando `supplier.supplierName` y `companyStore.storeName`, case-insensitive, con
`LIKE '%…%'`, sobre las órdenes habilitadas. Nota medida: el placeholder de la pantalla promete
"número de orden o proveedor" y la consulta nunca miró el número de orden; es una discrepancia previa
que este registro **no** cambia.

**S6 — la API no aplica alcance por tenencia y este cambio no lo introduce.**
El listado es `isAuthenticated()` desde antes de este trabajo: los filtros son de UI y no son un guard.
Un filtro ausente devuelve lo mismo que hoy; un filtro presente acota. Que el listado no derive su
alcance del `ScopeLevel` como `goods-receipts` es una decisión de autorización **previa y fuera de
alcance** (ver `## Log`, `L4`).

**S7 — la página elegida sobrevive al render; sólo cambiar los filtros vuelve a la página 0.**
Medido en el código que este trabajo reescribe: el efecto que resetea a la página 0 leía
`pageIndex()` en modo *rastreado*, así que se auto-disparaba y **cada cambio de página volvía a 0**
(revertir el `untracked` produce `expected +0 to be 1`, ver `## Log`, `L5`). Con el filtro nuevo la
regla es explícita: paginar mantiene la página; cambiar `search`, ubicación, zona o tienda vuelve a la 0.

## Tasks

- **T1** (S4, S5) `life-control-api`: `PurchaseOrderRepository` — una sola consulta JPQL con predicados
  opcionales reemplaza a `findBySearchTerm`; `PurchaseOrderService#getAllPurchaseOrders` la usa siempre;
  `PurchaseOrderController#getAllPurchaseOrders` recibe los cinco `@RequestParam(required = false) UUID`.
  Commit: `feat(api): filter purchase orders by the store hierarchy`.
- **T2** (S4, S5) tests de la API: integración contra PostgreSQL real (Testcontainers) probando que la
  región acota, que zona/tienda acotan más, que filtro + `search` combinan, que una orden deshabilitada
  no aparece nunca, y que sin filtros la página es la misma de antes; más los unitarios de service y
  controller. Commit: el mismo que T1.
- **T3** (S1, S4) `life-control-app-angular`: `PurchaseOrderService#getPurchaseOrders` acepta los cinco
  filtros opcionales además de `search`; el spec fija los query params exactos. Commit:
  `feat(web): add the purchase order location filter`.
- **T4** (S1, S3) componente `PurchaseOrderLocationFilter` (`app-purchase-order-location-filter`) con la
  cascada Empresa → País → Región → Zona → Tienda, los resets al cambiar de nivel y la preselección
  desde `LocationPreference`; contrato público exacto:
  `readonly selection = output<PurchaseOrderLocationSelection>()` con
  `{ companyId: string | null; companyCountryId: string | null; regionId: string | null; zoneId: string | null; companyStoreId: string | null }`,
  emitido en cada cambio y al resolverse la preselección; con su spec. Commit: el mismo que T3.
- **T5** (S2, S7) `PurchaseOrderList`: el `rxResource` del listado queda inerte sin los tres niveles, el
  aviso de S2 reemplaza al listado, el buscador se deshabilita, paginar mantiene la página y sólo cambiar
  los filtros vuelve a la 0; el spec se reescribe sobre eso (incluido el caso que prueba `S7` en RED).
  Commit: `feat(web): gate the purchase order list on company, country and region`.
- **T6** gates: `./gradlew spotlessCheck spotbugsMain test` (API) y `npm run lint`, `npm run build`,
  `npm run test:coverage:check` (Angular), más el chequeo diferencial de S5 sobre la consulta anterior.
  Sin commit propio.

### Delivery: cadena con rama tracker (`chain_strategy=feature-branch-chain`)

Elegido por el usuario el 2026-10-24, al pasar la rama las ~400 líneas autoradas del presupuesto de
`CONTRIBUTING.md` (§3). Un solo corte cohesivo no alcanza: ninguna slice entra en 400 sin separar los
tests del comportamiento que prueban, así que el corte es por unidad entregable y cada PR se lee solo.
Total: ~2.200 líneas autoradas en 26 archivos.

| Slice | Alcance | Commits | Depende de |
| --- | --- | --- | --- |
| 1 | Registro ODD + `GET /api/purchase-orders` con los cinco filtros y sus suites (unitaria e integración) | `docs(odd): record…`, `feat(api): filter…`, `test(api): pin the list filters…`, `test(api): pin the null-search…` | — (base `origin/main`) |
| 2 | Componente `PurchaseOrderLocationFilter` (cascada + preferencia) con su spec y sus modelos 📍 | `feat(web): add the location filter component…` | slice 1 |
| 3 | Página `/purchases/orders`: compuerta de S2, aviso, buscador deshabilitado, servicio con los params y sus dos callers | `feat(web): gate the purchase order list…` | slice 2 |

Nada de esto autoriza push, apertura de PR ni merge: la elección es de estrategia, y esos pasos siguen
siendo decisión del usuario. Fuera de alcance en las tres slices: alinear el listado al `ScopeLevel`
del token (`L4`), el mock E2E del listado (`L7`) y el placeholder del buscador que promete número de
orden (`S5`).

## Log

**L1 — pedido original, verbatim**
`quiero agregar los filtros de Empresa, Emprea Pais, Region, Zona, Tienda en /purchases/orders los cuales aparezcan preseleccionados con las preferencias como en /companies/stores, y solo  Empresa, Emprea Pais, Region son obligatorios, desde que se filtre la region se deben de ver las ordenes de compra de la region`

**L2 — decisión del usuario sobre el estado previo a la región**
Pregunta: *¿qué debe mostrar /purchases/orders antes de que haya Empresa, País y Región elegidos?*
Respuesta elegida: `No listar nada: aviso "elegí empresa, país y región"` — *filtros vacíos → panel de
aviso, 0 requests de órdenes; recién con región elegida se piden las órdenes de esa región; el buscador
queda deshabilitado hasta tener región*.

**L3 — decisión del usuario sobre el nivel Tienda**
Pregunta: *el filtro Tienda, ¿cuándo se habilita?* Respuesta elegida:
`Después de elegir Zona (cascada, como el editor de OC)` — reusa el endpoint actual de tiendas por zona;
sin endpoint nuevo de backend.

**L4 — hallazgo medido durante la exploración (no se cambia)**
`GET /api/purchase-orders` es `isAuthenticated()` y no deriva alcance del `ScopeLevel`, a diferencia de
`GET /api/goods-receipts` (`odd/tasks/goods-receipt-scope.md`, S2). Agregar filtros opcionales no cambia
la exposición: sin filtros la respuesta es la misma de hoy. Alinear el listado al alcance del token es
trabajo de autorización aparte, con su propio registro.

**L5 — defecto medido en el efecto de reset, arreglado en el mismo cambio**
El efecto que devolvía la lista a la página 0 leía `pageIndex()` rastreado, así que se auto-disparaba:
paginar no se sostenía (`expected +0 to be 1` al revertir el `untracked`, corrida del 2026-10-24 sobre
`purchase-order-list.spec.ts`). Es el mismo efecto que este trabajo reescribe para los filtros, y sin el
arreglo `S7` no se puede cumplir. El test `should keep the chosen page after change detection` lo fija.

**L6 — estado sin filtros alcanzable**
`Empresa` lleva una opción vacía (`— Seleccionar empresa —`) para poder volver al estado de `S2`
después de haber elegido una: sin ella el selector no tenía forma de limpiarse.

**L7 — medido, no cambiado: el mock E2E del listado no conoce los filtros**
`life-control-app-angular/e2e/mocks/api.ts#filterPurchaseOrders` sólo aplica `search` (y matchea
`orderNumber` + `supplierName`, mientras la API matchea proveedor + tienda). Ninguna spec E2E lista la
tabla de órdenes — `purchases-receipts.spec.ts` sólo usa `/purchases/orders` para afirmar el
`Access Denied` del guard —, así que no hay corrida que arreglar; alinear el mock a la API es trabajo
aparte si alguna vez una spec E2E abre el listado.

**L8 — el diferencial encontrado por el chequeo base-vs-rama: `search` ausente devolvía 500**
Chequeo diferencial (sonda HTTP temporal, jamás commiteada, copiada al commit base y a esta rama, mismo
fixture, salida canónica diffeada): la base respondía `absent | total=2` y la rama devolvía **HTTP 500**
con `ERROR: function lower(bytea) does not exist`. Causa: `('%'||:search||'%')` ligaba el nulo sin tipo
(el `cast` sólo cubría la rama `IS NULL`). Peor: el test `noFilters_returnTheSamePageAsBefore` daba
**falso verde** al correr la clase completa y fallaba corriéndolo solo — otro método ejecutaba antes la
consulta con `search` no nulo y eso tipaba el parámetro para el `SessionFactory`. Arreglo: tipar cada
aparición del parámetro más un test en un contexto Spring distinto, donde la primera ejecución de la
consulta es la del nulo. Es exactamente el modo de falla que el diferencial existe para encontrar y que
la suite del worker no podía ver.

**L9 — el `head` de un grep ocultó un caller**
`getPurchaseOrders` tenía dos callers; el `build` de Angular destapó `receipt-create.ts`, que seguía
pasando un `string` en la tercera posición. Corregido con su spec. Los gates ahora se corren con
`set -o pipefail`: la primera corrida reportó `exit 0` con el build roto porque el código de salida era
el de `tail`.

**L10 — evidencia de gates (2026-10-24)**
Angular, desde `life-control-app-angular`: `npm run lint` → *All files pass linting*; `npm run build` →
*Application bundle generation complete*; `npm run test:coverage:check` → statements 86.50 %, branches
86.00 %, functions 79.62 %, lines 89.25 %, los cuatro dentro de los umbrales, y las specs de la unidad
(servicio + componente + página) 53/53 en verde. API, desde `life-control-api`: `spotlessCheck`,
`spotbugsMain` y la suite completa. El chequeo diferencial base-vs-rama (L8) cierra con salida idéntica
en las nueve sondas (`absent`, paginado, `blank`, sin match, número de orden, nombre de tienda en dos
capitalizaciones, proveedor en dos capitalizaciones).
