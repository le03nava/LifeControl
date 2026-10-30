# ODD feature: receipt-location-filter

**Repository**: LifeControl — `life-control-api/` y `life-control-app-angular/`
**Status**: implemented — la cascada de ubicación de `/purchases/receipts` está escrita en las dos capas (cinco parámetros opcionales en `GET /api/goods-receipts` intersectados con el alcance del token, componente compartido con `/purchases/orders`, compuerta de la página) con sus tests. Nada queda diferido.
**Created**: 2026-10-25 · **Risk**: alto — item 2 (superficie de autorización): el listado de recibos deriva su alcance de los claims del token y este cambio le suma un filtro que el caller elige, así que la consulta tiene que acotar dentro de ese alcance y nunca ampliarlo (`S3`).

## Specs

**S1 — los cinco filtros de la jerarquía de tienda existen en `/purchases/receipts`, en ese orden y como en `/purchases/orders`.**
Pedido del usuario, verbatim:
`en /purchases/receipts le quiero agregar los filtros de tienda y sus jerarquia asi como esta  en /purchases/orders`
Los rótulos visibles son exactamente `Empresa`, `País`, `Región`, `Zona`, `Tienda`, en ese orden, y
salen del **mismo componente** que usa `/purchases/orders`: es el único modo de que las dos pantallas no
se desincronicen. La preselección viene de `LocationPreference`, el mismo origen que
`/companies/stores` y que el filtro de órdenes de compra; cada nivel se selecciona **sólo si su id
aparece en las opciones de ese nivel** y un id que el nivel no lista queda vacío, nunca adivinado.

**S2 — Empresa, País y Región son obligatorios: sin ellos la página no consulta el listado y el buscador queda deshabilitado.**
Decisión del usuario, verbatim: `Obligatoria: compuerta igual que OC`, con el efecto que leyó:
`Sin empresa+país+región → aviso 'Elegí empresa, país y región para ver los recibos.' 0 requests, buscador deshabilitado.`
Mientras falte alguno de los tres niveles la página **no pide** recibos y muestra ese aviso exacto. El
cambio de comportamiento es deliberado y se declara: antes la página listaba lo que el alcance del
token permitía apenas se abría; ahora un usuario cuyo perfil no nombra empresa, país o región tiene que
elegirlos. Zona y Tienda son opcionales y acotan dentro de la región: `Zona` se habilita con la región
elegida y `Tienda` con la zona elegida.

**S3 — el filtro se aplica en la API y acota **dentro** del alcance del token, nunca lo amplía.**
`GET /api/goods-receipts` acepta cinco parámetros **opcionales** de tipo UUID — `companyId`,
`companyCountryId`, `regionId`, `zoneId`, `companyStoreId` — combinados con `AND` entre sí, con el
`search` existente y con el `enabled = true` que ya tenía. El orden (`receivedAt DESC`) y la paginación
no cambian. Un caller no-admin cuyo alcance efectivo es `S` y que pasa un filtro que resuelve a `F` ve
`S ∩ F`: un id fuera de `S` produce **página vacía**, jamás la fila. Un admin (`scope.unscoped()`) ve
`F`. La intersección es la regla entera: el filtro **no reemplaza** al alcance, que es cómo un parámetro
de consulta se convertiría en un bypass de tenencia.

**S4 — sin parámetros la respuesta es la de hoy.**
Un filtro ausente no acota nada: cinco ids nulos y `search` nulo devuelven exactamente la página que el
endpoint devuelve hoy, mismas filas, mismo `receivedAt DESC`, mismos totales, para admin y no-admin.

**S5 — la página elegida sobrevive al render; sólo cambiar los filtros vuelve a la página 0.**
Paginar mantiene la página; cambiar `search` o cualquier nivel de la ubicación vuelve a la 0. Es la
regla que `S7` de `odd/tasks/purchase-order-region-filter.md` fijó para órdenes, con el mismo
`untracked` sobre `pageIndex()` — sin él el efecto se auto-dispara y cada cambio de página vuelve a 0.

