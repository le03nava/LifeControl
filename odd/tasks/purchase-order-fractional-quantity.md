# ODD feature: purchase-order-fractional-quantity

**Repository**: LifeControl — `life-control-api/` (migración, entidad, DTOs, servicio y boundary de
recepción) + `life-control-app-angular/` (`detail-table`, form de recepción y sus specs).
**Status**: implemented — el contrato decimal de la cantidad de una línea de orden de compra y de su
recibida está escrito en la API (migración `V30`, entidad, DTOs con su validación, servicio y boundary de
recepción) y el ABM Angular deja de exigir enteros, con sus tests. Nada queda diferido.
**Created**: 2026-10-25 · **Branch**: `feat/purchase-order-fractional-quantity` · **Base**: `main` @
`eb9cb19` · **Worktree**: `~/workspace/LifeControl-worktrees/feat-purchase-order-fractional-quantity` ·
**Risk**: **items 1 y 3** — la migración cambia el tipo de dos columnas que ya tienen datos (formato
almacenado) y el contrato `PurchaseOrderDetailRequest/Response.quantity` pasa de `Integer` a decimal para
todos sus consumidores; no hay pérdida de datos porque todo entero convierte exacto a `DECIMAL(12,2)`.

## Objective

Cerrar la limitación que `product-variant-measure-unit` declaró como real y no como bug: con la unidad de
medida en el variante (V29), una variante en `Kgm` **se vende y se stockea en fracciones** —`sales_order_items`,
`inventory_movements`, `product_variant_locations.stock` y `product_variant_store_stock.stock` son
`DECIMAL(12,2)`— pero **no se puede pedir ni recibir** en fracciones, porque
`purchase_order_details.quantity` y `received_quantity` son `INTEGER` y el boundary de recepción rechaza
cualquier cantidad no entera (`W2-D7`). Esta unidad levanta ese borde en las dos capas.

## Specs

**S1 — el alcance es levantar `W2-D7`, y sólo eso.** Pedido del usuario, verbatim:
`continua con product-variant-measure-unit`
y su elección, verbatim, del diferido a tomar:
`Fracciones en OC y recepción (W2-D7 → W3)` — *La limitación que el registro nombra: una variante en Kgm se
vende y se stockea en fracciones pero no se puede pedir ni recibir en fracciones.*

La restricción que se levanta está escrita en `odd/tasks/purchase-order-goods-receipt.md:193`:
`Accepted cost: 2.5 kg cannot be received until W3 revisits the measure-unit question.`
`purchase_order_details.quantity` y `received_quantity` pasan a `DECIMAL(12,2)`; una línea de 2.5 kg se
crea, se edita y se recibe.

**S2 — lo que ya funcionaba sigue funcionando igual.** Una cantidad entera se comporta como antes en las
dos capas: crear y editar la línea, recibir, derivar `Partial Received` cuando lo recibido es menor que lo
pedido y `Received` cuando lo alcanza, y promover la cabecera `In Transit` a `Received` cuando todas sus
líneas habilitadas quedaron recibidas. `sales_order_items`, `inventory_movements` y los dos `stock` no se
tocan: ya eran `DECIMAL(12,2)`.

**S3 — validación de entrada, en la columna y en el servicio.** La cantidad de una línea es `> 0` y admite
hasta dos decimales —la escala de la columna—; `0`, negativa o con más de dos decimales se rechaza con
**400** sin escribir nada. La cantidad recibida por línea es `> 0`, con hasta dos decimales, y el acumulado
(`recibida_actual + esta`) no puede superar lo pedido; el rechazo sigue nombrando la línea.

**S4 — el boundary de recepción deja de exigir entereza.** El chequeo `remainder(BigDecimal.ONE) != 0` y
los `intValueExact()` de `GoodsReceiptService` desaparecen: la cantidad viaja como `BigDecimal` desde el
request hasta `registerReceivedQuantity`, y el acumulado decimal es lo que se escribe en
`purchase_order_details.received_quantity`.

