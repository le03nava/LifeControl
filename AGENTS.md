# Repository Guidelines

## How to Use This Guide

- Start here for project-wide norms.
- Each active component has specific guidelines in su `AGENTS.md` (ej: `life-control-api/AGENTS.md`).
- Component docs override this file when guidance conflicts.

---

## Available Skills

Los skills propios del repo viven en `.agents/skills/` y se versionan. Pi y OpenCode los descubren automáticamente: caminan hacia arriba desde el `cwd` hasta la raíz del repo y cargan todo `.agents/skills/**/SKILL.md`. No hace falta registrarlos en ningún config.

### Repo Skills (versionados)
| Skill | Description | Ruta |
|-------|-------------|------|
| `project-conventions` | Convenciones enterprise, gates de seguridad, controles de release y evidencia auditable | [SKILL.md](.agents/skills/project-conventions/SKILL.md) |

### Generic Skills (provistos por gentle-ai, NO versionados acá)
| Skill | Description |
|-------|-------------|
| `angular-21` | Angular 18+ patterns (signals, standalone, control flow) |
| `spring-boot-3` | Spring Boot 3 patterns (DI, config, web services) |
| `sdd-init` | Spec-Driven Development initialization |
| `skill-creator` | Create new AI agent skills |

### Project-Specific Skills (provistos por gentle-ai, NO versionados acá)
| Skill | Description |
|-------|-------------|
| `sdd-explore` | Explore and investigate ideas |
| `sdd-propose` | Create change proposals |
| `sdd-spec` | Write detailed specifications |
| `sdd-tasks` | Break down specs into tasks |
| `sdd-apply` | Implement tasks from specs |
| `sdd-verify` | Validate implementation against specs |
| `sdd-archive` | Sync specs and archive changes |

> **Skills de usuario**: los de estas dos tablas están instalados en la máquina por gentle-ai (hoy en `~/.config/opencode/skills/`), no en este repo, por eso no tienen ruta relativa. Si falta alguno, corré `gentle-ai sync`.
>
> **Dónde van los skills compartidos**: sólo `.agents/skills/` se versiona. `.opencode/`, `.pi/`, `.atl/`, `sdd/` y `openspec/` están en `.gitignore`, así que un skill guardado ahí es local y el equipo no lo recibe.

---

## Auto-invoke Skills

| Action | Skill |
|--------|-------|
| Cualquier cambio de código, seguridad, CI/CD o release | `project-conventions` |
| Trabajo en paralelo, worktrees, varios agentes a la vez | `project-conventions` → `references/worktrees.md` |
| Frontend Angular development | `angular-21` |
| Backend Spring Boot services | `spring-boot-3` |
| Initialize SDD in project | `sdd-init` |
| Explore codebase / investigate | `sdd-explore` |
| Create feature proposal | `sdd-propose` |
| Write specifications | `sdd-spec` |
| Break down into tasks | `sdd-tasks` |
| Implement code | `sdd-apply` |
| Verify implementation | `sdd-verify` |
| Archive completed change | `sdd-archive` |
| Create new AI skill | `skill-creator` |

---

## Exploración de código con CodeGraph

Grafo de símbolos en `.codegraph/codegraph.db` (SQLite + FTS5): Java y TypeScript en **un mismo índice**,
con call paths y blast radius resueltos. Es la única vista del repo que cruza `life-control-api` y
`life-control-app-angular` en una sola consulta — Gradle ve sólo el build, `madge` sólo los `.ts`,
`jdeps` sólo los `.java`. Medido en este repo: **1,517 archivos, 43,178 nodos, 98,016 aristas, 198 MB**,
5.5 s de indexado.

| Regla | Valor |
|-------|-------|
| Dónde se indexa | **Sólo el anchor.** El índice es **por checkout** y pesa ~198 MB: indexar cada worktree de `new-unit.sh` multiplica ese costo y el índice muere con el worktree. Se consulta desde el anchor, que es de lectura por convención — `.codegraph/` va excluido localmente, así que `git status` sigue vacío |
| Qué queda **afuera** del índice | **Shell no está soportado**: `.github/scripts/**`, `.githooks/**` y `docker/scripts/**` no existen para el grafo, así que `affected` nunca va a nombrar ninguna de las suites de `.github/scripts/tests/`. `odd/tasks/*.md`, los ADRs, el compose y el YAML de `k8s/` tampoco son nodos. Esa mitad del repo se sigue leyendo con `read`/`grep` |
| Cómo se mantiene fresco | **No se auto-sincroniza**: el watcher sólo vive dentro de `codegraph serve --mcp` y el daemon no queda corriendo tras `init` (`codegraph daemon` → *No CodeGraph daemons running*). Antes de una tanda de consultas: `codegraph sync`, incremental y ~1 s con cero cambios. El lock `writer.pid` admite **un solo writer por proyecto** |
| Antes de editar código compartido | `codegraph impact <símbolo>` enumera qué se afecta, con `archivo:línea` y los tests que lo verifican. Es la forma barata de detectar que tocaste algo que otros consumen, en vez de juzgarlo por el tamaño del diff |
| Para elegir tests | `codegraph affected <archivo>`. Con un símbolo **hoja** devuelve 3–15 archivos y ahorra de verdad; con un **hub** (`CurrentUserContext`: 226 callers, **206 tests** afectados) devuelve "casi todo el API", y ese resultado *es* la respuesta: el cambio es de riesgo alto y va con suite completa, no con un subconjunto |
| Qué **no** reemplaza | Nada del flujo de verificación: las suites de `.github/scripts/tests/`, `./gradlew test`, `npm run test:coverage:check` y el `PR Label Guard` siguen siendo los gates. El grafo dice **por dónde empezar**, nunca **qué alcanza** |
| Cómo se consulta desde Pi | La tool `codegraph` (`init` / `query` / `explore`), sin ruta: siempre el workspace actual. El subagente `gentle-ai-explore` la tiene en su lista de tools y la usa **antes** de buscar en el filesystem |
| Entorno y privacidad | La telemetría anónima viene **encendida** por defecto: `codegraph telemetry off`. La instalación tampoco cablea Pi (`codegraph install` no lo lista como agente); el adapter lo aporta gentle-pi con esa tool, y `~/.pi/agent/mcp.json` aporta `codegraph serve --mcp` |
| Lo que no se hace | Versionar `.codegraph/` (la exclusión va por `git rev-parse --git-path info/exclude`, nunca por un `.gitignore` trackeado: eso sí sería una decisión de PR); cablearlo en CI (necesitaría el índice por job); o sincronizarlo desde un hook (llega tarde: sincroniza después del commit, cuando ya consultaste un grafo viejo) |

```bash
# En el anchor, una vez por checkout
codegraph init && codegraph telemetry off

# Después de un pull, antes de consultar
git pull --ff-only && codegraph sync

# Pre-vuelo de un cambio y su test plan
codegraph impact CurrentUserContext
codegraph affected life-control-api/src/main/java/com/lifecontrol/api/<área>/<Clase>.java
```

---

## Trabajo en paralelo con worktrees

Aislamiento para trabajo concurrente. El detalle completo, los comandos y los anti-patrones están en
[`references/worktrees.md`](.agents/skills/project-conventions/references/worktrees.md).

| Regla | Valor |
|-------|-------|
| Un worktree por unidad de trabajo | Una feature, un fix, un PR |
| Un agente por worktree | Dos agentes en el mismo cwd se pisan los archivos a nivel filesystem; Git no lo puede evitar |
| **El anchor nunca es destino de edición** | No es una regla de paralelismo: vale también para un solo agente en secuencia, que es el caso que produjo todos los incidentes de anchor del repo. Se lee, no se escribe: `fetch`, `log`, `diff`. Un cambio hecho ahí o no se puede commitear, o queda en una rama que nunca se va a poder pushear |
| Primer paso de toda unidad | `./.github/scripts/new-unit.sh <branch>` — crea el worktree de la convención con `git worktree add` puro, sin depender de `herdr`, y deriva el slug de la rama |
| Hook local | `git config core.hooksPath .githooks` (una vez por clon; los worktrees linkeados comparten la config). `.githooks/pre-commit` rechaza un commit en la rama por defecto, resuelta desde `origin/HEAD` —no hardcodea `main`—, y deja escapar con `ALLOW_MAIN_COMMIT=1` el commit que decidas a propósito. Verificado por `.github/scripts/tests/worktree-anchor.test.sh` |
| Al empezar la sesión | En el anchor: `git fetch origin`, `git status --short` (tiene que estar **vacío**; si no, parar y preguntar de quién es, nunca descartar) y `git log --oneline origin/main -1`. La base de una unidad es `origin/main`, no el `main` local, que otra sesión puede haber movido |
| Qué ejecuta el anchor | Sólo lecturas y fast-forwards: `fetch`, `pull --ff-only`, `log`, `diff`, `status`, `worktree add/remove/prune`. **Nunca** `reset`, `stash`, `clean`, `checkout -b`, `switch -c`, `rebase`, `cherry-pick`, editar archivos ni commitear. La lista completa y el porqué, en `references/worktrees.md` |
| El anchor queda en `main` y limpio | `~/workspace/LifeControl` es la referencia para `fetch`, `log` y `rebase` |
| Path del worktree | `~/workspace/LifeControl-worktrees/<slug>`, hermano del repo, nunca adentro |
| Slug del directorio | Igual al de la rama, con `/` → `-`: `feat/x` → `feat-x` |
| Creación | `./.github/scripts/new-unit.sh <branch>` (portátil, el camino documentado). Con `herdr` instalado: `herdr worktree create --path ... --branch <branch> --base main --no-focus` (sin `--branch` la rama sale aleatoria) |
| Limpieza | `herdr workspace close` → `git worktree remove` → `git worktree prune`, en ese orden |

> **Requisito de trust**: Pi carga `.agents/skills/` desde el `cwd` y sus directorios **ancestros**, y sólo
> después de confiar el proyecto. Un worktree no es hijo del repo, así que una entrada de trust para el
> anchor **no lo cubre**. Hay que confiar `~/workspace/LifeControl-worktrees` como carpeta padre: una sola
> entrada cubre todos los worktrees actuales y futuros.
>
> **`herdr workspace close --group` cierra el workspace primario y todos los worktrees linkeados.** No lo
> uses para saltear un error de cierre.

---

## Registros ODD (`odd/tasks/`)

Cada unidad de trabajo sustancial deja un registro versionado en [`odd/tasks/`](odd/tasks/). El detalle
completo, con la forma exacta del header, está en
[`references/feature-records.md`](.agents/skills/project-conventions/references/feature-records.md).