**S6 — `/purchases/orders` sigue funcionando igual después de compartir el componente.**
El componente de cascada pasa de `purchase-orders/components/location-filter` a
`purchases/components/location-filter` porque ahora sirve a las dos listas del módulo, y con él su
modelo (`PurchaseLocationSelection`). El comportamiento del filtro de órdenes no cambia: mismos cinco
niveles, misma preselección, misma compuerta, mismos query params.

## Tasks

- **T1** (S3, S4) `life-control-api`: `CompanyStoreRepository#findIdsByHierarchyFilter` (los cinco
  niveles opcionales sobre la ruta `store → zone → region → country → company`),
  `CompanyStoreFilterResolver` que resuelve el filtro a ids de tienda, `GoodsReceiptFilter` (record de
  los cinco ids, con `NONE`), `GoodsReceiptService#getAllReceipts` intersectando alcance y filtro, y los
  cinco `@RequestParam(required = false) UUID` en `GoodsReceiptController#getAllReceipts`.
  Commit: `feat(api): filter goods receipts by the store hierarchy`.
- **T2** (S3, S4) tests de la API: unitarios de servicio (filtro intersecta, id fuera de alcance da
  página vacía, admin filtra sin alcance, sin filtro no cambia nada, alcance vacío sigue vacío),
  unitario de controller (los cinco params llegan al servicio; sin params llega `NONE`) y el del
  resolver. Commit: el mismo que T1.
- **T3** (S1, S6) `life-control-app-angular`: mover la cascada a `purchases/components/location-filter`
  como `PurchaseLocationFilter` (`app-purchase-location-filter`) con
  `PurchaseLocationSelection`, actualizando `PurchaseOrderList` y los specs. Commit:
  `refactor(web): share the location filter between both purchase lists`.
- **T4** (S1, S2) `GoodsReceiptService#getReceipts` acepta los cinco filtros opcionales además de
  `search`, con spec de los query params exactos; `ReceiptList` queda inerte sin empresa, país y región,
  muestra el aviso de `S2`, deshabilita el buscador y respeta `S5`. Commit:
  `feat(web): add the store hierarchy filter to the receipt list`.
- **T5** gates: `./gradlew spotlessCheck spotbugsMain test` (API) y `npm run lint`, `npm run build`,
  `npm run test:coverage:check` (Angular). Sin commit propio.

### Delivery

Un solo corte no entra en el presupuesto de ~400 líneas autoradas de `CONTRIBUTING.md` §3: son tres
unidades entregables (API, componente compartido, página) y cada PR se lee solo. Slice 1 = registro +
T1 + T2; slice 2 = T3; slice 3 = T4. Nada de esto autoriza push, apertura de PR ni merge: son decisión
del usuario.

## Log

**L1 — pedido original, verbatim**
`en /purchases/receipts le quiero agregar los filtros de tienda y sus jerarquia asi como esta  en /purchases/orders`

**L2 — decisión del usuario sobre la compuerta, verbatim**
Pregunta: *En /purchases/receipts, ¿la cascada de ubicación debe ser obligatoria (compuerta como en
/purchases/orders) o acotar opcionalmente lo que el token ya permite ver?*
Respuesta elegida: `Obligatoria: compuerta igual que OC` — *Sin empresa+país+región → aviso 'Elegí
empresa, país y región para ver los recibos.' 0 requests, buscador deshabilitado.*

**L3 — medido durante la exploración: la API de recibos no tenía filtros y sí tenía alcance**
`GET /api/goods-receipts` recibía sólo `search`, y `GoodsReceiptService#getAllReceipts` deriva su
alcance de `CompanyStoreScopeResolver` (el `ScopeLevel` más amplio otorgado, `odd/tasks/goods-receipt-scope.md`).
`GET /api/purchase-orders`, en cambio, recibe los cinco filtros y **no** deriva alcance del token
(`odd/tasks/purchase-order-region-filter.md`, `S6` y `L4`). Copiar los parámetros sin más habría
duplicado el contrato pero no la autorización, así que el filtro se resuelve a ids de tienda y se
intersecta con el alcance (`S3`).