**S5 — la UI permite tipear fracciones.** Los tres inputs de cantidad aceptan paso decimal —la línea nueva y
la edición inline en `detail-table`, y la cantidad a recibir en `receipt-create`— y el validador puro del
formulario de recepción (`validateQuantity` en `receipt-form.utils.ts`) deja de exigir entero, conservando
sus otros dos rechazos: negativo y por encima de lo pendiente.

**S6 — una sola migración, sin backfill.** `V30__purchase_order_fractional_quantity.sql` hace sólo
`ALTER COLUMN … TYPE DECIMAL(12,2)` de las dos columnas, conserva los dos `CHECK` (`quantity > 0`,
`received_quantity >= 0`) y no reescribe ninguna fila: todo entero almacenado convierte exacto. Los siete
pines de la cabeza de Flyway —`GoodsReceiptIntegrationTest` y las seis suites de `hr`/`provisioning`— pasan
a `"30"`, con los `@DisplayName` que nombran la versión alineados.

## Decisions

| # | Decision | Choice |
| --- | --- | --- |
| **D1** | Tipo destino | **`DECIMAL(12,2)`**, la misma precisión y escala que ya usan `goods_receipt_items.quantity_received` y los dos `stock`. No se elige `DECIMAL(10,2)` ni `NUMERIC` sin escala: la escala tiene que ser la que el boundary ya acepta. |
| **D2** | Migración | **`ALTER COLUMN … TYPE`** sobre las dos columnas, en una sola migración, conservando los `CHECK` existentes (que Postgres revalida al cambiar el tipo). No se elige columna nueva + copia + drop: duplicaría el dato y la tabla es chica pero el riesgo no lo justifica. |
| **D3** | Escala de entrada | **Dos decimales, rechazados con 400 por encima** (`@Digits(integer = 10, fraction = 2)` en el DTO y `setScale(2, UNNECESSARY)`-style en el servicio es innecesario: la validación del DTO y el `CHECK` de la columna son la puerta). No se elige redondeo silencioso: 2.505 kg redondeado a 2.51 es plata que nadie pidió. |
| **D4** | Lo que **no** se toca | `sales_order_items`, `inventory_movements`, `product_variant_locations.stock`, `product_variant_store_stock.stock`, `goods_receipt_items.quantity_received` (ya decimales), CFDI, conversiones entre unidades y la escala SAT (`D7` de `product-variant-measure-unit`). |
| **D5** | Forma de entrega | **Dos slices encadenados**, API primero y ABM después, cada uno con su issue aprobado y su PR, como `product-variant-measure-unit` (`#570`/`#571`): el contrato decimal y su migración se leen solos, y la UI no compila contra un contrato que todavía dice entero. |

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | `V30__purchase_order_fractional_quantity.sql`: las dos columnas a `DECIMAL(12,2)`, `CHECK` conservados, sin backfill | S1, S6 | inline | `352729a` |
| T2 | `PurchaseOrderDetail`: `Integer` → `BigDecimal` en `quantity` y `receivedQuantity` (campos, getters, setters, builder) | S1 | inline | `352729a` |
| T3 | `PurchaseOrderDetailRequest` / `PurchaseOrderDetailResponse`: `BigDecimal` + `@DecimalMin("0.01")` y `@Digits(integer = 10, fraction = 2)` | S1, S3 | inline | `352729a` |
| T4 | `PurchaseOrderService`: `registerReceivedQuantity(BigDecimal)`, comparaciones con `compareTo`, `toResponse`, el chequeo de línea completa y el evento publicado | S1, S2, S3 | inline | `352729a` |
| T5 | `PurchaseOrderDetailStatusChangedEvent`: `int receivedQuantity` → `BigDecimal` | S1 | inline | `352729a` |
| T6 | `GoodsReceiptService`: borrar el chequeo de entereza y llevar `ResolvedLine` a `BigDecimal` (`quantity`, `accumulatedQuantity`) | S4 | inline | `352729a` |
| T7 | Los siete pines de Flyway a `"30"`, con sus `@DisplayName` y nombres de método alineados | S6 | worker | `352729a` |
| T8 | Tests API: fracción aceptada en la línea y en la recepción, entero preservado, dos decimales aceptados, más de dos rechazados, over-receipt fraccionario rechazado | S2, S3, S4 | inline + worker | `352729a` |
| T9 | Angular `detail-table`: los tres inputs de cantidad (línea nueva y los dos de edición inline) con paso decimal, y `min` a 0.01 | S5 | worker | `45b21b0` |
| T10 | Angular `receipt-create` + `receipt-form.utils`: input con paso decimal (`min="0"` conservado) y validador sin exigencia de entero, con `Number.isFinite` cubriendo lo que la regla de entero atrapaba | S5 | worker | `45b21b0` |
| T11 | Angular specs: `detail-table`, `receipt-form.utils`, `receipt-create` | S5 | worker | `45b21b0` |