| Regla | Valor |
|-------|-------|
| Qué lleva el header | **Qué es el trabajo y qué queda** (nada, diferido o bloqueado, con la razón). Nunca si eso se pusheó, se abrió, se revisó o se mergeó |
| Token de apertura | El párrafo `**Status**:` **abre con uno de cinco veredictos**: `merged`, `implemented`, `deferred`, `blocked`, `superseded` (sin distinguir mayúsculas, con énfasis de Markdown permitido). Cualquier otra palabra de apertura es un hallazgo, en cualquier idioma: `delivered`, `closed`, `landed`, `done`, un id de unidad (`W1b merged`) o una oración entera no son veredictos. `delivered`/`closed`/`landed`/`done` no responden la única pregunta que el token existe para responder — si eso está en `main` o todavía debe algo — así que se dice `merged` o `implemented`. Es un conjunto cerrado: un sexto token es un cambio de contrato, y la suite falla si el código y `references/feature-records.md` dejan de nombrar los mismos |
| Qué no lleva nunca | Estado vivo o pendiente, en cualquiera de los dos idiomas: "not pushed", "no PR is open", "not merged", "yet to land", "awaiting review", "pending review", "in progress", "unstarted", "what remains is the delivery", "sin pushear", "no hay PR abierto", "sin mergear", "en progreso", "esperando review". Tampoco un descargo del tipo `makes no claim about push` al lado de una evidencia de entrega: el descargo solo es legal, la contradicción no |
| Dónde va la entrega | En el log de evidencia fechado del propio registro, con fecha, PR y commit. Nombrar el PR o el merge commit en el header es **opcional y nunca obligatorio**: se deriva con `git log -1 --format='%h' -- odd/tasks/<slug>.md`, no se guarda |
| Cuántos PRs por unidad | **Uno**: el registro viaja en el PR que trae el trabajo y su header queda final ahí. No existe un PR de seguimiento para mover el header a una forma mergeada: medido, esa práctica costó 51 de los últimos 150 PRs mergeados, 40 de ellos reescribiendo un solo registro, cada uno con su propio issue `status:approved` |
| Por qué | El header se escribe **antes** del merge y nadie lo revisita: un estado vivo queda falsificado por el próximo merge, sin que ningún commit toque la frase que mintió. La única frase que sobrevive es la que ningún merge puede cambiar |
| Enforcement | **Automático**: `.github/scripts/tests/odd-status-headers.test.sh` corre en cada PR (el job `guard` de `PR Label Guard` levanta todas las suites de `.github/scripts/tests/`) y falla el registro que no tiene párrafo `**Status**:`, el que **abre el párrafo con una palabra que no es uno de los cinco veredictos** —el agujero que una lista negra no puede cerrar: *no encontré ninguna frase prohibida* y *hay un rechazo escrito en palabras que mi lista no tiene* imprimían el mismo `PASS`—, el que afirma cualquier frase de la lista prohibida **aunque nombre un merge commit**, el que descarga el estado de entrega al lado de una evidencia, y el que cita un merge commit que no resuelve como ancestro de `origin/main`. No exige evidencia de ninguna clase, y una suite de paridad falla si alguna frase de esta tabla deja de estar cubierta o si el vocabulario del contrato y el del código dejan de coincidir. Los registros anteriores a la regla del header no se reescriben, porque su narrativa congelada es en sí misma evidencia; los headers que sí nombran un merge commit siguen siendo legales. La única excepción, tomada una vez: cuando el token de apertura entró, se normalizó **sólo la palabra de apertura** de los 23 registros que no la usaban |

---

## Labels de PR

Cada PR lleva **exactamente un** label `type:*`, elegido del vocabulario que declara el skill `branch-pr`
de gentle-ai. El vocabulario completo, el modo de falla medido que obligó a crear estos labels y los
anti-patrones están en
[`references/labels.md`](.agents/skills/project-conventions/references/labels.md).

| Regla | Valor |
|-------|-------|
| Cuántos | Exactamente uno por PR: ni cero ni dos. Los labels de área (ej. `java`) son ortogonales y pueden acompañarlo |
| Vocabulario | `type:feature` (`feat`, `perf`) · `type:bug` (`fix`, `revert`) · `type:docs` (`docs`) · `type:refactor` (`refactor`) · `type:chore` (`chore`, `style`, `test`, `build`, `ci`) · `type:breaking-change` (cualquier tipo con `!`: `feat!`, `fix!`, `refactor!`) |
| Otros labels | Los defaults de GitHub (`bug`, `enhancement`, `documentation`) son vocabulario de triage de **issues**; `status:approved` aprueba el issue linkeado; `dependencies` lo pone Dependabot |
| Cómo se aplica | Se tilda **un** checkbox de la sección *PR Type* de `.github/PULL_REQUEST_TEMPLATE.md` y se agrega el label que ese renglón nombra: `gh pr create --label type:docs ...`, o `gh pr edit <n> --add-label type:docs` sobre un PR ya abierto |
| Qué no se crea | Sinónimos (`type:feat`, `type:fix`) ni `size:exception`, que es política de repos de Gentle, no de este repo |
| Plantilla | `.github/PULL_REQUEST_TEMPLATE.md`: los seis checkboxes (uno por label), el issue linkeado, el resumen, la tabla de cambios y el test plan. El label se aplica igual: el guard lee labels, no checkboxes |
| Enforcement | `PR Label Guard` (`.github/workflows/pr-label-guard.yml` → `.github/scripts/check-pr-type-labels.sh`) falla el PR si el set de labels no tiene exactamente un `type:*` del vocabulario — cero, dos o un nombre fuera del vocabulario son fallos. **No** lleva filtro `paths:`, a propósito: los otros cuatro workflows filtran por componente, así que un PR sólo-markdown no corre ningún job, que es justo donde esta regla se olvida |
| Historial | `.github/scripts/retro-label-prs.sh` reconcilió los 341 PRs mergeados el 2026-10-17: 307 altas y 21 correcciones (`type:chore` sobre diffs que no eran chores). Es dry-run por defecto, idempotente, y **no** toca los labels de triage (`documentation`, `bug`, `enhancement`, `java`, `dependencies`) |
| Suite del guard | `.github/scripts/tests/*.test.sh` (**11 suites**; el conteo se lee de la corrida y no se copia acá — `for s in .github/scripts/tests/*.test.sh; do "$s"; done` reportó **364 aserciones** el 2026-10-21, y este renglón decía 245 y después 290 y después 312 y después 350 mientras el árbol ya tenía más), offline: el mapeo completo, el marcador `!`, los cinco modos de falla del guard, la idempotencia y la coincidencia plantilla↔vocabulario, más el audit de headers de `odd/tasks/*.md` con su vocabulario cerrado y su paridad con el contrato, la referencia al issue, los nombres de job, las plantillas de issue, la paridad de `paths:` entre workflows, la barrera de formateo del job `Lint & Build` y la regla del anchor con su hook manejado como programa, el procedimiento de arranque de sesión y la lista de lo que el anchor puede ejecutar). Corre en el mismo workflow, y shellcheck `--severity=warning` sobre los **16** archivos shell de `.github/scripts/` y `.githooks/` — los cuatro scripts, las once suites y el hook, tomados por glob, así que una suite nueva queda cableada por existir y el hook se lintea aunque no lleve extensión `.sh` |
| Qué no hace | El guard **no** está en branch protection: reporta en cada PR, pero nada obliga a esperarlo antes de mergear hasta que las reglas de protección lo nombren. Tampoco valida el issue linkeado ni el cuerpo del PR |
| Citas desde `docs/` hacia migraciones | `adr-migration-citations.test.sh` recorre cada cita `path:line` que `docs/` escribe hacia `life-control-api/src/main/resources/db/migration/` y falla si la versión no existe o nombra dos archivos, si el rango se pasa del archivo, o si la línea no nombra en backticks nada que declaren las migraciones que su párrafo cita. El nombre se busca en el archivo entero y no en el rango citado, porque una cita puede nombrar la tabla cuya columna está en esa línea: por eso no ve un rango que sobra ni una cita a las líneas equivocadas del archivo correcto, y no juzga un nombre que ninguna migración declara |

---

## Project Overview

LifeControl es un sistema de gestión consolidado en un monolito modular `life-control-api`.

| Component | Location | Tech Stack | Estado |
|-----------|----------|------------|--------|
| API Gateway | `api-gateway/` | Spring Boot | Activo |
| Life Control API | `life-control-api/` | Spring Boot, Java 21, PostgreSQL | **Activo** — núcleo del sistema |
| Angular App | `life-control-app-angular/` | Angular 20.3.0, Material, Vitest + Playwright, ESLint/Prettier | Activo |
| Backstage | `backstage/` | Backstage framework | Activo |

---

## Atributos de producto (`products.attributes` / `product_variants.attributes`)

El `jsonb` de atributos existía desde V1 como texto libre y **sin lector**: el índice GIN
`idx_products_attributes` no lo consulta ninguna query y el único escritor era el textarea de JSON del
formulario. Es decir, el esquema lo escribía el operador. V37/V38 le ponen catálogo.

| Regla | Valor |
|-------|-------|
| Dónde vive el catálogo | `product_attribute_profiles` → `product_attribute_definitions` → `product_attribute_values` (V37). Las definiciones son **globales**; una fila con `company_id` es el override de una empresa, y hoy no existe ninguna: es la costura, no una función |
| Cómo se engancha a una categoría | `product_attribute_profile_roots(profile_id, root_name, company_id)` une un perfil con el **nombre de la raíz** del árbol (V38 siembra once de las doce raíces habilitadas). Por nombre y no por id porque el catálogo es global y las categorías son por empresa: un id obligaría a una fila por inquilino, y una empresa creada mañana necesitaría definiciones nuevas. El costo está dicho: renombrar una raíz deja de resolver y el endpoint responde 204 |
| Cómo se resuelve | La categoría del producto sube hasta su raíz y toma el perfil de esa raíz; una fila de la empresa gana sobre la global |
| La clave es el **código** | `{"material_externo": "piel_vacuno"}`, nunca `{"Material Externo": "Piel Vacuno"}`. El label es presentación y se puede reescribir; el código es lo que filtra un reporte |
| Tipos (`kind`) | `TEXT`, `ENUM`, `MULTI_ENUM`, `MEASUREMENT` (`{value, unit}`), `BOOLEAN`. `MEASUREMENT` no es un lujo: seis de los atributos pedidos son cantidad + unidad, y "1/2 pulgada" guardado como string no se puede comparar ni convertir |
| Nivel (`attribute_level`) | `PRODUCT` o `VARIANT`. **Color y acabado son del variante** (dos colores son dos códigos de barras, dos stocks y dos precios); el resto es del producto. `Talla` **no** es un atributo: `product_variants.variant_name` ya es la talla, y un segundo campo sería una segunda fuente de verdad del mismo dato |
| `unit_label` | Es una **sugerencia** para el formulario y nunca una restricción: la misma rama vende "1/2 pulgada" y "10 milímetros", así que el valor guardado lleva su propia unidad |
| Escritura | El request **manda**: el mapa reemplaza al guardado en vez de mezclarse (`null` = no tocar). Y se valida el **estado que queda**, no el campo que llegó: un atributo requerido no se saltea omitiendo el mapa |
| Validación | En el servicio, no en el formulario: código desconocido, valor fuera del conjunto, multi-valor vacío o duplicado, medición sin unidad, texto vacío, no-booleano o requerido faltante son **400** y nombran el código. El formulario no es una frontera de integridad y cualquier otro cliente pasa por el mismo control |
| Alcance | `GET /api/companies/{companyId}/product-categories/{categoryId}/attribute-profile` → 200 con `productAttributes` y `variantAttributes` separados por nivel, **204** cuando la rama no declara perfil (es una respuesta legítima, no un error) y 404 si la categoría es de otra empresa |
| Las dos pantallas | El formulario de producto renderiza las definiciones `PRODUCT` y el diálogo de variante las `VARIANT`, del mismo perfil y con el mismo componente, para que no se puedan desincronizar. Al **guardar** cada pantalla proyecta el conjunto que la rama declara y **nombra en pantalla** los códigos guardados que se van a descartar: así nada se borra sin avisar y nada queda imposible de guardar. Con el perfil todavía desconocido (lectura en curso o fallida) no escribe nada y deja el conjunto como vino |
| Lo que **no** cubre | No hay fecha de caducidad: es del lote, no de la fila de catálogo. No hay GIN sobre `product_variants.attributes`: el de `products.attributes` existe desde V1 y no lo lee ninguna consulta, así que un segundo índice sin lector sólo costaría escrituras. No hay ABM de perfiles: se editan por migración hasta que exista la pantalla |

