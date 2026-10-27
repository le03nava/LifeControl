# ODD feature: product-category-select

**Status**: implemented — V36 adds the nullable `products.category_id`, the API carries it in both
directions, and the product form offers the three cascade selects plus the SAT-code suggestion. No work
left in this slice; the one follow-up, separate work, is a PostgreSQL-level test of the paged read under
the new entity graph (the unit tests mock the repository, so the generated SQL is unproven there but for
the schema suite).
**Created**: 2026-10-26 · **Risk**: high — item 1 of the Task Size list. V36 alters the `products`
table of every tenant, so a wrong constraint or a wrong default reaches live data forward-only; the
column itself is additive and nullable, which is what keeps the mistake detectable (no row changes
value) but not free (a bad FK blocks every product write).

## Objective

Let the operator classify a product against the company's category tree — categoría, subcategoría
nivel 1, subcategoría nivel 2 — and preload the product's SAT `ClaveProdServ` input with the key
configured on the chosen node, as an editable suggestion. The classification is persisted, so
reopening the product reships the three selects and the SAT code it was saved with.

## Problem

`products` carries `sat_code` as free text and **no link to `product_categories`** at all (V1
baseline, `life-control-api/src/main/resources/db/migration/V1__baseline_schema.sql:184`). The
category tree with its per-node `sat_code` exists since V31/V34, but nothing consumes it on the
product side: the key is typed from memory, per product, and the tax classification the catalog
already encodes is unreachable from the entity that needs it.

## Why this shape

- **One nullable FK to the deepest selected node, not three columns.** `products.category_id`
  references `product_categories(id)`, and the three selects are a *view* of that single link: the
  path is recovered by walking `parent_category_id` up. Three columns would allow states the tree
  forbids (a level-2 node that is not the child of the stored level-1 node), and the server has no
  cheap way to police that on every write.
- **Any level is a legal stopping point.** A root with no children is a complete classification, so
  the deepest *selected* node is what gets stored — not a required three-deep path. The tree caps at
  three levels (`MAX_CATEGORY_DEPTH`, backend D2), so the three selects cover the whole vocabulary.
- **`category_company_id` on the response, not on the request.** The category is company-scoped but
  the product is not (`/api/products` has no company segment), so the form cannot derive which tree
  to load from a company context it does not have. Exposing the category's company lets the form
  seed its company selector from the saved classification instead of guessing — and it is the only
  way a product classified under company A renders its path while the header context points at B.
- **The request carries only `categoryId`.** There is no company to validate against, and the FK
  makes cross-tenant state structurally impossible: the link points at exactly one real row of
  exactly one company. Tenancy here is the category's, not a claim's.
- **The server validates existence, and nothing else.** `ProductService` gains the one lookup that
  turns a bad id into the existing 404 contract (`ProductCategoryNotFoundException`) instead of an
  FK violation surfacing as a 500. It deliberately does **not** require the category to be enabled:
  soft-disabling a category must not freeze every later edit of the products already classified
  under it.
- **The SAT code stays free text and is never derived on read.** It is a suggestion written into the
  input, editable afterwards, and the product's own value is what is persisted and rehydrated. A
  product whose category key changed later keeps the code it was saved with — which is the point of
  answering S6 the way it is answered.
- **The tree is read with `includeDisabled=true`.** Without it, a product classified under a since
  disabled category would render three empty selects over a stored `category_id`, i.e. the saved
  classification would be invisible and the next save would silently drop it.
- **The select cascade is client-side.** One `getCategories` call answers the whole tree depth-first,
  so the parent→child filters are `computed`s over that array, exactly as
  `product-category-dialog.ts` already does for its parent picker. No per-level round trip.

## Specs

S1. En `/products/edit/:id`, la pestaña **Datos** ofrece tres selects dependientes — **Categoría**,
**Subcategoría nivel 1** y **Subcategoría nivel 2** — que son los niveles 1, 2 y 3 del árbol de
categorías de producto. Cada uno lista solo los hijos del nodo elegido en el anterior, y queda vacío
y deshabilitado mientras el anterior no tenga selección.

S2. Un selector de **Empresa** en el mismo formulario decide de qué árbol se leen las tres listas. Se
siembra de `?companyId=` de la URL y lo escribe de vuelta con `replaceUrl`, igual que
`ProductCategoryList`. Cambiar de empresa limpia los tres selects y el aviso del código SAT.

S3. La categoría elegida se persiste en el producto como el nodo más profundo seleccionado
(`products.category_id`, FK nullable). Un producto sin selección guarda `null`.

S4. Al elegir un nodo que tiene `sat_code` configurado, el input **Código SAT** se sobrescribe con ese
valor y el formulario muestra el aviso `Código SAT tomado de: <nombre del nodo>`. El operador puede
editarlo después, y lo que el input tenga es lo que se guarda.

S5. Al elegir un nodo **sin** `sat_code` configurado, el input **Código SAT** no se toca.