### Delivery slices

| Slice | Alcance | Commit | Líneas autoradas |
| --- | --- | --- | --- |
| 1 | Migración, entidad, DTOs, servicio, boundary de recepción, pines de Flyway y sus tests (T1–T8) | `352729a` | **487** |
| 2 | ABM Angular: los tres inputs, el validador y sus specs (T9–T11) | `45b21b0` | **128** |
| 3 | Total de línea alineado a la escala de la columna, cuatro javadocs corregidos y su test (hallazgos de la verificación independiente) | `c324286` | **85** |

**Por qué el slice 1 son 487 líneas y no dos PRs de ~240.** Medido con la misma herramienta que reportó
el corte: 487 autoradas, contra el aviso de ~400. Un corte más chico no es limpio, y hay dos formas de
intentarlo, ambas peores: (a) cortar por puerta —línea de OC primero, boundary de recepción después— deja
un estado intermedio donde la orden acepta 2.5 y la recepción sigue contestando 400 `whole number`, o sea
un contrato que se contradice a sí mismo entre dos PRs, y obliga a escribir dos veces las mismas ~50
líneas de asserts del boundary; (b) cortar la migración del código que la valida publica una columna
decimal que el boundary todavía rechaza como entera, que es exactamente el defecto que este registro
existe para no repetir. El corte real es el borde API/ABM, como en `product-variant-measure-unit`, y 487
es 1.2× del aviso, no 3.9×.

### La cadena de entrega se rearmó para que el PR de la API lleve su propio arreglo

`970adac` (el total de línea) se escribió **después** del commit del ABM, así que un PR de API cortado en
`352729ab` habría dejado afuera el arreglo de plata que la verificación independiente pidió: se estaría
revisando y aprobando un contrato con un defecto conocido. La rama se rearmó —es trabajo propio, sin
pushear— en dos: `feat/purchase-order-fractional-quantity-api` = `352729ab` + `c324286`, y
`feat/purchase-order-fractional-quantity` apilada encima con el ABM y el registro. Verificado por diff:
la API del PR 1 es idéntica byte a byte al subárbol de API ya gateado, y el PR 2 es idéntico al árbol
completo verificado; lo único que le falta al PR 1 son los seis archivos de Angular y este registro. Los
hashes de arriba son los de la cadena rearmada.

Nada de esto autoriza push, apertura de PR ni merge: esos pasos siguen siendo decisión del usuario, y cada
PR necesita su issue aprobado (`status:approved`).

## Log

- **L1** — `continua con product-variant-measure-unit`
- **L2** — Elección del diferido a tomar, verbatim de la opción elegida: `Fracciones en OC y recepción
  (W2-D7 → W3)`.