---

## Moneda de la empresa (`companies.currency`)

`companies.currency` es `CHAR(3) NOT NULL REFERENCES currency (code)` desde `V57`, y **la empresa la
declara: el servidor no adivina**. El campo viaja en el mismo `CompanyRequest` para `POST` y `PUT`.

| Regla | Valor |
|-------|-------|
| Obligatoria, sin default | `CompanyRequest.currency` es `@NotBlank` y `CompanyService.resolveCurrency` rechaza blank también para todo caller que no pase por HTTP. Un body sin `currency` es **400** `"currency is required"`, en el alta y en la edición |
| Por qué no hay default | Un `MXN` silencioso escribe la moneda de un país sobre las ventas de otro sin decirlo (ADR-0002, decisión 2, `D-c`). Pre-seleccionar en la pantalla sería ese mismo default una capa arriba, así que el select arranca **vacío** y es requerido |
| El catálogo | `GET /api/currencies` devuelve una lista plana `{code, minorUnits, symbol, enabled}`, sólo habilitadas salvo `?includeDisabled=true`, para cualquier principal autenticado. **Y se puede editar por API**: `POST`, `PUT /{code}`, `DELETE /{code}` y `PATCH /{code}/enable` bajo `lc-admin` o `lc-currency` (`odd/tasks/currency-catalog-abm.md`), así que sembrarlo por migración ya no es la única forma de cambiarlo. Retirar es `PATCH …/enable` y no un borrado, porque cinco columnas FK apuntan a la fila |
| La clave es el **código** | ISO 4217 (`MXN`), nunca el símbolo ni el nombre: `symbol` es presentación y se puede reescribir |
| Qué resuelve el servidor | El código se resuelve contra el catálogo dentro de la transacción: un código que el catálogo no tiene es **404**, uno que tiene pero está retirado es **400** cuando el request lo cambia, y ninguno de los dos llega a la fila. La pantalla ofrece sólo las habilitadas |
| Lo que **no** cubre | El desglose de impuestos, `tax_rate`, `sale_payment` y la escala de las ventas son `T2` y posteriores de `odd/tasks/money-first-class.md`. **El catálogo ya no se edita por migración**: `POST /api/currencies`, `PUT /api/currencies/{code}`, `PATCH /api/currencies/{code}/enable` y `DELETE /api/currencies/{code}` existen desde `odd/tasks/currency-catalog-abm.md`, con `hasAnyRole('lc-admin','lc-currency')` a nivel de método, la forma que `payment_methods`, `measure_units` y `seniority_levels` ya usaban. Retirar es `PATCH …/enable` y no un borrado: cinco columnas FK apuntan a la fila, así que el `DELETE` de un código referenciado es **409**. Toda escritura evicta la región `currencies`, porque con la escritura el cache dejó de ser permanente. Lo que **sí** sigue faltando es la pantalla: ningún catálogo global de este repo tiene ABM de UI, y el menú es una decisión aparte |

> **Una moneda retirada del catálogo no inutiliza la empresa que ya la tenía.** `updateCompany` resuelve
el código contra el catálogo sólo cuando el request lo **cambia**: si `request.currency()` es exactamente
la moneda guardada, el valor se conserva sin resolver, porque un código que el catálogo retiró después no
es una selección que el caller esté haciendo de nuevo y rechazarlo dejaría sin guardar un campo que nadie
tocó. Cambiar la moneda **a** un código retirado sigue siendo **400**, igual que en el alta.

## Dinero de la orden de compra (`purchase_orders` / `purchase_order_details`)

La orden de compra tenía **una sola cifra** —el `total` de cada línea, escrito por Java—, así que el documento no
sabía lo que costaba y la pantalla lo respondía sumando filas en el navegador con un literal `'MXN'`. `V92`/`V93`
le dan al documento su propio dinero, **derivado en la base** (`odd/tasks/purchase-order-money.md`).