S6. Al reabrir un producto con categoría guardada, los tres selects quedan precargados con ese camino
y el input con el código SAT guardado del producto. Esa precarga no muestra el aviso de S4 ni
reescribe el código, aunque la categoría tenga otro configurado.

S7. Elegir un nivel limpia los niveles inferiores: cambiar **Categoría** vacía **Subcategoría nivel 1**
y **Subcategoría nivel 2**; cambiar **Subcategoría nivel 1** vacía **Subcategoría nivel 2**.

S8. El árbol se lee incluyendo los nodos deshabilitados, para que un producto clasificado bajo una
categoría deshabilitada después siga mostrando su camino. Los deshabilitados se listan pero no se
pueden elegir.

S9. Backend: `ProductRequest` gana `categoryId` opcional; un id inexistente responde 404 con el
contrato existente de categoría (`ProductCategoryNotFoundException`), y `null` o ausente limpia el
vínculo. `ProductResponse` devuelve `categoryId` y `categoryCompanyId` — la empresa dueña de la
categoría — para que el formulario cargue el árbol correcto sin adivinar la empresa.

S10. El mismo formulario se usa en el alta (`/products/create`), así que los tres selects y la
precarga aparecen también ahí.

## Tasks

| ID | Tarea | Specs | Ruta | Commit |
|----|-------|-------|------|--------|
| T1 | V36 con `products.category_id` y su FK; subir a V36 las siete aserciones de Flyway head | S3, S9 | inline | `feat/product-category-select-1` |
| T2 | Backend: relación en `Product`, `ProductRequest`/`ProductResponse`, `ProductService` y los 25 call sites de test | S3, S9 | inline | `feat/product-category-select-2` |
| T3 | Frontend: modelos, tres selects, selector de empresa, precarga y aviso del SAT en `ProductsForm`, y los cuatro controles en la página | S1, S3, S4, S5, S7, S9, S10 | inline + `gentle-ai-worker` | `feat/product-category-select-3` |
| T4 | Frontend: árbol de la empresa, camino guardado tri-estado y banner de error en `product-edit` | S2, S6, S8 | inline + `gentle-ai-worker` | `feat/product-category-select-4` |
| T5 | Verificación: suites backend y frontend, lint, verifier independiente por riesgo item 1 | todas | inline + `gentle-ai-verify` | `feat/product-category-select-4` (docs) |

## Log

L1 (pedido original, verbatim):

> quiero agregar /products/edit/ que se pueda seleccionar una categoria de producto, subcategoria nivel 1 y nivel 2 y que al seleccionarla se precarge el input del codigo sat con el que tenga configurada la categoria, subcategoria

L2 (respuestas del operador a las tres decisiones abiertas, verbatim):

> 1. Solo quiero que lo precargue como sugerencia, el usuario todavía lo puede editar y el que tenga es el que se guarde, cuando se vuelva a consultar los selects deben de estar precargados con la categoria guardada y con el codigo de sat guardado
> 2. Selector de empresa en el formulario
> 3. Sobrescribir y avisar que vino de la categoría

D1 (L2.1). La clasificación se **persiste**; el código SAT es una sugerencia editable. De ahí S3, S4,
S5 y S6: el guardado es el del producto, no el de la categoría.

D2 (L2.2). La empresa se elige dentro del formulario, con el mismo patrón `?companyId=` +
`replaceUrl` que `ProductCategoryList` ya usa, no con el contexto del header.

D3 (L2.3). La selección sobrescribe el código existente y el formulario dice de dónde vino.

D4. Se persiste el nodo más profundo, no tres columnas: ver *Why this shape*.

D5. El servidor valida existencia pero no `enabled`: deshabilitar una categoría no puede congelar la
edición de los productos que ya la tienen.

D6. El árbol se lee con `includeDisabled=true` para que el camino guardado resuelva siempre.

D7. Los selects viven en `ProductsForm`, que es el único formulario de producto del repo
(`product-edit.html:137` monta uno solo, compartido por la pestaña `Datos` y el paso 1 del stepper),
así que el alta hereda el comportamiento. Se declara en S10 en vez de ramificar el componente por
modo.

D8. Al limpiar un nivel, la clasificación se re-ancla al nodo más profundo **todavía** seleccionado en
vez de vaciarse: limpiar `subcategoría nivel 2` con una categoría ya elegida deja el producto
clasificado en esa categoría. Vaciar `categoryId` ahí desclasificaría el producto en silencio, que es lo
contrario de S3.

D10. El camino guardado es **tri-estado** (`string[] | null`): `null` significa "el host todavía no
sabe" (el producto llegó, el árbol sigue en vuelo) y el formulario no toca nada; `[]` significa "este
producto no tiene clasificación" y limpia los selects. Sin esa distinción, el effect del formulario
borraba `categoryId` durante la carga y la próxima grabación desclasificaba el producto en silencio.
De ahí también que el host exponga `categoryTree` con `status() === 'resolved'` en vez de leer
`categoriesResource.value()`, que lanza en estado de error, y que una lectura fallida se muestre con
`ErrorBanner` en vez de degradar a un árbol vacío.