- **L3** — Evidencia recogida antes de escribir, toda medida sobre `main @ eb9cb19`:
  - `V1:345` `quantity INTEGER NOT NULL CHECK (quantity > 0)` y `V1:348`
    `received_quantity INTEGER NOT NULL DEFAULT 0 CHECK (received_quantity >= 0)`; `V1:500`
    `quantity DECIMAL(12,2)` para la línea de venta.
  - `GoodsReceiptService.java:458` rechaza la fracción con `quantity.remainder(BigDecimal.ONE).compareTo(ZERO) != 0`
    y `:469` cierra con `quantity.intValueExact()` / `accumulated.intValueExact()` sobre
    `ResolvedLine(int quantity, int accumulatedQuantity)` (`:513-518`).
  - `PurchaseOrderService.java:493` `registerReceivedQuantity(UUID, UUID, int)`; `:519` y `:523` comparan
    y derivan el estado con `int`; `:709` cierra la línea con `getReceivedQuantity() >= getQuantity()`.
  - `PurchaseOrderDetail.java:33,42` `Integer quantity` / `Integer receivedQuantity = 0`;
    `PurchaseOrderDetailRequest.java:14-15` `@Min(1) Integer quantity`;
    `PurchaseOrderDetailResponse.java:14,17` ambos `Integer`;
    `PurchaseOrderDetailStatusChangedEvent.java:19` `private final int receivedQuantity`.
  - Fuera de `purchaseorder/` y `goodsreceipt/` **nadie** lee esas dos cantidades: los únicos
    `getQuantity()` externos son de `inventory_movements` y `sales_order_items`, que ya son decimales.
    El evento no tiene listener (`@EventListener` no lo menciona en `src/main`).
  - Siete suites afirman `flyway…isEqualTo("29")`: `goodsreceipt/GoodsReceiptIntegrationTest:578` y
    `hr/{EmployeeContract,EmployeeStoreAssignment,EmployeeScopeAssignment,Hr,Employee}Schema…`,
    `provisioning/AccessProvisioningSchemaMigrationIntegrationTest`.
  - Angular: `detail-table.html:50-52` y `:138-140` (`min="1" step="1"`), `receipt-create.html:255`
    (`step="1"`), y `receipt-form.utils.ts` `validateQuantity` → `'Debe ser un número entero'`. Los modelos
    (`quantity: number`) y los validadores de página (`quantity > 0`) no necesitan cambio de tipo.

## Evidence log

- **L4 — RED observado antes de escribir, API (2026-10-25).** `PurchaseOrderDetailVariantIntegrationTest$FractionalQuantityTests`
  corrió contra `main @ eb9cb19` con JSON crudo y 15 tests: **2 fallas, 2.5 truncado**.
  `fractionalQuantityRoundTrips` → `JSON path "$.quantity" expected:<2.5> but was:<2.0>`: Jackson
  coaccionaba el `2.5` del JSON al campo `Integer` y lo guardaba como `2`, en silencio.
  `moreThanTwoDecimalsRejected` → `Status expected:<400> but was:<201>`: 2.505 entraba.
  `wholeQuantityUnchanged` pasó en esa misma corrida, así que el comportamiento previo del entero quedaba
  pineado por un test que ya existía y no hubo que escribir después.
- **L5 — RED observado, Angular (worker, suite enfocada de `purchases`).** 9 fallas / 384 pasadas:
  `receipt-form.utils` esperaba `'Debe ser un número entero'` para una fracción y para `NaN`;
  `detail-table` daba `expected '1' to be '0.01'` en los tres inputs (el de línea nueva `expected '' to be '0.01'`);
  `receipt-create`, el `step` y «2.5 validaba `false`».
- **L6 — gates de la API, verdes.** `cd life-control-api && ./gradlew spotlessCheck spotbugsMain test --no-daemon`
  → **BUILD SUCCESSFUL** en 3m13s, **969 suites / 3895 tests / 0 failures / 0 errors / 0 skipped**,
  SpotBugs `total_bugs="0"`, `spotlessCheck` OK tras un `spotlessApply` que sólo reformateó archivos ya
  modificados. Las únicas dos fallas residuales del cambio de tipo eran de forma y no de fondo:
  `getQuantity().toString()` comparaba `"2.5"` contra el `"2.50"` que devuelve `DECIMAL(12,2)`; pasaron a
  `isEqualByComparingTo`. Ninguna aserción se aflojó a `any()` y no se borró ni deshabilitó ningún test.