| Regla | Valor |
|-------|-------|
| Dónde vive | `purchase_orders.currency CHAR(3) NOT NULL REFERENCES currency (code)`, `subtotal`, `tax_total`, `total_amount`, y `purchase_order_details.subtotal` / `total`, todas `NUMERIC(19,4)` |
| Quién lo escribe | **Nadie en Java.** `purchase_order_detail_money()` (`BEFORE INSERT OR UPDATE`, trigger `trg_purchase_order_details_money`) deriva las tres columnas de la línea —`subtotal = round(unit_price * quantity, 4)`, `tax_amount = round(subtotal * tax_rate, 4)` y `total = subtotal + tax_amount`— y `purchase_order_money()` (`AFTER INSERT OR UPDATE OR DELETE` sobre las líneas, trigger `trg_purchase_order_details_document_money`) recompone el documento bajo el lock de su fila; `purchase_order_currency()` copia `companies.currency` al alta |
| Las dos mitades de la línea | `subtotal` es la base y `total` lo que la línea cuesta. Son dos columnas desde el principio porque la sucesora agrega el IVA: un único `total` que después empieza a incluir impuesto es una columna que cambia de significado debajo de las filas ya guardadas |
| Las constraints | `ck_po_order_total` (`total_amount = subtotal + tax_total`) y `ck_po_order_parts_not_negative`. Compras no tiene descuento, así que **no** hay `discount_total` que copiar de ventas |
| La moneda se congela | Es la de la empresa **al crear la orden** y no se re-deriva: cambiar la moneda de la empresa no reescribe un documento histórico, igual que decidió `V80` para la venta |
| Lo que sí hace Java | **Leer de vuelta**, nunca asignar: `refreshDerivedOrderMoney` / `refreshDerivedLineMoney` hacen `flush()` + `refresh()` en las rutas que **mueven** el dinero —alta, reemplazo de líneas, alta de línea, edición de línea y **re-habilitar** una orden borrada, porque re-habilitar escribe las líneas y la derivación las lee—, porque las columnas son `insertable = false, updatable = false` y la caché de primer nivel devuelve la instancia vieja (el `null` del alta, el total anterior de la edición). Las dos bajas son la excepción deliberada: no devuelven body, así que no hay cifra que hacer coincidir con la fila. El mapper no refresca: la lista pagaría una sentencia por fila sin que nada haya cambiado |
| La pantalla | Lee `totalAmount` y el `total` de cada fila del response con el `MoneyPipe` compartido y la moneda del documento. **No queda ningún `'MXN'` ni una suma en el browser** en `features/purchases/purchase-orders`, y mientras el documento no tiene respuesta del servidor la pantalla no renderiza cifra en vez de inventarla |
| Auditoría de la migración | Las filas existentes se recomputan con la misma expresión, así que un `total` guardado puede moverse hasta **0.005** (de 2 a 4 decimales). El backfill de `currency` corre con dos ventanas `NO FORCE ROW LEVEL SECURITY` explícitas, porque `purchase_orders` está armada desde `V53` y Flyway migra como el dueño, al que la policy también alcanza |
| El impuesto de la línea | `purchase_order_details.tax_code` / `tax_rate` / `tax_amount` (`V94`). El código sale del **producto** (`products.tax_code`, vía la variante que la línea nombra); la tasa se **congela** al crear, con la fecha del documento —que es `created_at`, porque una orden de compra no tiene `order_date`—; y el monto lo deriva el trigger. Nunca lo manda el cliente: `PurchaseOrderDetailRequest` no ganó ningún campo |
| Por qué la tasa es columna y no un join | `tax_rate` es append-only por `(company_id, tax_code, effective_from)` y el resolver toma la última tasa ≤ la fecha del documento, así que un join reescribiría un documento histórico en cuanto se publica una tasa nueva. La copia es lo que hace que un cambio de tasa no reescriba nada — `ADR-0002 §Decisión 6` |
| Editar no re-resuelve | Cambiar cantidad o precio de **una fila que existe** conserva `tax_code` y `tax_rate` guardados y re-deriva desde **esa** tasa. La excepción es mover la línea a **otra variante**: ya no es el artículo cuya tasa se copió (`SalesOrderService.java:380-384`). Y un **reemplazo completo** (`PUT` con todas las líneas) no es una edición: borra las filas y crea nuevas, así que cada una vuelve a copiar contra `created_at`. Eso mueve el snapshot sólo si cambió el `tax_code` del producto o si se publicó una tasa con `effective_from <= created_at` **después** del alta; una tasa posterior a la fecha del documento no lo mueve. Está fijado por un test, no dejado a la memoria (`L14`) |
| El rechazo, y lo que la secuencia sí pierde | Un código gravable sin tasa habilitada en la fecha del documento es **400** que nombra el código y la fecha, y **no deja fila**: en el alta y en el alta de línea se lanza antes de la primera escritura; en el reemplazo completo las líneas viejas se borran antes, y lo que impide que quede un documento a medio reemplazar es la transacción, no el orden de las sentencias. El número de documento es otra cosa: `createPurchaseOrder` lo pide **después** de resolver todas las líneas (issue #857), así que un rechazo de una línea ya no consume un número; lo único que queda es la ventana entre el `nextval` y un crash antes del commit, que ninguna secuencia de PostgreSQL promete cerrar (ADR-0003 decisión 8) |
| Los códigos cero por definición | `NA`, `TASA_0` y `EXENTO` responden 0 **sin consultar** `tax_rate`, con la lista viviendo en la capa de tax (`TaxCodeService.ZERO_RATE_CODES`) que ahora leen ventas y compras por igual |
| Lo que **no** cubre | El **costo de inventario**: `purchase_order_details.unit_price` no llega a `cost_price` (`StockLedgerEngine.java:192`, `:822`), y decidir si un impuesto no deducible pertenece al costo es una decisión contable aparte. Y el **IVA frontera 8%** junto al 16%, porque `uq_tax_rate_company_code_from` permite una sola tasa por código y fecha. Lo que **no** es un hueco: el **proveedor exento**. El código de impuesto es del **producto** y un proveedor no declara condición fiscal en este dominio (`suppliers` no tiene columna para eso), así que el código que la línea copia es exactamente el que el producto declara — decisión del 2026-11-12 en `odd/tasks/purchase-order-line-tax.md`, issue #852 |

---

## Pantalla de orden de compra (dos pasos)

El alta y la edición dejan de ser una sola superficie con líneas adentro: la orden primero, las líneas después, cada una por su
propio request (`odd/tasks/purchase-order-create-then-lines.md`).

| Regla | Valor |
|-------|-------|
| El alta no tiene líneas | `/purchases/orders/create` renderiza **sólo el encabezado**. El área de detalle no existe hasta que la orden existe, porque una línea se direcciona por el id de la orden en el sub-recurso |
| Al guardar, la misma pantalla se vuelve la edición | `save()` en modo alta postea el encabezado con `details` **ausente** y navega a `/purchases/orders/{id}` con `replaceUrl: true`, así el operador no pierde el lugar |
| Cada línea se persiste sola | `POST` / `PUT` / `DELETE /api/purchase-orders/{id}/details` por fila: no hay guardado por lote ni staging local. La tabla emite lo que el operador hizo y la página persiste, con `linePending` / `lineErrors` por fila |
| La fila se reconcilia desde la respuesta | El id, el total y el impuesto son los que el servidor contestó, nunca los del payload; una edición optimista se restaura si el servidor la rechaza, y el error queda en esa fila, no en un banner general |
| La plata del documento se relee | `subtotal` / `taxTotal` / `totalAmount` los deriva `purchase_order_money()` **en la base** a partir de las filas, así que la página vuelve a leer la orden después de cada mutación (`refreshDocumentMoney`) y toma los tres números del servidor. Nunca los suma, y si la relectura falla lo dice en vez de dejar un total que describe otro conjunto de filas |
| El orden de las filas | `createdAt` y después `id`, en la carga y después de cada mutación: el API no ordena y el esquema no tiene ordinal |
| El catálogo se busca en el servidor | Un stream `merge(of(''), reset$, term$.debounceTime(300).distinctUntilChanged())` + `switchMap`, keyed en la compañía **de la orden guardada** (`loadedOrder().companyId`, con el cascade de respaldo) y el proveedor del formulario; menos de dos caracteres no emite request |
| Los tres estados vacíos son distintos | buscando / el proveedor no tiene productos / el término no coincide. «No hay productos» dejó de leerse como «nada coincide», que es lo que hacía el filtro en memoria |
| El encabezado se bloquea con la primera línea | Proveedor y tienda quedan fijos en `lineItems().length > 0`, con la razón visible al lado del control; medio de pago y comentarios siguen editables. El control se deshabilita **por el form** (`disable({ emitEvent: false })`), no con `[disabled]` sobre un `formControlName`, y el guardado lee `getRawValue()` para que el valor bloqueado igual viaje en el request |
| El guard cubre el encabezado solo | `hasUnsavedChanges` cuenta los cambios del encabezado: una línea ya se guardó por su propio request, así que contarla hacía preguntar por algo que ya estaba persistido |
| Lo que **no** cubre | No hay purga de borradores abandonados (decisión del operador, `T12`) y `by-supplier` sigue sin paginar (`T14`, diferido hasta que un término corto matchee suficientes filas para necesitarlo) |

---

## Docker Scripts

Scripts en `docker/scripts/`:

### setup-env.sh
Configura el entorno de Docker. Crea volúmenes, copia archivos de entorno, materializa `docker/secrets/*` desde los templates y valida Docker. En **todos** los entornos elimina las líneas de contraseñas de los `.env.*` (las contraseñas vienen solo de `docker/secrets/`).
```bash
./docker/scripts/setup-env.sh [dev|staging|prod]
```
- **dev**: Environment de desarrollo (puerto 9000)
- **staging**: Environment de staging (puerto 9100)
- **prod**: Environment de producción (puerto 9200)

### validate-env.sh
Valida la configuración del entorno (archivo .env, variables requeridas, puertos, Docker, `docker/secrets/*` materializados en todos los entornos).
```bash
cd docker && ./scripts/validate-env.sh            # dev, sólo warnings por drift de pins
cd docker && ./scripts/validate-env.sh prod --strict-pins   # falla si un pin diverge del template
```

> **Dos reportes, dos preguntas.** El script **reporta** —warning, nunca `ERRORS`, porque es el gate del start
de prod— cada key del template que el `.env.<env>` no declara (ya corre con el default del compose) y cada
`*_IMAGE` cuyo valor **difiere** entre el `.env.<env>` y `docker/.env.template`, nombrando la key y los dos
valores. Lo segundo es la única señal que existe para un env file materializado hace meses: `setup-env.sh`
sólo lo crea si falta, así que nada lo reconcilia, y un Tempo o un Loki de la generación anterior arrancaba
con un deploy verde. `--strict-pins` es la única forma de volverlo un fallo, y nunca es el default;
`env-image-pin-drift.test.sh` fija las dos mitades.

### deploy.sh
Script principal de despliegue. Build y start de servicios.
```bash
./docker/scripts/deploy.sh [dev|staging|prod] [start|stop|restart|build|build-images|up|logs|status|version|clean|health]

# Ejemplos:
./docker/scripts/deploy.sh dev start      # Build y start desarrollo
./docker/scripts/deploy.sh dev up         # Start sin build
./docker/scripts/deploy.sh dev build      # Solo build
./docker/scripts/deploy.sh dev logs      # Ver logs
./docker/scripts/deploy.sh dev status    # Estado de servicios
./docker/scripts/deploy.sh dev version   # Qué código corre cada imagen de app, contra HEAD
./docker/scripts/deploy.sh dev health     # Health check
./docker/scripts/deploy.sh dev clean      # Baja el stack: contenedores, red y volúmenes con nombre
./docker/scripts/deploy.sh dev clean --data  # Además borra ./volumes-<env>, el directorio de datos propio del entorno
```

> **`clean` no borra los datos de este stack: los deja, y ahora lo dice.** Cada directorio con estado es un
> **bind mount** bajo `$VOLUMES_ROOT` (`postgres/lifecontrol/data`, `keycloak-postgres/data`,
> `keycloak/realms`, `redis/data`, `loki/data`, `prometheus/*`, `tempo/*`, `grafana/*`), y
> `docker compose down -v` borra **volúmenes con nombre**, nunca un bind mount. En el compose base no hay
> ningún volumen con nombre, así que en dev y staging `clean` no toca un solo byte de datos: el comando
> imprime la ruta que sobrevivió y los directorios que están adentro, y el próximo `start` los reusa.
> `clean --data` es el único camino que los borra, y sólo borra el directorio propio de **este** entorno,
> `./volumes-<env>`: un directorio real, no un symlink. Lo hace detrás de una **segunda** confirmación que
> nombra la ruta exacta. Cualquier otra cosa —`VOLUMES_ROOT` absoluto (prod), otra ruta, o un symlink— se
> reporta y queda para el operador, porque ese borrado es manual a propósito, igual que en
> `cleanup.sh local`. En prod, además, keycloak-postgres, loki, prometheus, tempo y grafana **sí** usan
> volúmenes con nombre: ahí `clean` sí borra esos datos, y el primer aviso es el que lo dice.
> `clean-bind-mounts.test.sh` fija los dos veredictos.

> `health` ya no se conforma con `docker ps`: también falla si un target de Prometheus no está
> `up` o si Tempo no puede leer un trace ya flusheado, y avisa —sin fallar— cuando el store de
> traces todavía no tiene nada que leer. Ver
> [Observabilidad](#observabilidad-config-versionada-puertos-y-exporters).
>
> **`start` arranca postgres solo, aplica los roles y recién después levanta el resto.**
> `compose_run up -d lifecontrol-postgres`, después `docker/scripts/db-roles.sh <env>` y al final
> `compose_run up -d`. Un fallo de los roles aborta el start: una aplicación que conecta sin ellos no
> puede aislar un tenant. `up` y `restart` pasan por el mismo `start_services`, así que hay un solo
> lugar que arranca contenedores y un solo lugar que aplica los roles.
>
> **Qué código corre se reporta, no se adivina.** Cada imagen de app lleva bakeado
> `org.opencontainers.image.revision`, que `build_images` estampa con `HEAD` —o con `HEAD-dirty` si
> el árbol tiene cambios sin commitear, porque un build sucio **no** es ese commit—, y
> `deploy.sh <env> version` lo lee de vuelta: por servicio, el ID de la imagen que corre su
> contenedor, la revisión y un veredicto (`match`, `drift`, `dirty`, `unknown`, `absent`), y sale
> distinto de cero salvo que todas sean `match`. Se lee la imagen **por ID**, nunca por tag: un tag
> es un puntero que un build posterior mueve. `health` corre el mismo reporte antes de esperar
> salud, que es lo que cubre `start`, `up`, `restart` y `health` en un solo lugar: sin eso,
> `deploy.sh dev up` después de un `git pull` da health verde sobre imágenes de diez commits atrás.
> El reporte sólo avisa —nunca tumba un `start`, igual que el de pins—; `STRICT_REVISION=1|true|yes`
> lo vuelve un fallo, que es el switch que se pide a propósito. El nombre de la imagen **no** está
> hardcodeado: compose nombra una imagen construida `<project>-<service>` y el proyecto es por
> entorno, así que un `image:` explícito necesitaría el entorno en el tag o las tres instancias
> compartirían un tag local.


### db-roles.sh
Aplica los dos roles de base de datos de ADR-0005 a una base que **ya existe**.
```bash
./docker/scripts/db-roles.sh [dev|staging|prod]
```
> El directorio `/docker-entrypoint-initdb.d` de la imagen de postgres corre **una sola vez por datadir**,
así que un volumen que ya tiene datos (dev, hoy) no recibe los roles por esa vía. Este script ejecuta
dentro del contenedor el mismo wrapper del init, `docker/postgres-init/10-lifecontrol-roles.sh`, que
aplica `docker/postgres-init/lifecontrol-roles.sql`: **un solo cuerpo de SQL** para las dos rutas. Es
idempotente (el rename está guardado por `pg_roles`, los atributos se reafirman en cada corrida y el
final se lee de vuelta con un `RAISE`), y `deploy.sh <env> start` lo llama entre postgres y la
aplicación. El wrapper elige con qué rol aplicarlo **verificando `rolsuper`**, no sólo que conecte:
`lifecontrol_owner` es `NOSUPERUSER` a propósito, así que el único rol que puede aplicar el archivo en la
segunda corrida —y en cualquier migración futura que cree una extensión— es el superusuario de
mantenimiento, `lifecontrol_bootstrap`, que es además **el rol que crea `initdb`** (`POSTGRES_USER`):
PostgreSQL no deja quitarle `SUPERUSER` al rol que creó `initdb` (`The bootstrap superuser must have the
SUPERUSER attribute`), así que dueño y bootstrap no pueden ser la misma fila y el dueño lo **crea** el SQL
(`CREATE ROLE lifecontrol_owner LOGIN NOSUPERUSER`), le pone el password desde
`docker/secrets/lifecontrol_postgres_password` y le pasa la base con `ALTER DATABASE … OWNER TO
lifecontrol_owner` (sin eso el dueño no puede crear su primera tabla en `public`). El SQL le borra el
password al bootstrap (`ALTER ROLE … PASSWORD NULL`), porque la imagen le da a `POSTGRES_USER` el valor de
`POSTGRES_PASSWORD` —el secreto del dueño— y un superusuario alcanzable por TCP con la credencial de Flyway
es justo lo que el ADR prohíbe. Por eso **ninguna** otra cosa lo nombra: ni el wrapper de la API, ni el
exporter, ni un secret propio. Además **reporta** —sin fallar y sin escribir el archivo— cada key del
`.env.<env>` que nombre un rol que no es el suyo: el rename y el reparto no pueden editar un archivo
materializado, así que una key que todavía diga `lifecontrol_user` apunta a un rol que ya no existe, una
`LIFECONTROL_POSTGRES_USER` que diga `lifecontrol_owner` deja el volumen nuevo con el dueño como superusuario
bootstrap y el SQL muerto en el paso 3, y una `LIFECONTROL_DATABASE_USERNAME` que diga `lifecontrol_owner`
deja la API conectada como el dueño, sin policy que aplique. Y un volumen cuyo `initdb` creó
`lifecontrol_owner` **no se puede arreglar**: PostgreSQL nunca le quita `SUPERUSER` al rol bootstrap, así
que ese datadir hay que recrearlo una vez (es el caso del volumen de dev de hoy). `db-roles.test.sh` fija
las dos mitades.

### preflight-money-parts.sh
Reporta, antes de que exista la restricción, cada orden de venta cuyo `total_amount` no cuadra con la suma
de sus líneas.
```bash
./docker/scripts/preflight-money-parts.sh [dev|staging|prod]
```
> Es el paso 2 de la migración de ADR-0002 hecho ejecutable: «Without this step the constraint aborts the
> deploy on production data instead of on a report». Es **sólo lectura** y nombra un veredicto por orden,
> con las dos frases del ADR: un drift **dentro de una unidad mínima** de la moneda de esa orden es
> `rounding_adjustment` —la migración lo absorbe en esa fila y nadie decide—, y uno mayor es una **decisión
> manual por fila**, tomada antes de que corra la migración y no por la migración. Los códigos de salida son
> la otra mitad del contrato: **0** nada que decidir (sin drift, o todo absorbible por su propio
> `rounding_adjustment`), **2** al menos una decisión manual, **1** el reporte no pudo correr. El 2 no es 1 a
> propósito: un deploy tiene que poder distinguir "necesita un humano" de "no pudo preguntar", y un
> reporte que falla **nunca** se lee como un dataset limpio.
>
> Lee como **superusuario**, y no por comodidad: `sales_orders` está con `ENABLE` + `FORCE ROW LEVEL
> SECURITY` desde `V65`, y `apply_tenant_isolation` alcanza también al dueño, así que el mismo reporte
> corrido como `lifecontrol_owner` —el rol de Flyway— **lee cero filas e imprime un dataset limpio**, que es
> el modo de falla que ya registran `V54` y `V64`. Por eso resuelve el lector preguntando `pg_roles.rolsuper`
> en el orden del wrapper de roles, y el dueño **no** es candidato: uno que conecta y no lee nada es peor que
> ninguno.
>
> La suma de líneas se lee de `sales_order_items.subtotal`, que `V81` deriva y `ck_item_subtotal` hace
> cumplir, en vez de re-derivar la expresión inline del ADR: la plata de una línea tiene **una** definición,
> y dos formas de calcularla es cómo un reporte y una restricción terminan discrepando. La tolerancia sale
> de `currency.minor_units` (0.01 para `MXN`) y no del literal 1 de `ck_order_rounding_bounded`, que es la
> mitad agnóstica de la moneda. La comparación se hace en diezmilésimos enteros —la escala que toda parte
> lleva desde `V80`—, así que no hay redondeo ni coma flotante entre lo medido y el veredicto. **Nada lo
> llama todavía**: es el pre-flight que corre un operador antes de la migración que agrega `ck_order_total`
> (T6/T7 de `odd/tasks/money-first-class.md`). `preflight-money-parts.test.sh` lo fija offline, y el glob
> del job `Shell & Artifact Integrity` la cablea por existir.

### preflight-payment-methods.sh
Responde la pregunta que el paso 6 de ADR-0002 no puede responderse solo: **qué órdenes `COMPLETED` harían que
`V87` se niegue a dropear `sales_orders.payment_method_id`, y por qué**.
```bash
./docker/scripts/preflight-payment-methods.sh [dev|staging|prod]
```
> Es el pre-flight del paso **4** de ADR-0002 **y** el gate del **paso 6**, porque las dos dependen de la misma
> medición: el paso 4 inserta un `sale_payment` por orden `Completed` con «the order's `payment_method_id`», y
> el paso 6 dropea esa columna «once the payment rows are populated and every reader has moved». Una orden
> bloqueada no puede recibir fila de pago (`sale_payment.method_id` es `NOT NULL`, `V83`) y, mientras siga
> bloqueada, la columna es el único registro de que esa venta tomó plata — por eso `V87` se niega a dropearla y
> por eso el reporte es también el pre-flight de su guard. El paso 2 —el total contra la suma de las líneas—
> sigue siendo `preflight-money-parts.sh`, y ese reporte no contesta esto.
>
> **Qué es "se tomó plata" y qué es "bloquea el drop", leídos del árbol y no adivinados.** Se tomó plata es
> `sales_orders.status = 'COMPLETED'` —`SalesOrderService.java:461`, después del guard de `:426` que exige que
> los pagos no anulados cubran el total; `PENDING` es el estado transitorio que la misma transacción
> autopromueve y que en reposo no tiene plata—. Bloquea el drop es exactamente el predicado del guard de
> `V87`: una orden `COMPLETED` **sin fila de `sale_payment` no anulada** (`V83`: un void es una fila anexada en
> `sale_payment_void`, nunca una columna de estado). **No** es `net = total`, y la diferencia es la que importa:
> una venta reembolsada por completo tiene neto cero y **no** bloquea nada, porque sí tiene filas que dicen cómo
> se pagó. `enabled` **no** se filtra: una orden borrada lógicamente igual tomó plata.
>
> **Cada orden bloqueada declara uno de dos motivos, disjuntos a propósito** para que los conteos sumen y
> ninguna orden se nombre dos veces —los mismos dos conjuntos que `V86` nombra en sus `RAISE NOTICE`—:
> **`no_method`** (la orden no declara método y `sale_payment.method_id` es `NOT NULL`, así que el backfill no
> puede inventarlo: es el conjunto de **D-a**, y el motivo sólo existe mientras la columna exista) y
> **`zero_total`** (declara método y su total es 0, y `sale_payment.amount` lleva `CHECK (amount > 0)`). Una
> orden completada con método, total positivo y sin fila de pago **no** se lista: la escribe `V86`, así que no
> hay nada que decidir. D-a quedó **cerrado por medición** en `odd/tasks/money-first-class.md`, que nombra a
> este script como «the gate that would answer if a dataset ever appears».
>
> Imprime, por fila, lo que una decisión por orden necesita: id, `order_number`, `company_id`, el estado,
> `order_date`, **`updated_at`** —el `coalesce(o.updated_at, o.order_date)` con el que el paso 4 fecha
> `paid_at`, nunca `now()`, que fecharía todo pago histórico el día del deploy— `total_amount`, que es el
> `amount` que llevaría la fila inventada, y el **motivo**. Los veredictos son **`nothing_to_decide`** (ninguna
> orden bloqueada, así que el backfill no tiene nada que inventar y el paso 6 puede dropear) y **`manual`** (hay
> al menos una, y la decisión se toma por fila con la lista en la mano, antes del backfill y no por el
> backfill). Los códigos de salida son el mismo contrato de tres formas que el pre-flight del paso 2, por la
> misma razón: **0** nada que decidir, **2** al menos una decisión por fila, **1** el reporte no pudo correr (sin
> superusuario, psql falló, o el esquema no es el de este árbol) — nunca se lee como un dataset limpio.
>
> **Contesta desde las filas de pago, así que no se rompe cuando la columna se va.** El paso 6 dropea
> `payment_method_id`, y una sentencia que la nombrara como columna dejaría de parsear en cuanto la migración
> corriera —el reporte saldría **1**, que un operador lee como "la base está atrás" cuando en realidad está
> adelante—. Hay una sola `SELECT` (dos sentencias romperían el contrato de una sola lectura, y además podrían
> leer dos datasets distintos) y la columna se lee a través de la **imagen de la fila**,
> `to_jsonb(o) ->> 'payment_method_id'`, que es una búsqueda de clave en un documento JSON y no una referencia
> a columna: en un esquema sin la columna devuelve `NULL` en vez de fallar. Ese `NULL` no es la respuesta —una
> columna dropeada y una que no declara nada se ven igual—, así que un primer CTE le pregunta a
> `information_schema.columns` si la columna todavía existe y `no_method` sólo es alcanzable mientras exista.
> **Antes del drop pueden aparecer los dos motivos; después, sólo `zero_total`**, y la pregunta es la misma en
> los dos esquemas.
>
> **Un esquema que no puede leer es un rechazo, no un dataset limpio.** El reporte apunta al esquema del árbol
> que viaja: estado tipado de `V66`, `total_amount NUMERIC(19,4)` de `V80` y `sale_payment` /
> `sale_payment_void` de `V83` (la columna que dropea `V87` es opcional justamente porque el reporte la lee por
> la imagen de la fila). En una base más vieja —dev está en `V78`— la sentencia no parsea y el script sale **1**
> nombrando las piezas que necesita y las palabras de psql, porque sobre una base sin libro de pagos toda orden
> completada parece impaga y un "0 órdenes" ahí sería una respuesta que nadie pudo dar.
>
> Es **sólo lectura**: una sola `SELECT`, sin `CREATE`, `INSERT`, `ALTER`, advisory lock ni `SET` de sesión, y
> lee como **superusuario** por la misma razón que el otro pre-flight —`sales_orders` está con `FORCE ROW
> LEVEL SECURITY` desde `V65`, así que el dueño lee cero filas—. Ese lector está en
> `docker/scripts/_preflight_reader.sh`, que **los dos** reportes sourcean: una sola copia de los candidatos,
> del probe `rolsuper` y del porqué, para que no puedan divergir. `preflight-payment-methods.test.sh` lo fija
> offline, y el glob del job `Shell & Artifact Integrity` la cablea por existir.

### cleanup.sh
Limpia recursos de Docker y archivos locales.
```bash
./docker/scripts/cleanup.sh [stop|docker|volumes|local|builds|secrets|all|help] [dev|staging|prod]

# Opciones:
# stop    - Detener contenedores (conserva volúmenes, imágenes, redes)
# docker  - Limpia contenedores, imágenes y redes de Docker (conserva volúmenes)
# volumes - Limpia SOLO volúmenes con nombre (no toca los bind mounts de $VOLUMES_ROOT)
# local   - Borra ./volumes-<env>, el directorio de datos del propio entorno (un directorio real, no un symlink; nunca uno absoluto ni el de otro entorno)
# builds  - Limpia artifacts de build (./api-gateway/build)
# secrets - Rota los archivos de docker/secrets/* desde sus templates
# all     - Limpieza completa (requiere confirmación)
# help    - Muestra la ayuda
```

> **`docker` detiene el stack antes de limpiar**: su primer paso es `compose down --remove-orphans`.
> El prune de contenedores, imágenes y redes va filtrado por
> `label=com.docker.compose.project=<COMPOSE_PROJECT_NAME>`, así que no toca otros proyectos pero
> tampoco deja nada corriendo. `all` también baja el stack (vía `stop_containers`) y encima borra
> volúmenes y datos locales. Para volver a levantar sin rebuild: `./docker/scripts/deploy.sh <env> up`.

### Observabilidad: config versionada, puertos y exporters

| Regla | Valor |
|-------|-------|
| Config de Loki | `docker/config/loki/loki.yml`, montada **read-only** en `/etc/loki/config.yml` y **no** instalada en `$VOLUMES_ROOT` como `prometheus.yml`, `tempo.yml` y `datasources.yml`: declara `limits_config.retention_period: 720h` (30 días, el rango máximo que Loki responde) con `compactor.retention_enabled: true` y `delete_request_store: filesystem`. Una copia-if-missing no se refresca nunca, y la retención decide cuánto disco toma el host y cuánta historia sobrevive: eso va en un commit revisado. `loki-retention.test.sh` falla si `setup-env.sh` vuelve a copiarla |
| Puertos de observabilidad | Loki, Prometheus, Tempo, Grafana y los exporters publican **sólo en `127.0.0.1`**, en el compose base y en el override de prod: ninguno autentica a quien lo llama, así que un `HOST:CONTAINER` pelado los exponía a toda la red del host. Prometheus ya **no** corre con `--web.enable-lifecycle`: eso apaga `POST /-/quit` y `POST /-/reload`, que cualquiera con acceso al puerto podía disparar; un reload ahora es recrear el contenedor. `observability-port-binding.test.sh` lo fija |
| `deploy.sh <env> health` | Además de que cada contenedor esté `healthy`, falla si Prometheus reporta un target que no está `up` (`up == 0`) o si Tempo no puede leer un trace ya flusheado; si el store de traces es más joven que la ventana de la sonda (24h a 2h), imprime un aviso y no falla, porque todavía no hay nada que leer. `observability-probes.test.sh` fija los dos veredictos |
| Dashboards de Grafana | Se provisionan desde `docker/config/grafana/dashboards/` (un provider con `allowUiUpdates: false` y dos dashboards JSON), montados read-only en `/etc/grafana/provisioning/dashboards`; los datasources siguen viniendo del volumen que instala `setup-env.sh`. **El alerting también se provisiona desde el repo**, y es lo que hace utilizable una regla: `docker/config/grafana/alerting/{contact-points,policies,rules}.yml`, montados read-only en `/etc/grafana/provisioning/alerting` en los dos compose. Un contact point de **email** (`lifecontrol-email`), un único árbol de políticas que rutea todo hacia él (`group_by [job, alertname]`, `group_wait 30s`, `group_interval 5m`, `repeat_interval 4h`), y **una** regla: ratio de errores `SERVER_ERROR` por encima del 1% en 5 minutos, `for: 5m`, `noDataState: OK`, sobre `lc-prometheus`. La dirección **no** está escrita en el archivo: es `$__env{GRAFANA_ALERT_EMAIL}`, que los dos compose declaran con default, porque una variable vacía hace fallar **todo** el pase de provisioning (`could not find addresses in settings`) en vez de dejar la regla muda — el modo de falla correcto para un valor sin default sensato. En dev entrega en Mailpit, el mismo sink que las invitaciones de Keycloak; en staging/prod `SMTP_HOST`/`SMTP_PORT` apuntan a un relay real y la password viaja como secret montado, nunca como `environment:`. `docker/scripts/tests/grafana-alerting.test.sh` fija el cableado: que los tres archivos sigan montados, que el `receiver` de la política nombre el contact point que existe, y que el `datasourceUid` de la regla sea uno que `datasources.yml` declare — una regla no puede omitir su datasource como hace un panel. Lo que **no** hay: sink de webhook o de chat, porque no hay consumidor para ninguno, y una sola regla |
| Exporters de datastores | Dos `postgres-exporter` (uno por instancia: `postgres_exporter` mide un DSN por proceso) y un `redis-exporter`, con el puerto de host en loopback y la password por `*_FILE` desde el mismo secret que lee la base. Keys en `.env.template`: `POSTGRES_EXPORTER_IMAGE`, `POSTGRES_EXPORTER_PORT`, `KEYCLOAK_POSTGRES_EXPORTER_PORT`, `REDIS_EXPORTER_IMAGE`, `REDIS_EXPORTER_PORT`, `POSTGRES_EXPORTER_MEMORY_LIMIT` y `REDIS_EXPORTER_MEMORY_LIMIT` |
| Bloque de auth de Grafana | Salió de `docker/.env.template` (y de los `.env.<env>`): dos de esas keys **sí** las reenviaba el override de prod (`GF_AUTH_ANONYMOUS_ENABLED`, `GF_AUTH_DISABLE_LOGIN_FORM`), así que un `.env.prod` materializado desde el template podía correr Grafana con admin anónimo. Quedan como keys internas del compose, con el default del compose como único valor, y `image-pins.test.sh` falla si vuelven al template |

### Secretos
Todas las contraseñas viajan por archivos `docker/secrets/*` (NO por los `.env.*`) con el **mismo patrón en dev, staging y prod**: `setup-env.sh` materializa cada `*.template` (strip de comentarios, `chmod 0444`), compose los monta read-only en `/run/secrets/<nombre>`, y los wrappers en `docker/entrypoints/*.sh` leen el archivo y exportan la env var que la imagen espera (`KC_DB_PASSWORD`, `POSTGRES_PASSWORD`, `DATABASE_PASSWORD`, `FLYWAY_PASSWORD`, `GF_SECURITY_ADMIN_PASSWORD`, `KEYCLOAK_ADMIN_CLIENT_SECRET`).
- Secretos: `keycloak_postgres_password`, `keycloak_admin_password`, `keycloak_admin_client_secret`, `lifecontrol_postgres_password`, `lifecontrol_app_password`, `grafana_admin_password`.
- **Dos credenciales de base, dos trabajos (ADR-0005, decisión 3).** El pool conecta como `lifecontrol_app`, con el secret `lifecontrol_app_password` que el wrapper exporta como `DATABASE_PASSWORD` y que lee `spring.datasource`: no es dueño de nada y no puede saltear RLS, así que las policies aplican a toda conexión de la aplicación. Flyway corre como `lifecontrol_owner`, con el secret `lifecontrol_postgres_password` que el wrapper exporta como `FLYWAY_PASSWORD` y que lee `spring.flyway`, desde el mismo proceso: es dueño de los objetos y el único login que puede correr DDL, y su credencial nunca llega al pool. El nombre del pool lo pone el compose (`DATABASE_USERNAME`); el de Flyway es **literal** en el compose (`FLYWAY_USERNAME=lifecontrol_owner`), porque es el nombre que crea el SQL y `LIFECONTROL_POSTGRES_USER` ya no nombra al dueño: nombra el rol que crea `initdb`. Ninguna de las dos contraseñas es una key de `.env`, el template sólo lleva nombres de rol, y `app-credential-split.test.sh` falla si las dos credenciales se intercambian.
- **El tercer rol no es una credencial de servicio.** `POSTGRES_USER` —y el default del compose `${LIFECONTROL_POSTGRES_USER:-lifecontrol_bootstrap}`— es `lifecontrol_bootstrap`, el superusuario de mantenimiento, y es el rol que crea `initdb`. El SQL le borra el password en cada corrida (`ALTER ROLE … PASSWORD NULL`), porque la imagen le da a `POSTGRES_USER` el valor de `POSTGRES_PASSWORD`, que es el secreto del dueño: sin ese `ALTER`, el superusuario de mantenimiento sería alcanzable por TCP con la credencial de Flyway. Sin password sólo entra por el socket local (`trust`), y por eso ninguna otra pieza de la stack lo nombra como login: el exporter usa `lifecontrol_owner`, que es el rol al que pertenece `lifecontrol_postgres_password`.
- El nombre del rol dueño es `lifecontrol_owner` y no `lifecontrol_user`: el SQL renombra el rol viejo cuando existe (`ALTER ROLE … RENAME`) para que la propiedad de cada objeto sobreviva a un volumen existente, y lo **crea** `NOSUPERUSER` cuando no existe —que es el caso de todo volumen nuevo, porque `initdb` crea `lifecontrol_bootstrap` y PostgreSQL no deja quitarle `SUPERUSER` al rol que creó `initdb`.
- `validate-env.sh` valida los secretos en todos los entornos, y falla si un `*.template` no fue materializado (la iteración anterior sólo miraba entradas existentes, así que un secreto ausente pasaba el gate y fallaba dentro del contenedor); `deploy.sh start` en prod lo usa como gate.

### keycloak-setup.sh
Provisión idempotente del realm `life-control-realm`, los clients (`life-control-client` public + `life-control-admin-client` confidential con service account) y los roles (`lc-*` client roles, roles legacy de realm) vía `kcadm.sh` dentro del contenedor. Requiere Keycloak arriba y los secrets de keycloak materializados.
```bash
./docker/scripts/keycloak-setup.sh [dev|staging|prod]
```
> En dev la password del admin del realm la define `docker/secrets/keycloak_admin_password`; el service account del admin client toma `docker/secrets/keycloak_admin_client_secret`. Tras crear users en el realm hay que asignarles los roles correspondientes (ej. `lc-admin`) para que el frontend muestre los menús.
>
> El realm también declara el **entorno de invitación**: `frontendUrl` y el `smtpServer`, que es lo que hace que los emails de acción (invitación, verify-email, reset) lleguen y que su link apunte a Keycloak. **`frontendUrl` es la URL pública de Keycloak** (`KEYCLOAK_URL`), **no** el origen de la app: fija el **issuer** del realm que valida todo el stack (`KEYCLOAK_ISSUER_URI`) y la base del link de acción. El origen de la app vive en `WEB_APP_URL` y es lo que va en los `redirectUris`/`webOrigins` del client público; el link va a Keycloak y la persona vuelve a la app después por el claim `redirect_uri` del token de acción. Son valores distintos a propósito: apuntar `frontendUrl` al origen de la app rompe el issuer (HTTP 401 en todo token válido) y deja el link apuntando a la SPA, que no proxya `/realms/**`; dejarlo sin setear genera el link contra el host interno (`http://keycloak:8080`). `frontendUrl` se escribe como atributo (`attributes.frontendUrl`), no como campo raíz: Keycloak 26 lo rechaza con `Unrecognized field "frontendUrl"`. La convergencia del `smtpServer` y del `frontendUrl` se lee de vuelta del realm completo y aborta si el write no quedó (nunca se confía en el exit code). Los `redirectUris`/`webOrigins` del client público convergen de forma **aditiva**: se preserva todo lo ya registrado y sólo se agrega el origen que falte.
>
> **Variables SMTP** en `docker/.env.<env>` (en `docker/.env.template` con los defaults de dev): `SMTP_HOST`, `SMTP_PORT`, `SMTP_FROM`, `SMTP_STARTTLS`, `SMTP_AUTH` y, con auth, `SMTP_USER`. En dev apuntan al contenedor **Mailpit** (`SMTP_HOST=mailpit`, `SMTP_PORT=1025`), un servicio **dev-only** en `docker/docker-compose.override.yml` (nunca en el compose base ni en prod); su UI/API se publica en `MAILPIT_PORT` y el puerto SMTP no se publica al host.
>
> **Fail-closed fuera de dev**: si en staging/prod `SMTP_HOST` sigue resolviendo al nombre del contenedor de dev (`mailpit`) — sea por valor ausente, comentado o vacío, porque `get_env` cae al default — el script aborta antes de escribir nada. Si no, cada invitación de producción terminaría en el buzón local. Dev sí puede apuntar a un relay real.
>
> **Fail-closed de `frontendUrl` fuera de dev**: si en staging/prod `KEYCLOAK_URL` sigue resolviendo a un default local (`localhost`, `127.0.0.1` o el nombre interno `keycloak`) — por valor ausente, comentado o vacío, porque `get_env` cae al default — el script aborta antes de escribir nada, nombrando la variable. Su modo de falla es peor que el de SMTP: un `frontendUrl` equivocado cambia el issuer del realm en silencio y rompe la autenticación de todo el stack con HTTP 401.
>
> La credencial SMTP es `docker/secrets/smtp_password`, leída directo como `keycloak_admin_password` y **sólo requerida cuando `SMTP_AUTH=true`** (si falta, está vacía o conserva `CHANGEME`, aborta). No tiene `.template` a propósito: `setup-env.sh` materializa todo template y `validate-env.sh` rechaza `CHANGEME`.
>
> **Verificación end-to-end** (manual, nunca wired en CI): con el stack arriba y `keycloak-setup.sh` corrido, `./docker/scripts/verify-invitation-flow.sh dev` obtiene un token del service account admin, crea un user descartable, dispara `execute-actions-email` con `["VERIFY_EMAIL","UPDATE_PASSWORD"]` y comprueba por la API REST de Mailpit que el mail llegó. Después hace tres verificaciones reales: que la base del link sirva **Keycloak** (el discovery en `<link-base>/realms/<realm>/.well-known/openid-configuration` devuelve un `issuer` igual a esa base — en el origen de la app devuelve el HTML de la SPA), que el `issuer` del realm sea el que valida el stack (`KEYCLOAK_ISSUER_URI`), y que el claim `redirect_uri` del token de acción (emitido por Keycloak 26 como `reduri`) sea `WEB_APP_URL`. Borra el user al salir y sale distinto de cero ante cualquier fallo.
>
> **El flujo de provisioning tiene su propio veredicto de punta a punta**, hermano del anterior y con la misma forma (manual, dev-only, datos descartables, limpieza al salir, exit distinto de cero ante cualquier fallo): `./docker/scripts/verify-provisioning-flow.sh dev`. Es el que contesta la pregunta que originó la investigación del 2026-10-19 — *¿se mandó el correo de activación?* — en una corrida en vez de cuatro queries SQL y una mirada a Mailpit. Crea **su propio** empleado, contrato y store assignment por la API (el mismo `POST /api/companies/{id}/employees` y `.../contracts` que usan las pantallas), con un token de operador que lleva `lc-admin` y el claim `company_id`; espera a que el worker aplique la tarea hasta `APPLIED`; encuentra la invitación en Mailpit con su link y comprueba que la base del link sirva Keycloak; y verifica la cuenta de Keycloak: el id linkeado, sus `requiredActions`, los roles que declara el template de la posición y los cinco claims `company_*` (presentes exactamente en los niveles que la derivación sostiene, ausentes en los que no). Si el stack tiene el worker apagado **no espera**: lo dice y nombra `WORKER_ENABLED`, porque esperar una tarea que nadie va a reclamar es el estado silencioso que este script existe para eliminar. El operador es lo único que tiene que traer: por default el script crea un operador descartable (`lc-admin` + el atributo `company_id`, password aleatoria que nunca imprime y no escribe a disco) y lo borra al salir; con `PROVISIONING_VERIFY_TOKEN=<jwt>` usa un token que le pases y no escribe **ninguna** identidad en el realm. Lo que deja asentado y no cubre: las pantallas (maneja la API que ellas llaman), y un template de posición sin roles sólo prueba que la cuenta no tiene ninguno.

### Claims de tenancy (`company_*`) en el token

El guard de alcance de `life-control-api` **no lee la base**: `CurrentUserContext` resuelve company, country, region, zone y store leyendo cinco claims **en la raíz del JWT** — `company_id`, `company_country_id`, `company_region_id`, `company_zone_id`, `company_store_id`. `keycloak-setup.sh` provee lo que hace que existan, en tres piezas que tienen que estar las tres o ninguna sirve:

1. **Un protocol mapper por claim** en el client público `life-control-client`: `oidc-usermodel-attribute-mapper`, **multivaluado** y a nivel raíz. Los nombres tienen que coincidir con los de `ScopeLevel.claim()`; `KeycloakClaimMapperCoverageTest` acopla el script al código en las dos direcciones, así que un claim que el código lea sin mapper en el script rompe un test en vez de romper en runtime.
2. **`unmanagedAttributePolicy=ADMIN_EDIT`** en el user profile del realm. Sin esto Keycloak 24+ **descarta los atributos en silencio**: la Admin API responde **204** y no guarda nada, el mapper no emite, y el guard responde 403 sin que ningún test de Java lo vea. `ADMIN_EDIT` (nunca `ENABLED`) deja escribir a los endpoints de administración y evita que el sujeto se auto-asigne su propia tenancy.
3. **El atributo del usuario**, que escribe el flujo de aprovisionamiento de acceso.

**Verificar de punta a punta**, con el stack arriba (`./docker/scripts/keycloak-setup.sh dev` primero):

```bash
# 1) El atributo. OJO: `kcadm update users/<id>` NO escribe atributos de usuario — devuelve 0 y
#    los deja vacíos. Hay que usar la Admin REST API con un token de master:
#    PUT /admin/realms/life-control-realm/users/<id>
#    {"attributes": {"company_id": ["<uuid>"], "company_country_id": ["<uuid>"], ...}}

# 2) Un token real por password grant (el client público tiene directAccessGrantsEnabled):
curl -s -X POST "http://localhost:8181/realms/life-control-realm/protocol/openid-connect/token" \
  -d client_id=life-control-client -d grant_type=password -d username=<user> -d password=<pass>

# 3) Decodificá el payload y mirá la RAÍZ del token: los cinco claims tienen que estar, cada uno
#    como ARRAY JSON. Un atributo con dos valores (dos tiendas) es la prueba de que `multivalued`
#    funciona: sin ese flag Keycloak colapsa la lista al primer valor y el caller pierde alcance
#    en silencio.
```

> Un caller con solo `lc-department` o `lc-position` recibe **403** cuando el claim falta y **201** cuando está. Si ves 403 en un endpoint scoped, mirá primero el token y no los roles.

### Imágenes de la stack: pins, healthchecks y volúmenes

| Regla | Valor |
|-------|-------|
| Fuente versionada de los pins | `docker/.env.template` (los `.env*` están gitignoreados: son materialización local) **y** el default `${VAR:-…}` de los compose |
| Chequeo | `./docker/scripts/tests/image-pins.test.sh`: falla si el template y los defaults divergen, y es el que fija la lista de versiones vigente. Bumpear una imagen es editar las dos cosas y su tabla, en el mismo commit |
| Loki y Tempo no llevan `healthcheck` | Desde `grafana/loki` 3.6.0 y `grafana/tempo` 3.x las imágenes son distroless (`gcr.io/distroless/static`): sin shell ni `wget`, así que un `CMD wget …` queda `unhealthy` para siempre. La señal se mide desde el host en `deploy.sh health`, con `curl` |
| Loki guarda en `/loki` | Es el `path_prefix` de su config empaquetada. El mount apunta a `/loki` y `setup-env.sh` deja ese directorio (más `rules/` y `rules-temp/`) con dueño `10001:10001`, el uid de la imagen distroless |
| Volumen de Loki en prod | Es un named volume: si existe de antes (creado para `/loki/data`) hay que borrarlo una vez, porque Docker no lo repuebla desde la imagen y queda vacío y root-owned, que es justo lo que Loki no puede escribir |
| Exporters de datastores | `postgres-exporter` y `redis-exporter` se pinnean como el resto (`POSTGRES_EXPORTER_IMAGE` y `REDIS_EXPORTER_IMAGE`, en el template **y** en el default del compose) y **no** llevan `healthcheck`: la imagen del exporter de Redis es distroless, así que un probe in-container no puede pasar. La señal de vida es `up` en el scrape, y `exporters.test.sh` lo fija |
| Pins divergentes en un `.env.<env>` | Un `*_IMAGE` que el env file declara con un valor que el template ya no nombra **se reporta**: `validate-env.sh <env>` lo lista nombrando la key y ambos valores, y `deploy.sh <env> start` imprime el mismo reporte antes de buildear, así que un pin viejo no sobrevive un deploy en silencio. `env-image-pin-drift.test.sh` fija que el reporte nombre la key y los dos valores, que un env file alineado no produzca ningún warning y que el reporte sea read-only y devuelva 0 siempre |
| Qué código corre cada imagen de app | El label `org.opencontainers.image.revision` es la única afirmación que una imagen hace sobre su propio código, y hasta `docker-image-revision` nada lo leía de vuelta. Lo estampa `image_revision_stamp` (`<sha>`, `<sha>-dirty`, o `unknown` cuando git no puede responder) y lo verifica `deploy.sh <env> version`, que sale distinto de cero salvo que toda imagen de app sea `match` contra `HEAD`. `health` corre el mismo reporte y sólo avisa; `STRICT_REVISION` lo vuelve un fallo. `image-revision.test.sh` fija los cinco veredictos, los dos stamps y que los tres Dockerfiles sigan bakeando ese label exacto |
| Roles de la base de datos (`postgres`) | La imagen de postgres corre `/docker-entrypoint-initdb.d` **una sola vez por datadir**, así que un volumen con datos nunca vuelve a pasar por ahí: los roles los define `docker/postgres-init/lifecontrol-roles.sql`, que se monta en `/opt/lifecontrol/` y **fuera** del directorio de init (el glob del entrypoint lo correría una segunda vez), y lo aplican los dos caminos — `postgres-init/10-lifecontrol-roles.sh` en un volumen vacío y `docker/scripts/db-roles.sh <env>` en uno existente. `POSTGRES_USER` (default `${LIFECONTROL_POSTGRES_USER:-lifecontrol_bootstrap}`) es el rol que crea `initdb`, y **es el de mantenimiento, no el dueño**: PostgreSQL rechaza quitarle `SUPERUSER` al rol bootstrap, así que el dueño lo crea el SQL (`CREATE ROLE lifecontrol_owner LOGIN NOSUPERUSER`, o el rename del pre-ADR), le pone el password desde `docker/secrets/lifecontrol_postgres_password` y se queda con la base por `ALTER DATABASE … OWNER TO lifecontrol_owner` (sin eso no puede crear su primera tabla en `public`). El `CREATE EXTENSION IF NOT EXISTS btree_gist` va **antes** del `ALTER ROLE lifecontrol_owner NOSUPERUSER` porque `V21__employee_contracts.sql:27` lo necesita: al revés, la primera migración de toda base nueva falla. Al bootstrap el SQL le borra el password en cada corrida, así que sólo entra por el socket local (`trust`) y ninguna otra pieza lo nombra. Ni el dueño queda superusuario (un superusuario siempre saltea RLS) ni la app puede (`NOBYPASSRLS`), y el credential de la app es `docker/secrets/lifecontrol_app_password` |

> **Subir una imagen**: editá `.env.template`, los defaults de `docker-compose*.yml` y la tabla de
> `image-pins.test.sh`; corré los tests de `docker/scripts/tests/`; en dev, `./docker/scripts/deploy.sh dev up`
> y `./docker/scripts/deploy.sh dev health`. En staging/prod el cambio del template no toca nada hasta que el
> entorno se re-materialice: un `.env.<env>` existente **no** se reescribe solo (`setup-env.sh` sólo lo crea si
> falta), así que el operador lo actualiza a mano — y a partir de este cambio el deploy le avisa: un pin que el
> template ya no nombra sale como warning en `validate-env.sh <env>` y en `deploy.sh <env> start`, con la key y
> los dos valores (`image-pins.test.sh` falla en la máquina donde el archivo existe; en CI no hay `.env.<env>`,
> que es justo por lo que el reporte vive en los scripts).
>
> **Esa comodidad tiene una consecuencia, y es la que hay que leer dos veces**: toda key que el `.env.<env>` no
> declare corre **en silencio** con el default `${VAR:-…}` de los compose, así que el entorno no queda "sin" ese
> ajuste — queda con el default, sin que nada lo diga. Es exactamente lo que dejó el worker de
> provisioning apagado en dev sin que nadie lo viera (`WORKER_ENABLED=false` por el default del compose base):
> el default era correcto y aun así una tarea `PENDING` y una tarea que nadie va a reclamar eran
> indistinguibles. Para ver qué corre en lugar de qué, `./docker/scripts/validate-env.sh <env>` **reporta**
> —warning, nunca `ERRORS`, porque el script es el gate del start de prod— cada key del template que el env
> file no declara, con el default del compose al que cae; y las tres suites
> `docker/scripts/tests/env-defaults.test.sh`, `env-report-robustness.test.sh` y `worker-dev-default.test.sh`
> fijan que cada key del template tenga un consumidor declarado o un default, que el reporte no invente lo que
> no midió, y que el default de dev del worker no se pueda apagar sin que un test lo diga.

### Service URLs

> **Source of truth**: Service URLs are defined by port variables in `docker/.env.<env>` files.
> The scripts derive URLs from these env vars at runtime. The table below is a human-readable reference only.

| Environment | API Gateway | Actuator | Keycloak | Grafana | Prometheus | Loki | Tempo |
|-------------|-------------|----------|----------|---------|------------|------|-------|
| dev         | localhost:9000 | localhost:9001 | localhost:8181 | localhost:3000 | localhost:9090 | localhost:3100 | localhost:3110 |
| staging     | localhost:9100 | localhost:9101 | localhost:8281 | localhost:3100 | localhost:9190 | localhost:3200 | localhost:3210 |
| prod        | localhost:9200 | localhost:9201 | localhost:8381 | localhost:3200 | localhost:9290 | localhost:3300 | localhost:3310 |

> Los puertos de observabilidad (Grafana, Prometheus, Loki, Tempo y los exporters) se publican en
> `127.0.0.1` en todos los entornos: sirven desde el host y no son alcanzables desde la LAN. El
> detalle, y por qué, está en
> [Observabilidad](#observabilidad-config-versionada-puertos-y-exporters).

---

## Development Commands

### Backend (Gradle)
```bash
./gradlew build
./gradlew bootRun
./gradlew test
./gradlew bootJar
```

### Frontend (Angular)
```bash
cd life-control-app-angular
npm install
npm start                   # dev server (localhost:4200)
npm run build
npm run lint                # ESLint + Prettier
npm test                    # unit tests (Vitest)
npm run test:coverage:check # unit + enforcement de umbrales de cobertura (local)
npm run test:e2e            # E2E con Playwright (mocks de Keycloak y API)
```

> **`npm run test:e2e` con el stack de dev arriba**: Playwright reusa el servidor que ya escucha en `E2E_PORT` (4200 por default) cuando no hay `CI`, así que si el contenedor `life-control-app-angular` está corriendo ahí, la suite mide **esa imagen** y no tu worktree. El síntoma es engañoso: fallan justo las aserciones nuevas, con el DOM de una revisión anterior. Corré `E2E_PORT=4300 npm run test:e2e` en vez de bajar el stack.

> CI: `.github/workflows/angular-ci.yml` corre `Lint & Build` y, en dos shards en paralelo, los unit
> tests; un tercer job (`Coverage`) recombina los reportes blob de los shards con
> `npm run test:coverage:merge:check` y falla si el merge baja del umbral — el comando local
> `test:coverage:check` sigue siendo el mismo gate, sin shardear. Los dos triggers
> filtran por los **mismos** paths (`life-control-app-angular/**` y el archivo del propio workflow), y lo
> mismo vale para `api-ci`, `gateway-ci` y `docker-build-integrity`: la fuente es el bloque `paths:` de
> cada uno, y `.github/scripts/tests/workflow-trigger-parity.test.sh` falla si un `push` y un
> `pull_request` dejan de listar lo mismo. En consecuencia, un PR que no toca ningún path listado no corre
> ese workflow y su merge a `main` tampoco: un `gh pr checks` sin ningún check de componente significa que
> el merge no los va a correr, y sólo el guard de labels —que a propósito no filtra— corre siempre.
> El job `Shell & Artifact Integrity` de `docker-build-integrity` corre **todas** las suites de
> `docker/scripts/tests/*.test.sh` por glob —antes nombraba cinco, así que una suite nueva corría en una
> laptop y nunca en CI—, imprime cuántas encontró y falla si el glob no matchea nada: una suite queda
> cableada por existir. La corrida del 2026-10-31 levantó **23 suites**; el conteo se lee de la corrida
> (`for s in docker/scripts/tests/*.test.sh; do "$s" >/dev/null || echo "FAILED $s"; done`) y no se copia acá.

### Docker

Flujo canónico vía scripts (ver [Docker Scripts](#docker-scripts) arriba):

```bash
# Setup de entorno (una vez por entorno)
./docker/scripts/setup-env.sh dev        # dev | staging | prod
./docker/scripts/deploy.sh dev start     # Build + start
./docker/scripts/deploy.sh dev status    # Estado
```

> **Invariante de orden**: las imágenes de `api-gateway` y `life-control-api` no compilan:
> su Dockerfile hace `COPY` de un JAR ya buildeado. No corras `docker compose build` para
> esos servicios antes de generar sus JARs. `./docker/scripts/deploy.sh <env> start` hace el
> orden correcto (compila y después construye las imágenes). `docker compose build` por sí
> solo es seguro únicamente para `web-app`, que compila Angular dentro de su Dockerfile.

Equivalente directo con `docker compose` v2 y `--env-file`:

```bash
cd docker
docker compose -f docker-compose.yml -f docker-compose.override.yml --env-file .env.dev up -d
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod up -d
```

---

## Commit & Pull Request Guidelines

Follow conventional-commit style: `<type>[scope]: <description>`

**Types:** `feat`, `fix`, `docs`, `chore`, `perf`, `refactor`, `style`, `test`

The full flow — issue-first with an approved issue, the one `type:*` label, the ~400-line review budget and chained slices, the worktree convention, the ODD evidence record, the CI gates and the branch-protection runbook — starts in [CONTRIBUTING.md](CONTRIBUTING.md).

---

## Component-Specific Guidelines

Para cada componente activo, ver su propio `AGENTS.md`:
- `life-control-api/AGENTS.md` - Spring Boot 3 patterns + NO Lombok (records, constructor injection)