**L4 — por qué el filtro se resuelve a tiendas en vez de sumar predicados a la consulta de recibos**
`GoodsReceiptRepository` ya tiene el par de finders con alcance (`storeIds IN :storeIds`) y el par sin
alcance, y esa forma existe porque PostgreSQL no puede tipar un predicado `:search IS NULL` con el
parámetro nulo (medido en `purchase-order-region-filter.md`, `L8`). Sumar cinco predicados de
jerarquía habría multiplicado esa matriz por dos. Resolver el filtro a `Set<UUID>` de tiendas deja la
consulta de recibos **intacta** y la intersección ocurre antes de consultar, donde se puede probar sin
base de datos.

**L6 — medido en el E2E: el mock no servía ni el perfil ni la jerarquía, y la primera corrida dio un falso verde.**
`e2e/specs/purchases-receipts.spec.ts` afirma `No hay recibos registrados` apenas abre `/purchases/receipts`,
así que la compuerta de `S2` la rompe en cuanto la cascada no resuelve. Dos defectos medidos, en este orden:

1. La primera corrida del spec salió **3 passed** sin haber ejercitado este árbol: `playwright.config.ts`
   declara `reuseExistingServer: !CI` y algo escuchaba en `:4200`, así que Playwright reusó un dev server
   ajeno al worktree. Es el modo de falla que el número verde esconde: un `passed` que no ejecutó el cambio.
   Repetida con `E2E_PORT=4311` (sin listener, server propio), falló donde tenía que fallar.
2. El mock de E2E no conocía lo que la compuerta necesita. `LocationPreference` resuelve
   `profile.location` y el `SAMPLE_PROFILE` del mock sólo traía los ids planos, sin ese objeto; y los cuatro
   reads de la cascada (`/companies/selector`, `/countries`, `/regions`, `/zones`, `/stores`) no estaban
   mockeados en absoluto (`company-scope` de `purchase-order-region-filter.md`, `L7`, lo había dejado como
   trabajo aparte porque ninguna spec E2E abría una lista con la compuerta). Ahora los dos están en
   `e2e/mocks/api.ts`: `location` en el perfil y la cadena `company-1 → company-country-1 → region-1 →
   zone-1 → store-1` en los cinco endpoints. Con eso el spec pasa **3 passed** sobre un server propio.
   Efecto lateral deliberado: la cascada de `/purchases/orders` también queda preseleccionable en E2E.

**L7 — decisiones que el registro no fijaba, tomadas y declaradas.**
(a) La compuerta reemplaza al esqueleto de carga, igual que en órdenes: una página sin ubicación no tiene
nada que esté cargando. (b) La compuerta lleva una segunda línea, `La zona y la tienda son opcionales:
acotan el listado dentro de la región elegida.`, tomada de la página de órdenes; el registro sólo fijaba
el título. (c) `getReceipts` toma el tercer parámetro como objeto con default `{}` (no obligatorio), así
un caller que no pasa nada sigue compilando y `{}` manda sólo `page`/`size`. (d) El caso de spec
`should show loading skeleton initially (before first detectChanges)` se reemplazó por dos: no hay carga
mientras falta ubicación, y hay esqueleto con la lectura en vuelo — la aserción anterior dejó de ser
cierta por diseño de `S2`.

**L5 — issue**
Issue **#686** (`feat(web): add the store hierarchy filter to /purchases/receipts`), abierto con los
criterios de aceptación. Queda pendiente de `status:approved` de un maintainer antes del PR: la
aprobación no la aplica quien escribe.