- **L7 — gates de Angular, verdes.** `npm run lint` → *All files pass linting*; `npm run build` →
  *Application bundle generation complete* (15.2s); `npm run test:coverage:check` → **191 files / 3880 tests**,
  statements 87.08 %, branches 86.28 %, functions 79.73 %, lines 89.72 %, los cuatro dentro de umbral.
  `npm ci --ignore-scripts` fue necesario porque el worktree no tenía `node_modules`; el flag fue a
  propósito: el `prepare` del paquete corre `git config core.hooksPath` sobre la config **compartida** del
  clon y habría reapuntado los hooks del anchor. Verificado después, `core.hooksPath` sigue en
  `life-control-app-angular/.husky/_`.
- **L8 — chequeo diferencial.** En la API la entrada que cambia es `2.5` y el servicio no tiene superficie
  CLI, así que el diferencial se midió por la interfaz pública y quedó registrado en `L4` sobre el mismo
  endpoint: el código base truncaba `2.5` a `2` y aceptaba `2.505` con 201; la rama persiste `2.5` y
  responde 400 a `2.505`. En Angular el worker bundleó la versión base y la nueva de `receipt-form.utils.ts`
  con esbuild en un `mktemp -d` y las corrió sobre 13 entradas: la única diferencia no pedida fue
  `-Infinity`, que pasaba de `'No puede ser negativo'` a `'Debe ser un número'`; se movió la compuerta
  finita detrás del chequeo negativo, se pineó, y la segunda corrida sólo muestra los cambios pedidos
  (`2.5` → `null`, `7.5` con pendiente 6 → mensaje de pendiente, `0.01` → `null`, `NaN`/`Infinity` →
  mensaje nuevo). El resto —`-1`, `-0.5`, `-2.5`, `-Infinity`, `0`, `1`, `6`, `7` y las otras cuatro
  funciones del módulo— byte-idéntico.
- **L9 — los pines de Flyway se renombraron, no sólo se re-asertaron.** Los seis métodos
  `flywayHeadIsV29` y sus `@DisplayName` («applies every migration through V29») pasaron a V30, y
  `HrSchemaMigrationIntegrationTest` sirvió de RED del pin: volviéndolo a `"29"` falla con
  `expected: "29" but was: "30"`. Un `@DisplayName` que siga diciendo V29 al lado de una aserción `"30"`
  es la contradicción que este repo ya tuvo que arreglar una vez en la migración anterior.
- **L10 — el registro `L3` describe el estado *anterior*.** Sus `step="1"` y su `'Debe ser un número entero'`
  son la evidencia con la que se planificó el cambio, no una descripción de la rama; se conservan como
  historia y esta entrada es la que lo dice.