D11. El limitador de niveles se movió a `applyLevelAvailability(root, sub1)`, una función pura de sus
argumentos: el effect de sembrado escribe la señal `selection` y leerla ahí lo invalidaba en cada pick.

D9. `ProductsForm` es el dueño del camino guardado, no el host: el host calcula `savedCategoryPath` (es
quien tiene el producto y el árbol) y el formulario lo aplica con `emitEvent: false`, así que una
recarga no es un pick y no puede pisar el código SAT guardado del producto con el de la categoría (S6).

L3 (defecto encontrado por el worker en la primera implementación del formulario, y su corrección):

> el effect de `categoryPath` leía `selection` (vía `syncLevelAvailability`), así que cada pick lo
> invalidaba y en el siguiente flush re-aplicaba el camino guardado (vacío) y borraba la selección.

El effect ahora deriva la disponibilidad de los niveles de los valores que ya leyó, con
`applyLevelAvailability(root, sub1)` como función pura de sus argumentos, y el spec
`should keep a pick across a change-detection flush` lo fija: sin esa corrección S1, S4 y S7 no se
cumplen en la pantalla. El spec de S7 que fijaba el vaciado de `categoryId` al limpiar el nivel 2 se
corrigió al criterio de D8.

## Delivery slices

Cadena de cuatro PRs apilados sobre `main` (`stacked-to-main`), en el orden en que se revisan y se
mergean. Cada slice compila y pasa sus checks **solo**, verificado en su propia branch:

| # | Branch | Contenido | Líneas | Verificación propia |
|---|--------|-----------|--------|---------------------|
| 1 | `feat/product-category-select-1` | V36 y el esquema | 191 | `./gradlew test --tests "com.lifecontrol.api.product.*"` → BUILD SUCCESSFUL |
| 2 | `feat/product-category-select-2` | El contrato de la API | 389 | idem → BUILD SUCCESSFUL |
| 3 | `feat/product-category-select-3` | El formulario y su contrato | 657 | `ng test --include=products-form.spec.ts --include=product-edit.spec.ts` → 85/85 |
| 4 | `feat/product-category-select-4` | El árbol, el camino guardado y este registro | 478 | idem → 92/92, y la suite completa 3931/3931 |

Los slices 3 y 4 quedan sobre el presupuesto de ~400 líneas y no se parten más: `ProductControl` exige
los cuatro controles nuevos, así que un slice sólo de modelos no compila, y separar los specs del código
que prueban sería peor que el exceso. De las 1705 líneas de código, ~950 son tests y ~190 este registro.

La branch sin partir (`feat/product-category-select`, tres commits de código) queda como referencia del
estado verificado de punta a punta; la cadena es la que se entrega.

## Evidence log

| Fecha | Evidencia |
|-------|-----------|
| 2026-10-26 | `./gradlew test --tests "com.lifecontrol.api.product.*"` → **BUILD SUCCESSFUL**, 345 tests, incluidos los cuatro tests nuevos de esquema contra PostgreSQL (columna nullable, `fk_products_category` rechaza un id inexistente, un nodo cualquiera es clasificación legal, borrar una categoría referenciada se rechaza) y las siete aserciones de Flyway head ya en V36. |
| 2026-10-26 | RED observado antes del arreglo: los 4 tests nuevos de `ProductServiceTest > category link` fallaron con el servicio sin resolver la categoría (mapeo en `null`), y pasaron tras `resolveCategory`. |
| 2026-10-26 | `ng test --no-watch --include=products-form.spec.ts --include=product-edit.spec.ts` → **88 passed (88)**, 2 archivos. |
| 2026-10-26 | Full suite Angular, re-corrida después de las correcciones de F1/F2: `ng test --no-watch` → **192 archivos, 3931 tests, 0 fallos**; `eslint src/features/products` limpio; `prettier --check` limpio tras `--write`. |
| 2026-10-26 | Verifier independiente (riesgo item 1) → **fail** en la primera pasada: F1 (pérdida silenciosa de la clasificación cuando el árbol llega después del producto, S6) y F2 (throw en change detection si la lectura del árbol falla). Ambos corregidos y fijados por test (D10): `product-edit.spec.ts` 66/66 y `products-form.spec.ts` 26/26, 92/92 en conjunto. |
| 2026-10-26 | Hallazgos del verifier del lado backend: el comentario de V36 justificaba el índice ausente con el lado equivocado del FK (corregido, A1) y el `@EntityGraph` traía `category` pero no `category.company`, dejando el N+1 que el propio comentario negaba (corregido, A3). `ProductControllerTest` suma el 404 de la ruta de update (P3). |
| 2026-10-26 | Cadena construida y verificada slice por slice: S1/backend verde, S2/backend verde, S3 85 tests, S4 92 tests. La rama publicada es la que manda: cada slice arrastra un commit `style(...)` con el `google-java-format` que exige el job `Static analysis`, y los hijos mergean el padre hacia adelante en vez de reescribir historia publicada. |