- **L11 — verificación independiente (`gentle-ai-verify`, read-only) sobre `352729ab..781a8e7e`: 5/5 VERIFIED.**
  (1) `V30` sólo hace los dos `ALTER COLUMN … TYPE DECIMAL(12,2)`, sin `USING`, sin DML y sobre ninguna
  otra columna; sondeado contra Postgres: `numeric(12,2)` en las dos y los dos `CHECK`
  (`quantity > 0`, `received_quantity >= 0`) siguen en `pg_constraint`. (2) Una línea de 2.5 persiste y
  vuelve como 2.5 por `POST /purchase-orders/{id}/details` y por la creación anidada; no quedó ninguna
  coerción a entero (`parseInt`, `Math.trunc`, `ACCEPT_FLOAT_AS_INT`: ninguna). (3) Una recepción de 2.5
  escribe 2.50 en el ítem, el movimiento, los dos saldos y `received_quantity`, y 2.505 da
  `Reception line 0 … must have at most two decimals` con `receipts=0 items=0 movements=0`. (4) La
  derivación de estado no invirtió ninguna comparación y la promoción de cabecera sigue igual. (5) Los
  siete pines afirman `"30"` con nombres alineados y ningún `V29` residual; fuera de `purchaseorder/` y
  `goodsreceipt/` nada lee esas columnas.
  **Dos hallazgos causados por el cambio, ambos cerrados en `c324286`:**
  - *Documental*: cuatro javadocs seguían prometiendo el contrato entero (`GoodsReceiptService:149`,
    `InvalidReceiptQuantityException:6,8`, `GoodsReceiptLineRequest:12`); reescritos al contrato decimal.
  - *Monetario*: `unitPrice × quantity` puede dar cuatro decimales (2.5 × 123.45 = 308.625), la columna
    `total` guarda 308.63 y la respuesta del POST devolvía 308.625 — la respuesta y un GET posterior
    discrepaban sobre plata. Imposible antes de esta unidad, donde una cantidad entera acotaba el producto
    a dos decimales. `PurchaseOrderService.lineTotal` escala con `HALF_UP` en los cuatro call sites, y el
    test nuevo pinea 308.63 en la respuesta, en el GET y en la entidad: falló con 308.625 antes del
    arreglo.
  **Una consecuencia aceptada, no un defecto**: una cantidad entera se serializa ahora `3.00` donde antes
  era `3`. El número JSON es el mismo cuando se parsea (y el cliente Angular lo trata como `number`), así
  que no se normaliza la escala en la salida: hacerlo obligaría a elegir una representación para un dato
  cuyo valor es idéntico.
- **L12 — assess nativo y segunda verificación.** `gentle-ai review assess --base-ref eb9cb196…` sobre
  la rama completa: **risk medium**, 27 paths, 895 líneas, **`review due: no (under_budget)`**, razón
  `executable_change`. Con lo que manda la regla de entrada, no se abre linaje de review: no está vencido
  y la verificación independiente ya corrió. El facade `gentle_review assess` no relayó el `baseRef` al
  CLI (contestó `the review assess candidate has no pending changes`); el veredicto se obtuvo por la CLI
  nativa, que es la que lo emite.
  La segunda verificación independiente (commit `c324286`, que el primer verificador no vio) devolvió
  **cuatro VERIFIED** y la sensibilidad del test razonada en vez de observada: el RED de 308.625 contra
  308.63 lo había observado el writer con su corrida, y el verificador confirmó que con el producto crudo
  la aserción `isEqualByComparingTo("308.63")` falla. Cerró además que la única diferencia de los javadocs
  reescritos es de prosa (`grep -w INTEGER` y `whole number|integral` en `src/main`: sin hits fuera de la
  baseline y de la propia V30).
  **Dos avisos que NO son de esta unidad, declarados y no tocados** (ambos pre-existentes, o sea
  baseline-idénticos, y los dos del mismo género que el defecto que esta unidad arregla):
  - `POST /api/purchase-orders` con `details` anidados calcula el total de cada línea y las agrega a una
    colección `@OneToMany(mappedBy = "purchaseOrder")` **sin `cascade`** y sin `detailRepository.save`
    explícito (`PurchaseOrder.java:70-71`, `PurchaseOrderService.java:174-196`): responde 201 con líneas
    que no persiste, y ningún test cubre `create → GET /details`. Confirmado por lectura en esta sesión.
    La UI no usa esa ruta —crea la orden y agrega líneas por `/details`—, así que el modo de falla está
    expuesto sólo por la API.
  - `PurchaseOrderDetailRequest.unitPrice` no lleva `@Digits`: un precio de tres decimales se acepta y se
    guarda redondeado por la columna mientras la respuesta lo repite sin redondear.
  Ninguno de los dos es trabajo abierto de este registro: son follow-ups que necesitan su propio issue.
