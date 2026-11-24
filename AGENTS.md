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
| `angular-21` | Angular patterns (signals, standalone, control flow) |
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
| Qué **no** reemplaza | Nada del flujo de verificación: las suites de `.github/scripts/tests/`, `./gradlew spotlessCheck spotbugsMain` y `./gradlew test` en cada módulo Java, `npm run test:coverage:check` y el `PR Label Guard` siguen siendo los gates. El grafo dice **por dónde empezar**, nunca **qué alcanza** |
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
| Hook local | `git config core.hooksPath .githooks` (una vez por clon; los worktrees linkeados comparten la config). `.githooks/pre-commit` rechaza un commit en la rama por defecto, resuelta desde `origin/HEAD` —no hardcodea `main`—, deja escapar con `ALLOW_MAIN_COMMIT=1` el commit que decidas a propósito, y formatea desde `life-control-app-angular/` con `npx lint-staged` cuando esa app tiene `node_modules`. Verificado por `.github/scripts/tests/worktree-anchor.test.sh` |
| Los dos valores de `core.hooksPath` | Un clon puede quedar con `.githooks` (lo pone el comando de arriba) o con `life-control-app-angular/.husky/_` (lo escribe el `prepare` de `life-control-app-angular/package.json` en cada `npm install`), y **las dos garantías —guard de anchor y formateo— son las mismas con cualquiera de los dos**, así que ninguno de los dos valores es un clon mal configurado. Detrás de `.husky/_` corre `life-control-app-angular/.husky/pre-commit`, que no formatea nada por su cuenta: ejecuta `bash ../../.githooks/pre-commit` y sale con su código. Por eso leer el valor con `git config --show-origin --get core.hooksPath` y encontrarlo distinto del documentado no es un hallazgo: si el guard no actuó, se mira el hook, no el valor |
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
| Qué prueba una cita | **Contenido** (`patch-id`, `tree:`) o el **merge commit**, nunca el SHA de una rama: una reescritura posterior lo deja nombrando un commit que no está en `main`, y sigue resolviendo como objeto, así que se lee como evidencia. La regla se declara una sola vez en cada mitad —la rama en `references/worktrees.md`, el registro en `references/feature-records.md`— y `.github/scripts/tests/odd-citations.test.sh` la hace cumplir sobre cada commit que un registro cita |
| Cuántos PRs por unidad | **Uno**: el registro viaja en el PR que trae el trabajo y su header queda final ahí. No existe un PR de seguimiento para mover el header a una forma mergeada: medido, esa práctica costó 51 de los últimos 150 PRs mergeados, 40 de ellos reescribiendo un solo registro, cada uno con su propio issue `status:approved`. **Y cómo convive con el presupuesto de review, que es la otra regla que parece contradecirlo**: los cortes son **commits dentro de ese PR**, una cadena es para unidades independientes entre sí, y el registro nunca lleva un PR propio (`CONTRIBUTING.md` §3) |
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
| Suite del guard | `.github/scripts/tests/*.test.sh` (**17 suites**; el conteo se lee de la corrida y no se copia acá — `for s in .github/scripts/tests/*.test.sh; do "$s"; done` reportó **364 aserciones** el 2026-10-21, y este renglón decía 245 y después 290 y después 312 y después 350 mientras el árbol ya tenía más), offline: el mapeo completo, el marcador `!`, los cinco modos de falla del guard, la idempotencia y la coincidencia plantilla↔vocabulario, más el audit de headers de `odd/tasks/*.md` con su vocabulario cerrado y su paridad con el contrato, la referencia al issue, los nombres de job, las plantillas de issue, la paridad de `paths:` entre workflows y que el catálogo de versiones esté en los tres que buildean los módulos, los pins de los workflows (scope del token, cada action anclada a un SHA y la imagen de shellcheck), los pins de Gradle (los dos wrappers en la misma versión y las cinco versiones de plugin del catálogo compartido) y que ninguna versión de plugin se escriba de vuelta en un `build.gradle`, la barrera de formateo del job `Lint & Build` y la regla del anchor con su hook manejado como programa, el procedimiento de arranque de sesión y la lista de lo que el anchor puede ejecutar). Corre en el mismo workflow, y su paso `ShellCheck` lintea todo `.github/scripts/` y `.githooks/` por glob, así que una suite nueva queda cableada por existir y el hook se lintea aunque no lleve extensión `.sh`; cuántos archivos son y qué otras raíces están cubiertas, en el renglón siguiente |
| Superficie de shellcheck | Dos globs y una lista congelada. Las raíces que **tienen que** quedar cubiertas son `EXPECTED_LINT_SURFACES` en `.github/scripts/tests/workflow-pins.test.sh` (`.github/scripts`, `.githooks`, `docker/scripts`, `k8s`), y la suite falla nombrando la raíz que ningún glob cubre. Quién cubre qué: `.github/scripts/**` y `.githooks/*` el paso `ShellCheck` del job guard de `pr-label-guard.yml`; `docker/scripts/**` el job `Shell & Artifact Integrity` de `docker-build-integrity.yml`; y `k8s/**` el mismo guard desde el issue #955. Que una raíz la cubra un glob o el otro no es el contrato — que **alguna** la cubra sí. El `k8s/**` vive en el guard y no en el job de integridad porque el guard no declara filtro `paths:` y corre en **todo** PR, mientras el de integridad filtra por `docker/**` y dos archivos de build: ahí un cambio que sólo toca `k8s/` no se lintaría nunca. El caso que creó el renglón: dos scripts de `k8s/kind/` sin shebang, ejecutados y linteados por nadie, hasta que #955 los nombró. Cuántos archivos hay se lee de la corrida y no se copia acá |
| Qué no hace | El guard **no** está en branch protection: reporta en cada PR, pero nada obliga a esperarlo antes de mergear hasta que las reglas de protección lo nombren. Tampoco valida el issue linkeado ni el cuerpo del PR |
| Citas desde `docs/` hacia migraciones | `adr-migration-citations.test.sh` recorre cada cita `path:line` que `docs/` escribe hacia `life-control-api/src/main/resources/db/migration/` y falla si la versión no existe o nombra dos archivos, si el rango se pasa del archivo, o si la línea no nombra en backticks nada que declaren las migraciones que su párrafo cita. El nombre se busca en el archivo entero y no en el rango citado, porque una cita puede nombrar la tabla cuya columna está en esa línea: por eso no ve un rango que sobra ni una cita a las líneas equivocadas del archivo correcto, y no juzga un nombre que ninguna migración declara |

---

## Project Overview

LifeControl es un sistema de gestión consolidado en un monolito modular `life-control-api`.

| Component | Location | Tech Stack | Estado |
|-----------|----------|------------|--------|
| API Gateway | `api-gateway/` | Spring Boot | Activo |
| Life Control API | `life-control-api/` | Spring Boot, Java 21, PostgreSQL | **Activo** — núcleo del sistema |
| Angular App | `life-control-app-angular/` | Angular 22.2.3, Material, Vitest + Playwright, ESLint/Prettier | Activo |

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
| Por qué la tasa es columna y no un join | `tax_rate` es append-only por **(compañía, código, alcance, fecha)** —el alcance es una región de la empresa o «toda la compañía», `V95`— y el resolver toma la última tasa ≤ la fecha del documento dentro de ese alcance, así que un join reescribiría un documento histórico en cuanto se publica una tasa nueva. La copia es lo que hace que un cambio de tasa no reescriba nada — `ADR-0002 §Decisión 6`, y el alcance en la sección siguiente |
| Editar no re-resuelve | Cambiar cantidad o precio de **una fila que existe** conserva `tax_code` y `tax_rate` guardados y re-deriva desde **esa** tasa. La excepción es mover la línea a **otra variante**: ya no es el artículo cuya tasa se copió (`SalesOrderService.java:380-384`). Y un **reemplazo completo** (`PUT` con todas las líneas) no es una edición: borra las filas y crea nuevas, así que cada una vuelve a copiar contra `created_at`. Eso mueve el snapshot sólo si cambió el `tax_code` del producto o si se publicó una tasa con `effective_from <= created_at` **después** del alta; una tasa posterior a la fecha del documento no lo mueve. Está fijado por un test, no dejado a la memoria (`L14`) |
| El rechazo, y lo que la secuencia sí pierde | Un código gravable sin tasa habilitada en la fecha del documento es **400** que nombra el código y la fecha, y **no deja fila**: en el alta y en el alta de línea se lanza antes de la primera escritura; en el reemplazo completo las líneas viejas se borran antes, y lo que impide que quede un documento a medio reemplazar es la transacción, no el orden de las sentencias. El número de documento es otra cosa: `createPurchaseOrder` lo pide **después** de resolver todas las líneas (issue #857), así que un rechazo de una línea ya no consume un número; lo único que queda es la ventana entre el `nextval` y un crash antes del commit, que ninguna secuencia de PostgreSQL promete cerrar (ADR-0003 decisión 8) |
| Los códigos cero por definición | `NA`, `TASA_0` y `EXENTO` responden 0 **sin consultar** `tax_rate`, con la lista viviendo en la capa de tax (`TaxCodeService.ZERO_RATE_CODES`) que ahora leen ventas y compras por igual |
| Lo que **no** cubre | El **costo de inventario**: `purchase_order_details.unit_price` no llega a `cost_price` (`StockLedgerEngine.java:192`, `:822`), y decidir si un impuesto no deducible pertenece al costo es una decisión contable aparte. Y el **IVA frontera 8%** junto al 16%, porque `uq_tax_rate_company_code_from` permite una sola tasa por código y fecha. Lo que **no** es un hueco: el **proveedor exento**. El código de impuesto es del **producto** y un proveedor no declara condición fiscal en este dominio (`suppliers` no tiene columna para eso), así que el código que la línea copia es exactamente el que el producto declara — decisión del 2026-11-12 en `odd/tasks/purchase-order-line-tax.md`, issue #852 |

---

## Tasas de impuesto por región (`tax_rate`)

El IVA 8% de la región fronteriza y el 16% del resto del país son **el mismo impuesto con otra tasa**, y lo que
discrimina no es la compañía sino **dónde ocurre el acto**. Hasta `V95` la tabla contestaba por compañía entera, así
que una empresa con operación en las dos zonas no tenía dónde declarar la segunda tasa: el resolver acertaba al
resolver y devolvía 16% para todo. `odd/tasks/tax-rate-region-axis.md` (issue #855).

| Regla | Valor |
|-------|-------|
| El eje es la **región de la tienda** | `tax_rate.company_region_id` (nullable) apunta a una `company_regions` de la **misma** compañía; `NULL` es la tasa de toda la compañía, que es lo que son todas las filas anteriores. La región se lee de la cadena que ya existía: `company_stores.company_zone_id` → `company_zones.company_region_id`. El **código** no se mueve: sigue en el producto (`products.tax_code`), porque *qué* se vende es del artículo y *cuánto* es del lugar |
| La precedencia | El resolver recibe `(compañía, código, región, fecha)`: primero la última fila **de esa región** con `effective_from <= fecha`; si la región **no tiene ninguna fila habilitada** para ese código, la de la compañía (− comportamiento de siempre, sin cambios); y si la región **tiene filas habilitadas** pero ninguna aplica a esa fecha, **se rechaza** — nunca se cae a la de la compañía, que en la frontera es justo el 16% que no se debe cobrar. Quién tiene autoridad lo deciden las filas **habilitadas**: retirar la última le devuelve el código a la compañía, y volver a habilitar una se la devuelve a la región (`odd/tasks/tax-rate-region-authority.md`, issue #1030) |
| El rechazo | `TaxRateRegionGapException` → **400** que nombra el código, la región y la fecha del documento. La existencia que dispara el rechazo mira **`enabled` pero no la fecha**, y esa asimetría es el contrato: una región con una fila habilitada **posterior** al documento igual declaró que ese código es suyo y se rechaza (es `D2`), mientras una región con todas sus filas retiradas ya no declaró nada y cae a la tasa de la compañía. La autoridad se entra y se sale, no es un flag de una sola dirección |
| Dos tasas para un mismo código y fecha | Se puede, y lo que las distingue es el alcance. La unicidad son **dos índices parciales** (`uq_tax_rate_scope_from` para `company_region_id IS NULL`, `uq_tax_rate_region_scope_from` para el resto) y **no** una key compuesta: PostgreSQL trata los NULL como distintos, así que un compuesto `(company_id, tax_code, company_region_id, effective_from)` dejaría pasar dos filas «de toda la compañía» el mismo día — el modo de falla que la key original existía para evitar. **Desde `V114` los dos llevan además `AND enabled`**: la clave es sobre las filas habilitadas, así que retirar una libera su día y corregir un valor es «retirar y volver a declarar el mismo día» (`odd/tasks/tax-rate-same-day-correction.md`, issue #888), mientras dos filas **habilitadas** para el mismo día y alcance siguen siendo imposibles. `TaxRateMigrationIntegrationTest` fija las dos mitades |
| El snapshot de la línea | No cambia: `code + rate` en la fila (`sales_order_items`, `purchase_order_details`). Con 8% y 16% ese par **identifica** de qué fila salió, que es lo que un auditor lee meses después; copiar la región sería un segundo lugar que puede discrepar de la cadena de la tienda. Dos regiones con la **misma** tasa son indistinguibles por diseño: también son el mismo importe |
| El ABM | `POST`/`PUT` sobre `/api/companies/{companyId}/tax-rates` aceptan `regionId` opcional (ausente = toda la compañía) y el read devuelve `regionId` + `regionName`. Una región de **otra** compañía es **404** y no se guarda; el duplicado del mismo día se rechaza **por alcance** y el mensaje dice cuál lo tiene. La sucesión hereda el alcance de la fila que reemplaza: una sucesión «de toda la compañía» no puede tragarse las filas de una región |
| Lo que **no** cubre | La pantalla existe desde `odd/tasks/configuration-tax-rates.md`: **Configuración → Impuestos** lista las tasas de la empresa con el alcance explícito, marca la fila que resuelve hoy, **declara** una tasa con el porcentaje convertido a la fracción del contrato, la **sucede** desde un día estrictamente posterior sin ofrecer nunca un editor de la fila superada, y la **retira o reincorpora** con `PATCH …/enable` bajo la etiqueta «Retirar», jamás «Eliminar». **Corregir un tipeo de valor es «retirar y volver a declarar el mismo día»**: desde `V114` la unicidad mira las filas habilitadas, así que la retirada deja de ocupar su día y una habilitada nueva entra con **201**; dos habilitadas para el mismo `(compañía, código, alcance, fecha)` siguen dando **409**, dos retiradas para el mismo día son representables y nunca resuelven, y reincorporar una fila retirada cuyo día ya fue corregido responde **409 que nombra el código y el alcance**, nunca el conflicto genérico de constraint. **Lo que sigue sin existir es mover una fila de un alcance a otro** — el alcance es `updatable = false`, igual que la fecha y la tasa — pero el alcance equivocado sí se corrige: desde #1030 retirar la última fila habilitada de una región le devuelve el código a la compañía, así que sus documentos dejan de rechazarse con gap y la corrección es «retirar y declarar en el alcance correcto» (`odd/tasks/tax-rate-region-authority.md`). No hay impuesto por **producto**: el código es del producto, la tasa no. Y no hay regla de «la región X exige tasa propia»: la autoridad de una región nace de que alguien declare una fila para ella, no de un catálogo de jurisdicciones |

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

## Costo del inventario (`stock_valuation`)

Lo que una compra **pagó** y lo que el inventario **cuesta** eran dos números sin conexión: la entrada al ledger
registraba el `cost_price` declarado a mano, así que una recepción por un precio distinto dejaba el costo donde estaba,
en silencio. ADR-0001 ya decía la regla —*"a receipt takes the purchase line's unit cost"*— y ningún código la
implementaba. `odd/tasks/purchase-cost-into-inventory.md` (issue #853).

| Regla | Valor |
|-------|-------|
| Dónde vive el costo de lo que hay en mano | `stock_valuation.avg_unit_cost NUMERIC(19,4)`, que la base deriva: `total_value` es lo que entró valorizado menos lo que las asignaciones congelaron, y el promedio es eso sobre la cantidad (`V60`/`V61`, `replay_stock_valuation` / `refresh_stock_valuation`). **No hay una segunda columna de promedio**: el número es del ledger y desde Java sólo se lee |
| Quién lo alimenta | La **entrada** de una recepción, con la **base** de la línea de compra (`purchase_order_details.unit_price`), nunca el `total` ni el `cost_price` declarado. El costo viaja también a `goods_receipt_items.unit_cost` (`V96`, `NUMERIC(19,4)`, se escribe una vez y no se reescribe), que es el snapshot auditable de lo que esa mercancía costó |
| Qué **no** cambió | `cost_price` sigue siendo el precio de **catálogo declarado**, `NUMERIC(12,2)`, editable por el humano, y es lo que la pantalla de compras usa para pre-llenar el precio unitario (`detail-table.ts:447`). El promedio derivado nunca llega a ese campo: pre-llenarlo convertiría el promedio en la próxima compra y amortiguaría la variación que mide |
| La salida | Una fila de salida **no lleva costo propio**: el costo de las unidades vendidas está en `stock_allocation.unit_cost`, resuelto por `companies.costing_method` — `FIFO` (default) consume las entradas más viejas y `AVERAGE` congela el promedio vigente. Eso es ADR-0001 y esta unidad no lo toca; hay un test que lo fija con las dos políticas |
| El impuesto | Costo = **base**: el IVA acreditable no es costo. Un impuesto **no deducible** sí lo sería, pero el árbol no puede distinguirlo (no hay marca de deducibilidad en empresa, producto ni documento), así que se declara el límite en vez de adivinarlo |
| La pantalla | `averageCost` viaja en las respuestas de variante y se muestra **en lectura** junto al precio de catálogo, con la moneda del `MoneyPipe`. `null` significa *el ledger nunca movió esa variante en esa tienda* y se muestra como ausencia — **nunca como `0`**, que se leería como un costo real de nada |
| Lo que **no** cubre | No hay capas FIFO propias (las del ledger son las suyas). No hay costo de importación (fletes, aranceles). No se revalúa el stock que ya estaba: el primer promedio aparece con la próxima recepción, y una variante que nunca se vuelve a recibir conserva su costo declarado. Y bajo el default `FIFO` el promedio de lo que hay en mano se mueve también al vender, porque es valor sobre cantidad; el promedio como política contable es `costing_method = 'AVERAGE'` |

---

## Docker Scripts

Scripts en `docker/scripts/`:

| Regla | Valor |
|-------|-------|
| Modo estricto | Los cuatro scripts de entorno (`setup-env.sh`, `deploy.sh`, `cleanup.sh`, `validate-env.sh`) declaran `set -euo pipefail`, igual que `db-roles.sh`, los dos preflight y los wrappers de `docker/entrypoints/`. `-u` es lo que hace fallar un `ENV` sin declarar dentro del guard de borrado —`volumes_root_env_dir_name` formatea `$ENV`, así que sin `-u` el nombre era `volumes-` y el guard comparaba un path que nadie declaró—; `pipefail` es lo que vuelve visible un comando que falla adentro de un pipe. `scripts/tests/script-strictness.test.sh` extrae la línea `set` de **cada** script y corre las sondas bajo ella, así que un script cuyos flags se debiliten hace fallar su propia sonda: el literal del test no puede divergir del archivo que protege |

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
> **`start` tampoco reporta éxito sin el realm.** Después de `compose_run up -d` espera —acotado, y con
> knobs `REALM_WAIT_ATTEMPTS`/`REALM_WAIT_SECONDS`— a que el contenedor de Keycloak esté `healthy`, y
> recién entonces corre `keycloak-setup.sh --ensure-audience <env>`, que aborta el start si falla. La
> razón es que el mapper `api-audience` es lo que hace aceptable a **todo** token: sin él Keycloak emite
> `aud: ["account"]` y los dos decoders responden `401 invalid_token` sobre todo, mientras `health`
> seguía verde. `health` cierra la otra mitad: corre `keycloak-setup.sh --verify-audience <env>`, que es
> **sólo lectura**, después de sus veredictos de contenedores, Prometheus y Tempo, y devuelve rojo cuando
> el realm no emite la audiencia que los decoders exigen. Un health check que escribe puede enmascarar
> justo el estado que le pidieron reportar, así que no escribe. Ver `odd/tasks/deploy-realm-gate.md`
> (issue #1029).
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
| Puertos de datastores y API | Los dos Postgres, Redis, Mailpit y el puerto propio de `lifecontrol-api` publican **sólo en `127.0.0.1:`**, en el compose base, en el override de dev y en el de prod. Un `HOST:CONTAINER` pelado exponía a toda la red del host un Postgres con password y, peor, un Redis **sin password**: un cache escribible a un `CONFIG SET dir` de escribir un archivo arbitrario (issue #890). El lado del host sigue siendo la misma variable (`${POSTGRES_PORT}`, `${LIFECONTROL_POSTGRES_PORT}`, `${REDIS_PORT}`, `${MAILPIT_PORT}`, `${LIFECONTROL_API_PORT}`, `${API_GATEWAY_PORT}`) y el del contenedor el mismo puerto, así que nada de lo que el repo direcciona se movió. `datastore-port-binding.test.sh` lo fija con una tabla congelada: un mapping que desaparece o cambia es una edición revisada, no un recorte silencioso. Keycloak y el web app quedan **fuera** a propósito: son las entradas de navegador de todo entorno |
| `deploy.sh <env> health` | Además de que cada contenedor esté `healthy`, falla si Prometheus reporta un target que no está `up` (`up == 0`) o si Tempo no puede leer un trace ya flusheado; si el store de traces es más joven que la ventana de la sonda (24h a 2h), imprime un aviso y no falla, porque todavía no hay nada que leer. `observability-probes.test.sh` fija los dos veredictos |
| Dashboards de Grafana | Se provisionan desde `docker/config/grafana/dashboards/` (un provider con `allowUiUpdates: false` y dos dashboards JSON), montados read-only en `/etc/grafana/provisioning/dashboards`; los datasources siguen viniendo del volumen que instala `setup-env.sh`. **El alerting también se provisiona desde el repo**, y es lo que hace utilizable una regla: `docker/config/grafana/alerting/{contact-points,policies,rules}.yml`, montados read-only en `/etc/grafana/provisioning/alerting` en los dos compose. Un contact point de **email** (`lifecontrol-email`), un único árbol de políticas que rutea todo hacia él (`group_by [job, alertname]`, `group_wait 30s`, `group_interval 5m`, `repeat_interval 4h`), y **una** regla: ratio de errores `SERVER_ERROR` por encima del 1% en 5 minutos, `for: 5m`, `noDataState: OK`, sobre `lc-prometheus`. La dirección **no** está escrita en el archivo: es `$__env{GRAFANA_ALERT_EMAIL}`, que los dos compose declaran con default, porque una variable vacía hace fallar **todo** el pase de provisioning (`could not find addresses in settings`) en vez de dejar la regla muda — el modo de falla correcto para un valor sin default sensato. En dev entrega en Mailpit, el mismo sink que las invitaciones de Keycloak; en staging/prod `SMTP_HOST`/`SMTP_PORT` apuntan a un relay real y la password viaja como secret montado, nunca como `environment:`. `docker/scripts/tests/grafana-alerting.test.sh` fija el cableado: que los tres archivos sigan montados, que el `receiver` de la política nombre el contact point que existe, y que el `datasourceUid` de la regla sea uno que `datasources.yml` declare — una regla no puede omitir su datasource como hace un panel. Lo que **no** hay: sink de webhook o de chat, porque no hay consumidor para ninguno, y una sola regla |
| Exporters de datastores | Dos `postgres-exporter` (uno por instancia: `postgres_exporter` mide un DSN por proceso) y un `redis-exporter`, con el puerto de host en loopback y la password desde el mismo secret que lee la base: los Postgres con `DATA_SOURCE_PASS_FILE` y Redis con `--redis.password-file`, porque el cache **exige** password desde issue #890 y un valor de argumento termina en `docker inspect`. Ese flag **no toma una password plana**: toma un mapa JSON de dirección a password, así que el exporter lee `/run/lc-exporter/redis_passwords.json`, que escribe `docker/entrypoints/redis-entrypoint.sh` en un directorio que `redis` monta rw y el exporter ro — la imagen del exporter es distroless y no tiene shell para armar el archivo ella misma, y por eso el único shell de la cadena es el del cache. Keys en `.env.template`: `POSTGRES_EXPORTER_IMAGE`, `POSTGRES_EXPORTER_PORT`, `KEYCLOAK_POSTGRES_EXPORTER_PORT`, `REDIS_EXPORTER_IMAGE`, `REDIS_EXPORTER_PORT`, `POSTGRES_EXPORTER_MEMORY_LIMIT` y `REDIS_EXPORTER_MEMORY_LIMIT` |
| Bloque de auth de Grafana | Salió de `docker/.env.template` (y de los `.env.<env>`): dos de esas keys **sí** las reenviaba el override de prod (`GF_AUTH_ANONYMOUS_ENABLED`, `GF_AUTH_DISABLE_LOGIN_FORM`), así que un `.env.prod` materializado desde el template podía correr Grafana con admin anónimo. Quedan como keys internas del compose, con el default del compose como único valor, y `image-pins.test.sh` falla si vuelven al template |

### Secretos
Todas las contraseñas viajan por archivos `docker/secrets/*` (NO por los `.env.*`) con el **mismo patrón en dev, staging y prod**: `setup-env.sh` materializa cada `*.template` (strip de comentarios), le aplica **el dueño y el modo de la tabla de abajo** y compose los monta read-only en `/run/secrets/<nombre>`; los wrappers en `docker/entrypoints/*.sh` leen el archivo y exportan la env var que la imagen espera (`KC_DB_PASSWORD`, `POSTGRES_PASSWORD`, `DATABASE_PASSWORD`, `FLYWAY_PASSWORD`, `REDIS_PASSWORD`, `GF_SECURITY_ADMIN_PASSWORD`, `KEYCLOAK_ADMIN_CLIENT_SECRET`). Un archivo materializado **no es legible por una segunda cuenta local**: esa es la mitad de S6/S9 que este modelo cierra (`odd/tasks/docker-stack-hardening.md`, L21).
- Secretos: `keycloak_postgres_password`, `keycloak_admin_password`, `keycloak_admin_client_secret`, `lifecontrol_postgres_password`, `lifecontrol_app_password`, `redis_password`, `grafana_admin_password`, `smtp_password`, y los cuatro del proveedor fiscal (`fiscal_csd_certificado`, `fiscal_csd_llave`, `fiscal_csd_password`, `fiscal_sf_password`). **Cinco de los doce se crean a mano** porque no tienen `.template` a propósito: `redis_password` y los cuatro fiscales. `setup-env.sh` no los genera ni los materializa; la tabla de abajo dice quién los lee y con qué dueño/modo, y esa regla se aplica en cuanto el archivo existe.
- **La tabla es el contrato, y esta es la única copia que un humano lee.** `setup-env.sh` la aplica, `validate-env.sh` la verifica y `docker/scripts/tests/secrets-ownership.test.sh` la congela y compara las tres copias **en las dos direcciones**: una fila que se mueva en un solo lugar, o un secreto nuevo sin fila, hace fallar la suite en vez de ensanchar un modo en produccion.

| Archivo | Modo | Dueño:grupo | Quién lo lee |
|-------|------|-------------|--------------|
| `fiscal_csd_certificado` | `0400` | `1001:0` | el wrapper del api (uid 1001) |
| `fiscal_csd_llave` | `0400` | `1001:0` | el wrapper del api (uid 1001) |
| `fiscal_csd_password` | `0400` | `1001:0` | el wrapper del api (uid 1001) |
| `fiscal_sf_password` | `0400` | `1001:0` | el wrapper del api (uid 1001) |
| `grafana_admin_password` | `0400` | `1000:0` | el wrapper de grafana (uid 1000) |
| `keycloak_admin_client_secret` | `0400` | `1001:0` | el wrapper del api (uid 1001) |
| `keycloak_admin_password` | `0400` | `1000:0` | el wrapper de keycloak (uid 1000) |
| `keycloak_postgres_password` | `0440` | `0:9313` | keycloak (uid 1000) **y** `postgres-exporter-keycloak` (uid 65534) |
| `lifecontrol_app_password` | `0440` | `0:9311` | el api (uid 1001) **y** el script de roles dentro de `lifecontrol-postgres` (uid 999) |
| `lifecontrol_postgres_password` | `0440` | `0:9312` | el api (uid 1001, Flyway) **y** `postgres-exporter-lifecontrol` (uid 65534) |
| `redis_password` | `0400` | `1001:0` | el wrapper del api (uid 1001); el de redis lo lee como root antes de su drop |
| `smtp_password` | `0400` | `1000:0` | el wrapper de grafana (uid 1000) |

> **Por qué `0400` alcanza y por qué tres filas son `0440`.** Un archivo tiene **un** lector no-root en nueve filas: `0400` con ese uid como dueño es el modo más angosto que igual deja leerlo, y el grupo se declara igual (`:0`) para que cada credencial tenga **una sola** forma legal y "ya está bien" nunca sea una interpretación. Las tres restantes tienen **dos** lectores no-root con uids distintos (medido, L20: los exporters corren como `nobody` y no pueden leer un `0400` ajeno; el script de roles corre como 999 en un volumen nuevo), así que ningún dueño único sirve a los dos y un `0400` dejaría afuera a uno de ellos. La salida es **un gid numérico por credencial**, `0440 root:<gid>`, con `group_add` en **solo** los dos servicios que la leen: `9311` en `lifecontrol-api` y `lifecontrol-postgres`; `9312` en `lifecontrol-api` y `postgres-exporter-lifecontrol`; `9313` en `keycloak` y `postgres-exporter-keycloak`. Un gid numérico no necesita entrada en el `/etc/group` del contenedor ni se crea en el host, y el valor sigue existiendo **una sola vez** — duplicar el archivo por lector derivaría en la primera rotación. Los wrappers que corren como root antes de bajar privilegios (los dos postgres, redis) no necesitan grupo suplementario: root lee cualquier archivo.
- **Los lectores del lado del host corren como root.** `keycloak-setup.sh` (y las dos verificaciones manuales, `verify-invitation-flow.sh` / `verify-provisioning-flow.sh`) leen credenciales del host, y desde esta regla se invocan con `sudo`: `sudo ./docker/scripts/keycloak-setup.sh <env>`. Una credencial que la cuenta del operador puede leer es una credencial que cualquier proceso de esa cuenta puede leer, y esa es exactamente la mitad que S6 cierra; root es la única cuenta que lee todas sin ensanchar un modo. El script lo dice en su propio uso y falla con esa instruccion si el archivo no es legible, en vez de morir en un `Permission denied` de `cat`.
- **Qué NO defiende.** Un proceso que ya corre **como ese uid de servicio** lee su propio archivo, por diseño: el modelo separa uids, no encierra al lector. No protege contra root, ni contra un volumen o un `docker exec` con privilegios. Y no hay rotación automática: cambiar un valor es `cleanup.sh secrets <env>`, que rematerializa. **Ese camino no decide ningún modo ancho**: deja el archivo rotado en `0600` —nunca más abierto que su creador— y el cierre del comando nombra `./docker/scripts/setup-env.sh <env>` antes de `deploy.sh <env> restart`, porque la forma legal de cada credencial se aplica en un solo lugar. Mientras no se corra, el archivo en `0600` no coincide con su fila y `validate-env.sh` rechaza el deploy, que es el modo de falla correcto.
- **Un solo paso privilegiado en un checkout existente.** Los archivos de hoy son del operador, así que `setup-env.sh` no puede aplicarlos sin root: **colecciona todos** los que no pudo arreglar y, al final, imprime **un unico bloque** copiable con el orden que funciona —`deploy.sh <env> stop` (un contenedor vivo sigue escribiendo con su uid viejo, la leccion de T3), un `sudo chown … && sudo chmod …` por archivo, `deploy.sh <env> up`— y sale distinto de cero. Un viaje privilegiado, no once.
- `validate-env.sh` valida los secretos en todos los entornos, y falla si un `*.template` no fue materializado (la iteración anterior sólo miraba entradas existentes, así que un secreto ausente pasaba el gate y fallaba dentro del contenedor), si un archivo no tiene fila en la tabla, o si su modo, dueño o grupo no coinciden con la fila; el mensaje nombra el archivo y el valor exacto que encontró y el que hace falta. `deploy.sh start` en prod lo usa como gate. La excepción de `CHANGEME` no cambió.
- **Dos credenciales de base, dos trabajos (ADR-0005, decisión 3).** El pool conecta como `lifecontrol_app`, con el secret `lifecontrol_app_password` que el wrapper exporta como `DATABASE_PASSWORD` y que lee `spring.datasource`: no es dueño de nada y no puede saltear RLS, así que las policies aplican a toda conexión de la aplicación. Flyway corre como `lifecontrol_owner`, con el secret `lifecontrol_postgres_password` que el wrapper exporta como `FLYWAY_PASSWORD` y que lee `spring.flyway`, desde el mismo proceso: es dueño de los objetos y el único login que puede correr DDL, y su credencial nunca llega al pool. El nombre del pool lo pone el compose (`DATABASE_USERNAME`); el de Flyway es **literal** en el compose (`FLYWAY_USERNAME=lifecontrol_owner`), porque es el nombre que crea el SQL y `LIFECONTROL_POSTGRES_USER` ya no nombra al dueño: nombra el rol que crea `initdb`. Ninguna de las dos contraseñas es una key de `.env`, el template sólo lleva nombres de rol, y `app-credential-split.test.sh` falla si las dos credenciales se intercambian.
- **El tercer rol no es una credencial de servicio.** `POSTGRES_USER` —y el default del compose `${LIFECONTROL_POSTGRES_USER:-lifecontrol_bootstrap}`— es `lifecontrol_bootstrap`, el superusuario de mantenimiento, y es el rol que crea `initdb`. El SQL le borra el password en cada corrida (`ALTER ROLE … PASSWORD NULL`), porque la imagen le da a `POSTGRES_USER` el valor de `POSTGRES_PASSWORD`, que es el secreto del dueño: sin ese `ALTER`, el superusuario de mantenimiento sería alcanzable por TCP con la credencial de Flyway. Sin password sólo entra por el socket local (`trust`), y por eso ninguna otra pieza de la stack lo nombra como login: el exporter usa `lifecontrol_owner`, que es el rol al que pertenece `lifecontrol_postgres_password`.
- El nombre del rol dueño es `lifecontrol_owner` y no `lifecontrol_user`: el SQL renombra el rol viejo cuando existe (`ALTER ROLE … RENAME`) para que la propiedad de cada objeto sobreviva a un volumen existente, y lo **crea** `NOSUPERUSER` cuando no existe —que es el caso de todo volumen nuevo, porque `initdb` crea `lifecontrol_bootstrap` y PostgreSQL no deja quitarle `SUPERUSER` al rol que creó `initdb`.
- **El cache exige password, y viaja por archivo.** `redis` arranca con `--requirepass`, que arma `docker/entrypoints/redis-entrypoint.sh` leyendo `docker/secrets/redis_password`: `command:` es YAML y no un shell, así que sólo un script puede convertir un archivo montado en un flag, y un valor en un `environment:` sería un valor en `docker inspect` y en `compose config`. La API **monta el mismo archivo** y lo recibe exportado como `REDIS_PASSWORD` (lo lee `spring.data.redis.password`); el exporter no puede leerlo directo porque su flag pide un mapa JSON, así que el wrapper escribe ese mapa junto al secreto y el exporter lo lee read-only. El healthcheck también autentica, porque un `ping` pelado contesta `NOAUTH` y sale 0. Ni los compose ni el `.env.template` llevan el valor, y `datastore-port-binding.test.sh` falla si aparece — y también si alguna de las tres piezas (el montaje de la API, el directorio compartido, o la forma JSON) se pierde.

### keycloak-setup.sh
Provisión idempotente del realm `life-control-realm`, los clients (`life-control-client` public + `life-control-admin-client` confidential con service account) y los roles (`lc-*` client roles, roles legacy de realm) vía `kcadm.sh` dentro del contenedor. Requiere Keycloak arriba, los secrets de keycloak materializados y **root**, porque las tres credenciales que lee del host (`keycloak_admin_password`, `keycloak_admin_client_secret` y `smtp_password`) son `0400` con el uid del contenedor que las lee como dueño, y la cuenta del operador no es ninguna de ellas; root es la única que lee todas sin ensanchar un modo. El script falla nombrando el `sudo` si un archivo existe y no es legible, en vez de morir en un `Permission denied` de `cat`.
```bash
sudo ./docker/scripts/keycloak-setup.sh [dev|staging|prod]

# Los dos modos acotados del camino de deploy NO necesitan root: leen la credencial
# del admin DENTRO del contenedor, desde el mismo `/run/secrets/keycloak_admin_password`
# que el entrypoint ya lee, así que el host nunca abre un archivo 0400.
./docker/scripts/keycloak-setup.sh --ensure-audience [dev|staging|prod]
./docker/scripts/keycloak-setup.sh --verify-audience [dev|staging|prod]
```
> **Dos modos acotados, dos preguntas.** `--ensure-audience` toca **sólo** el mapper de audiencia de la
> plataforma: lo crea si falta y lo verifica si está, fallando ante un mapper derivado en vez de
> repararlo. `--verify-audience` es **sólo lectura**: exit 0 cuando el mapper existe y coincide con el
> contrato declarado, 1 cuando falta o derivó, y no escribe nada — ni un create, ni un update, ni una
> escritura al realm. Los dos reusan la **misma** declaración (`API_AUDIENCE`, `AUDIENCE_MAPPER`,
> `verify_audience_mapper`), porque un contrato en dos copias es el drift que el contrato existe para
> atrapar. La invocación sin flag sigue convergiendo todo el realm, con su requisito de root intacto.
>
> **Por qué los modos acotados no leen el host.** Las tres credenciales del provisioning completo son
> `0400` con el uid del contenedor como dueño, así que la corrida completa necesita root; mandar `deploy.sh`
> por ahí volvía root-only un flujo que hoy no lo es. Los modos acotados configuran `kcadm` con el
> password expandido **adentro** del contenedor, de modo que el valor no está en el proceso del host, ni
> en su entorno, ni en un argv que `docker inspect` muestre. Ninguno de los dos toca
> `keycloak_admin_client_secret` ni `smtp_password`: el mapper de audiencia no los necesita.
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
> **Verificación end-to-end** (manual, nunca wired en CI): con el stack arriba y `sudo ./docker/scripts/keycloak-setup.sh dev` corrido, `sudo ./docker/scripts/verify-invitation-flow.sh dev` obtiene un token del service account admin, crea un user descartable, dispara `execute-actions-email` con `["VERIFY_EMAIL","UPDATE_PASSWORD"]` y comprueba por la API REST de Mailpit que el mail llegó. Después hace tres verificaciones reales: que la base del link sirva **Keycloak** (el discovery en `<link-base>/realms/<realm>/.well-known/openid-configuration` devuelve un `issuer` igual a esa base — en el origen de la app devuelve el HTML de la SPA), que el `issuer` del realm sea el que valida el stack (`KEYCLOAK_ISSUER_URI`), y que el claim `redirect_uri` del token de acción (emitido por Keycloak 26 como `reduri`) sea `WEB_APP_URL`. Borra el user al salir y sale distinto de cero ante cualquier fallo.
>
> **El flujo de provisioning tiene su propio veredicto de punta a punta**, hermano del anterior y con la misma forma (manual, dev-only, datos descartables, limpieza al salir, exit distinto de cero ante cualquier fallo): `sudo ./docker/scripts/verify-provisioning-flow.sh dev`. Es el que contesta la pregunta que originó la investigación del 2026-10-19 — *¿se mandó el correo de activación?* — en una corrida en vez de cuatro queries SQL y una mirada a Mailpit. Crea **su propio** empleado, contrato y store assignment por la API (el mismo `POST /api/companies/{id}/employees` y `.../contracts` que usan las pantallas), con un token de operador que lleva `lc-admin` y el claim `company_id`; espera a que el worker aplique la tarea hasta `APPLIED`; encuentra la invitación en Mailpit con su link y comprueba que la base del link sirva Keycloak; y verifica la cuenta de Keycloak: el id linkeado, sus `requiredActions`, los roles que declara el template de la posición y los cinco claims `company_*` (presentes exactamente en los niveles que la derivación sostiene, ausentes en los que no). Si el stack tiene el worker apagado **no espera**: lo dice y nombra `WORKER_ENABLED`, porque esperar una tarea que nadie va a reclamar es el estado silencioso que este script existe para eliminar. El operador es lo único que tiene que traer: por default el script crea un operador descartable (`lc-admin` + el atributo `company_id`, password aleatoria que nunca imprime y no escribe a disco) y lo borra al salir; con `PROVISIONING_VERIFY_TOKEN=<jwt>` usa un token que le pases y no escribe **ninguna** identidad en el realm. Lo que deja asentado y no cubre: las pantallas (maneja la API que ellas llaman), y un template de posición sin roles sólo prueba que la cuenta no tiene ninguno.

### Claims de tenancy (`company_*`) en el token

El guard de alcance de `life-control-api` **no lee la base**: `CurrentUserContext` resuelve company, country, region, zone y store leyendo cinco claims **en la raíz del JWT** — `company_id`, `company_country_id`, `company_region_id`, `company_zone_id`, `company_store_id`. `keycloak-setup.sh` provee lo que hace que existan, en tres piezas que tienen que estar las tres o ninguna sirve:

1. **Un protocol mapper por claim** en el client público `life-control-client`: `oidc-usermodel-attribute-mapper`, **multivaluado** y a nivel raíz. Los nombres tienen que coincidir con los de `ScopeLevel.claim()`; `KeycloakClaimMapperCoverageTest` acopla el script al código en las dos direcciones, así que un claim que el código lea sin mapper en el script rompe un test en vez de romper en runtime.
2. **`unmanagedAttributePolicy=ADMIN_EDIT`** en el user profile del realm. Sin esto Keycloak 24+ **descarta los atributos en silencio**: la Admin API responde **204** y no guarda nada, el mapper no emite, y el guard responde 403 sin que ningún test de Java lo vea. `ADMIN_EDIT` (nunca `ENABLED`) deja escribir a los endpoints de administración y evita que el sujeto se auto-asigne su propia tenancy.
3. **El atributo del usuario**, que escribe el flujo de aprovisionamiento de acceso.

**Verificar de punta a punta**, con el stack arriba (`sudo ./docker/scripts/keycloak-setup.sh dev` primero):

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
| Pins de CI (`.github/`) | Los cuatro workflows de componente declaraban **ningún** `permissions:`, así que cada job heredaba el token por defecto del repositorio —con permiso de escritura— y se lo entregaba a cada action de terceros; y cada `uses:` nombraba un tag mayor (`actions/checkout@v7`), que es un puntero que su publicador puede mover, corriendo con el token de este repositorio. Ahora los cinco declaran `contents: read` (más `issues: read` y `pull-requests: read` en el guard, el único que lee la API), cada action está anclada a un SHA de 40 hex con su tag en un comentario al lado, y `koalaman/shellcheck:v0.11.0` reemplaza al alias flotante `stable` que linteaba este repositorio bajo un comentario que afirmaba que estaba pinneado. `workflow-pins.test.sh` lo fija y lo corre el job `guard` del `PR Label Guard` —el único workflow sin filtro `paths:`—, recorriendo los workflows por glob, así que uno nuevo queda cableado por existir. **Un scope de escritura se rechaza**, no se justifica con un comentario: un comentario no se puede verificar, y el modo de falla que la regla existe para atrapar es exactamente un token ensanchado que nadie miró |
| Chequeo | `./docker/scripts/tests/image-pins.test.sh`: falla si el template y los defaults divergen, si un `FROM` deja de nombrar su ARG o si el ARG diverge del default del compose, y es el que fija la lista de versiones vigente. Bumpear una imagen es editar las dos cosas y su tabla, en el mismo commit |
| Quién mueve los pins | **Nadie automáticamente, y es decisión, no olvido** (issue #987): ningún bot puede leerlos —cada `FROM` es un `ARG` que llena el compose y cada `image:` del compose es `${VAR:-default}`, así que la búsqueda de imágenes de Dependabot devuelve cero— y, aunque pudiera, un bump tiene que mover las **tres** copias (la clave del template, el default del compose y la tabla de `image-pins.test.sh`) en un solo commit, que es exactamente lo que un pull request automático no hace. Lo mueve una persona, y el gate sigue siendo la suite: `image-pins.test.sh` falla cuando las tres copias divergen, y `validate-env.sh <env>` nombra cada pin cuyo valor en un `.env.<env>` ya no coincide con el template. Lo que **no** hay es señal de staleness: un tag viejo sigue verde hasta que alguien lo mira, y esa es la mitad que esta decisión acepta a cambio de no sumar una segunda herramienta de dependencias al lado de los cuatro ecosistemas de Dependabot |
| Imágenes base de los Dockerfiles | Las cuatro líneas `FROM` de los tres Dockerfiles no escriben su imagen: `JAVA_BASE_IMAGE` (los dos módulos Java), `NODE_BASE_IMAGE` y `NGINX_BASE_IMAGE` (los dos stages del web app) son keys del template, el compose las pasa por `args:` con el mismo default y cada `FROM` nombra el ARG, con el valor actual como default del `ARG` para que `docker build .` siga funcionando fuera de compose. Una imagen base escrita a mano adentro de un `FROM` era el pin que ninguna suite leía: el template y los compose podían coincidir entre sí mientras el build arrancaba de otra imagen. `NODE_BASE_IMAGE` se compara además contra `.nvmrc`, porque dos fuentes de la línea de Node son dos fuentes |
| k8s y Gradle | `k8s/` **no** puede leer la fuente de pins de docker —corre MySQL, Kafka y una imagen de gateway externa que este stack no corre—, así que su fuente es la **tabla congelada** dentro de la misma suite, comparada en las dos direcciones: `grafana/loki:main` y `mysql:8` se materializaron a `3.7.8` y `8.3.0` (en las **dos** copias del manifiesto, `manifests/infra/` y `manifests/infrastructure/`, y en `kind/kind-load.sh`), y el único `:latest` que sobrevive es `saiupadhyayula007/new-api-gateway`, excepción nombrada: Docker Hub publica **un solo tag** de esa imagen, este repo no la construye ni la pushea y ningún deploy lee ese árbol. Los wrappers de Gradle y las versiones de plugin tienen su propia tabla congelada en `.github/scripts/tests/gradle-pins.test.sh`, que también falla en las dos direcciones: los dos wrappers quedaron en la misma versión (`9.9.2`) y cada versión de plugin vive en el catálogo compartido `gradle/libs.versions.toml` (issue #894), no en un `build.gradle` — así que la tabla no alinea nada, sólo congela lo que el catálogo declara |
| Loki y Tempo no llevan `healthcheck` | Desde `grafana/loki` 3.6.0 y `grafana/tempo` 3.x las imágenes son distroless (`gcr.io/distroless/static`): sin shell ni `wget`, así que un `CMD wget …` queda `unhealthy` para siempre. La señal se mide desde el host en `deploy.sh health`, con `curl` |
| Loki guarda en `/loki` | Es el `path_prefix` de su config empaquetada. El mount apunta a `/loki`, y el dueño lo **fija o el setup se detiene**: `setup-env.sh` hace `chown -R` a `10001:10001`, el uid de la imagen distroless, sobre ese directorio más `rules/` y `rules-temp/`, y un `chown` que no se puede aplicar es **fatal** (`exit 1`) nombrando el directorio y el comando exacto que hay que correr — hasta que entró el issue #897 el script imprimía un warning y seguía, así que un directorio de otro dueño no lo reportaba nadie y Loki entraba en un restart loop con `mkdir /loki/chunks: permission denied` mientras `docker/scripts/deploy.sh dev health` quedaba verde. Medido el 2026-11-18: el directorio era `leo3nava:leo3nava 755` y la imagen era la 3.7.8. Un directorio que **ya** tiene el dueño correcto **y todo su contenido también** es un éxito sin `chown`, y eso es lo que le permite a un operador sin root re-correr el script después de aplicar el comando que el error le dio |
| Volumen de Loki en prod | Es un named volume: si existe de antes (creado para `/loki/data`) hay que borrarlo una vez, porque Docker no lo repuebla desde la imagen y queda vacío y root-owned, que es justo lo que Loki no puede escribir |
| Exporters de datastores | `postgres-exporter` y `redis-exporter` se pinnean como el resto (`POSTGRES_EXPORTER_IMAGE` y `REDIS_EXPORTER_IMAGE`, en el template **y** en el default del compose) y **no** llevan `healthcheck`: la imagen del exporter de Redis es distroless, así que un probe in-container no puede pasar. La señal de vida es `up` en el scrape, y `exporters.test.sh` lo fija |
| Pins divergentes en un `.env.<env>` | Un `*_IMAGE` que el env file declara con un valor que el template ya no nombra **se reporta**: `validate-env.sh <env>` lo lista nombrando la key y ambos valores, y `deploy.sh <env> start` imprime el mismo reporte antes de buildear, así que un pin viejo no sobrevive un deploy en silencio. `env-image-pin-drift.test.sh` fija que el reporte nombre la key y los dos valores, que un env file alineado no produzca ningún warning y que el reporte sea read-only y devuelva 0 siempre |
| Qué código corre cada imagen de app | El label `org.opencontainers.image.revision` es la única afirmación que una imagen hace sobre su propio código, y hasta `docker-image-revision` nada lo leía de vuelta. Lo estampa `image_revision_stamp` (`<sha>`, `<sha>-dirty`, o `unknown` cuando git no puede responder) y lo verifica `deploy.sh <env> version`, que sale distinto de cero salvo que toda imagen de app sea `match` contra `HEAD`. `health` corre el mismo reporte y sólo avisa; `STRICT_REVISION` lo vuelve un fallo. `image-revision.test.sh` fija los cinco veredictos, los dos stamps y que los tres Dockerfiles sigan bakeando ese label exacto |
| PID 1 en las dos imágenes Java | Los dos `ENTRYPOINT` son `["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]`. Sin el `exec` el shell queda como PID 1, **no reenvía SIGTERM**, y cada deploy espera el stop timeout hasta el SIGKILL: `api-gateway` lo shipeó así hasta este cambio. Con `exec`, java es PID 1 y recibe la señal. Lo que **no** cambia: `api-gateway` no declara `server.shutdown` ni `spring.lifecycle.timeout-per-shutdown-phase`, así que el default `immediate` de Spring Boot sigue cortando las requests en vuelo al apagar; drenarlas pide las dos propiedades más un `stop_grace_period` con holgura, y eso es una decisión aparte de la del `exec`. `dockerfile-hardening.test.sh` fija el `exec` en los dos módulos y que ninguna instalación de paquete se esconda detrás de `\|\| true` — el `apk add curl` del gateway no lo usaba nadie, porque su HEALTHCHECK sondea con `wget` |
| Contexto de build de los módulos Java | Los dos módulos traen `.dockerignore`. La trampa que el archivo esquiva: el `Dockerfile` copia `${JAR_FILE}`, que resuelve a `build/libs/<module>-<version>.jar`, o sea **adentro** de `build/`; un `build/` pelado deja de achicar el contexto y **rompe el build** (`COPY` falla con `"/build/libs/<jar>": not found`). Por eso el archivo excluye `build/*` y vuelve a incluir `!/build/libs/` y `!/build/libs/*.jar`, y `dockerfile-hardening.test.sh` afirma el par —incluido que no exista una línea `build/` pelada— en vez del nombre del directorio. El criterio literal de #897, «el contexto ya no contiene `build/`», no es satisfacible mientras el JAR viva ahí |
| Roles de la base de datos (`postgres`) | La imagen de postgres corre `/docker-entrypoint-initdb.d` **una sola vez por datadir**, así que un volumen con datos nunca vuelve a pasar por ahí: los roles los define `docker/postgres-init/lifecontrol-roles.sql`, que se monta en `/opt/lifecontrol/` y **fuera** del directorio de init (el glob del entrypoint lo correría una segunda vez), y lo aplican los dos caminos — `postgres-init/10-lifecontrol-roles.sh` en un volumen vacío y `docker/scripts/db-roles.sh <env>` en uno existente. `POSTGRES_USER` (default `${LIFECONTROL_POSTGRES_USER:-lifecontrol_bootstrap}`) es el rol que crea `initdb`, y **es el de mantenimiento, no el dueño**: PostgreSQL rechaza quitarle `SUPERUSER` al rol bootstrap, así que el dueño lo crea el SQL (`CREATE ROLE lifecontrol_owner LOGIN NOSUPERUSER`, o el rename del pre-ADR), le pone el password desde `docker/secrets/lifecontrol_postgres_password` y se queda con la base por `ALTER DATABASE … OWNER TO lifecontrol_owner` (sin eso no puede crear su primera tabla en `public`). El `CREATE EXTENSION IF NOT EXISTS btree_gist` va **antes** del `ALTER ROLE lifecontrol_owner NOSUPERUSER` porque `V21__employee_contracts.sql:27` lo necesita: al revés, la primera migración de toda base nueva falla. Al bootstrap el SQL le borra el password en cada corrida, así que sólo entra por el socket local (`trust`) y ninguna otra pieza lo nombra. Ni el dueño queda superusuario (un superusuario siempre saltea RLS) ni la app puede (`NOBYPASSRLS`), y el credential de la app es `docker/secrets/lifecontrol_app_password` |
| Capacidades y escalada de privilegios | **Todo** servicio de los tres compose declara `cap_drop: [ALL]` y `security_opt: [no-new-privileges:true]`, con **dos** excepciones que llevan su razón medida al lado del bloque, porque un `cap_drop` pelado ahí no es endurecimiento sino un restart loop (o algo peor). Las dos `postgres`: `CHOWN, DAC_OVERRIDE, SETGID, SETUID` — su entrypoint arranca como root, hace `chown` del datadir y baja a `postgres` con `gosu`, así que con el set vacío muere en `chmod: changing permissions of '/var/run/postgresql': Operation not permitted` y `chown: changing ownership of '/var/lib/postgresql/18/docker': Operation not permitted`; `DAC_OVERRIDE` es lo que le permite escribir en un datadir que pertenece al operador que corrió `setup-env.sh`, y `FOWNER` se midió **innecesario** (sin él llega igual a *database system is ready to accept connections* como uid 999). `redis`: `CHOWN, DAC_OVERRIDE, FOWNER, SETGID, SETUID` — y acá `cap_drop: ALL` **invierte** el endurecimiento, porque el entrypoint de la imagen guarda el drop con `has_cap setuid && has_cap setgid`: con el set vacío `redis-server` **sigue corriendo como root** (medido con `docker top`), mientras que con esas cinco baja a uid 999 y escribe `appendonly.aof` en `/data` como hoy. `docker/scripts/tests/compose-hardening.test.sh` congela la tabla **por servicio** para las tres combinaciones (base, base+override, base+prod) leída de `docker compose config`: un servicio que gana o pierde una capacidad es una edición revisada de esa suite, no un drift silencioso, y un servicio nuevo sin fila también falla |
| Sistema de archivos de sólo lectura | `read_only: true` donde la imagen lo tolera, con `tmpfs` para **cada** ruta que escribe fuera de sus bind mounts: `prometheus`, `redis`, los tres exporters, `tempo`, `loki`, `grafana`, `mailpit` y las dos imágenes Java. Las dos `postgres` suman `/var/run/postgresql` al `tmpfs`; `grafana` necesita `/tmp` porque ahí descomprime los plugins que lanza (`open /tmp/pluginN: read-only file system`) — `/var/log/grafana` se midió **innecesario**, sus logs van a stdout. `tempo` y `loki` conservan el `tmpfs` de `/tmp` **sin que se haya observado que lo necesiten** (un arranque limpio de 22 s con sólo el directorio de datos escribible no dejó ni un error de permisos): queda declarado como seguro barato, y así se lee — un `tmpfs` de más no afirma nada, uno de menos falla recién en el mes dos. `mailpit` lo necesitaba y nunca se había medido: sin `tmpfs` en `/tmp` muere en `[db] open /tmp/mailpit-<n>.db: read-only file system`. Las dos Java se midieron sobre la imagen construida: `--read-only --tmpfs /tmp --cap-drop ALL` no deja ni un error de filesystem y el arranque llega hasta la conexión de base. **Dos servicios NO lo toleran y no se arregla con un `tmpfs`**: `keycloak` re-augmenta en `kc.sh start-dev` y escribe `/opt/keycloak/lib/quarkus/transformed-bytecode.jar` (`java.nio.file.FileSystemException: … Read-only file system`), y un tmpfs sobre ese directorio taparía los jars que el paso de build lee; `web-app` genera `assets/env.js` y parchea `index.html` **dentro** del document root antes de que arranque nginx (y escribe `/var/log/nginx/error.log` y `/var/cache/nginx`), así que un tmpfs ahí taparía la SPA que tiene que parchear — y `web-app` es el único de los dos cuyo veredicto se **deriva** del `Dockerfile`, `nginx.conf` y el entrypoint en vez de haberse observado corriendo: su imagen no se construyó en esta unidad, y así se lee. `compose-hardening.test.sh` congela los dos veredictos negativos igual que los positivos |
| Prometheus y Tempo ya no corren como root | `docker-compose.yml` les fijaba `user: "0:0"` y el override de prod no declaraba nada, así que los dos entornos no coincidían: en dev corrían como root y en prod con el default de la imagen. Ahora `prometheus` corre con `65534:65534` (el `nobody` de `prom/prometheus:v3.15.0`, medido con `docker image inspect -f '{{.Config.User}}'`) y `tempo` con `10001:10001` (el default de `grafana/tempo:3.1.0`). El número se escribe en vez de dejarlo al default de la imagen porque `setup-env.sh` crea `$VOLUMES_ROOT/prometheus/data` y `$VOLUMES_ROOT/tempo/data` con ese uid a través de `require_dir_owner` (el mismo helper que usan Loki y Grafana), y la suite mantiene las dos mitades juntas: un `user:` y un `chown` que se separan son el mismo restart loop de siempre. **En un checkout que ya corrió el setup** esos dos directorios son del operador, así que una vez, **con el stack parado** (`deploy.sh <env> stop` primero y `up` al final): `sudo chown -R 65534:65534 docker/volumes-<env>/prometheus/data` y `sudo chown -R 10001:10001 docker/volumes-<env>/tempo/data` (en prod, `/var/lib/lifecontrol/volumes/…`, donde los dos servicios usan named volumes que Docker ya inicializa con el dueño de la imagen). Si no se corre, `setup-env.sh` **aborta nombrando el comando**, que es el modo de falla que se eligió. El orden no es ceremonia: medido el 2026-11-22 en el stack de dev, un `chown -R` contra el stack **vivo** dejó el directorio en `10001:10001` y **57 bloques de traces de root adentro**, porque el Tempo viejo siguió escribiendo con su uid viejo hasta que lo reemplazaron; `deploy.sh dev health` falló con `tempo(restarting)` y el log decía `permission denied` sobre un bloque. Por eso el helper mira el **contenido** y no sólo el dueño del directorio —`find "$dir" ! -user <uid> -print -quit`, 0.01 s sobre los 1.718 archivos del directorio más grande del stack— y por eso el abort nombra `stop`, el `chown` y `up`, en ese orden |

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

## Audiencia del JWT (`aud`)

Los dos decoders validaban `exp`, `nbf` y el issuer, y **ninguno miraba `aud`**. Un allowlist de issuers
contesta *qué realm firmó esto*, nunca *para quién se emitió*: cualquier token del realm, de cualquier
cliente, y para cualquier otro recurso adentro del realm, lo aceptaban el API y el gateway.
`odd/tasks/jwt-audience-validation.md` (issue #893).

| Regla | Valor |
|-------|-------|
| Dónde se declara | `keycloak.allowed-audiences` (API) y `lifecontrol.gateway.allowed-audiences` (gateway). Los dos default a `life-control-api`, y son una lista porque un token puede nombrar varias audiencias |
| Qué exige el decoder | El claim `aud` tiene que **nombrar** alguna audiencia configurada. `aud` **ausente es rechazo**, no un pase: tratar el claim faltante como «sin restricción declarada» dejaría pasar justo el token contra el que existe la validación |
| Quién la emite | El mapper `api-audience` (`oidc-audience-mapper`, `included.custom.audience=life-control-api`) que provisiona `keycloak-setup.sh` sobre el client **público**. Sin ese mapper Keycloak emite `aud: ["account"]` y **ningún** token real pasaría: es la mitad que hay que desplegar junto con el código, no después |
| El gateway valida el mismo token | Comparte la audiencia con el API porque es su puerta de entrada: no hay un segundo recurso al que el token esté destinado entre uno y otro |
| De dónde salen los roles de cliente | `resource_access.<cliente>.roles`, y el cliente es **configuración del servicio** (`keycloak.authority-client-ids`, default `${keycloak.app.client-id}` = `life-control-client`). Antes salía de `resource_access.<azp>`, o sea del cliente que el propio token declaraba: un rol `lc-admin` homónimo en **cualquier otro cliente del realm** otorgaba autoridad de API. Una lista vacía significa que ningún cliente otorga roles de cliente |
| Los roles de realm no cambian | `realm_access.roles` sigue mapeando a `ROLE_*`, incluido el rol de realm `admin` que gobierna `/api/users-admin/**`: el rol de realm es del **usuario** y es el mismo sin importar qué cliente emitió el token. Lo que se angostó es la autoridad de **cliente**, que era la que dependía del token |
| Valor por entorno | No cambia por entorno. Las dos properties leen un env var (`KEYCLOAK_ALLOWED_AUDIENCES` / `ALLOWED_AUDIENCES`) que **ningún compose pasa al contenedor**, así que el valor efectivo es el default del archivo — el mismo mecanismo de «key que el `.env` no declara corre en silencio con el default» que ya documenta este archivo. El script sí lee `KEYCLOAK_API_AUDIENCE` del env file directo, porque `get_env` lo abre él mismo. Cambiar la audiencia es editar las **tres** copias juntas (script, API, gateway) y `AudienceDefaultConsistencyTest` es lo que falla si una queda atrás |
| Enforcement | `JwtDecoderConfigTest` (API) y `MultiIssuerSecurityConfigTests` (gateway) fijan el rechazo por `aud` ausente, por `aud` presente pero vacía y por `aud` ajena, el pase con la audiencia correcta (array o string único, que JWT permite) y las dos caras del rol: `lc-admin` desde el cliente que lo otorga se concede, desde otro cliente **no**. `exp`, `nbf` y el issuer tienen cada uno su test en la misma cadena, así que sacar cualquiera de los tres validadores rompe una prueba. `AudienceDefaultConsistencyTest` es el que ata las tres copias del literal (script, API, gateway) y los overrides por profile |
| Lo que **no** cubre | No valida `azp` (`authorized party`): la pieza que importa es qué cliente puede **otorgar** roles, y eso es configuración del servicio. El navegador no prueba esta regla: los mocks de Playwright emiten su propio token, así que el fixture de `e2e/support/tokens.ts` lleva la audiencia para no mentir sobre lo que Keycloak emite, pero ningún test de e2e decodifica un JWT real |

---

## Borde de confianza del API (proxies, rate limit y actuador)

El API decide dos cosas con la dirección del cliente: si confía en las cabeceras reenviadas y quién
consume qué cuota. Las dos estaban resueltas por defecto de la forma más amplia posible —todo RFC1918
era proxy confiable y lector del actuador— y el rate limiter contaba una sola bolsa por endpoint.
`odd/tasks/api-edge-trust.md` (issue #892).

| Regla | Valor |
|-------|-------|
| Dónde se declara la confianza | `app.security.trusted-proxies` (proxies), `app.security.actuator-allowed-cidrs` (lectores del actuador) y `app.rate-limit.trusted-proxies` (rate limiter). El default de las tres es **loopback y nada más**: `127.0.0.1/32,::1/128`. Una red privada que el operador no declaró no es un proxy |
| Un solo knob por concepto | `TRUSTED_PROXIES` alimenta las **dos** listas de proxies (seguridad y rate limiter: un mismo peer no puede ser confiable para una y no para la otra) y `ACTUATOR_ALLOWED_CIDRS` la de lectores del actuador. `docker/docker-compose.yml` declara las dos para el servicio del API con el rango del que Docker asigna sus redes bridge; es seguro **porque** el puerto publicado está en loopback, y ese par es la razón por la que el valor vive en el compose y no en un default de código |
| Cómo se resuelve la IP | La puerta va primero: si el peer inmediato no es un proxy confiable, se devuelve `getRemoteAddr()` y las cabeceras se ignoran. Si lo es, `X-Forwarded-For` se recorre **de derecha a izquierda** salteando los hops confiables, y el primero no confiable es el cliente. El hop de la izquierda lo escribe quien llama; el de la derecha lo agrega el proxy |
| Un hop que no es una IP | Falla cerrado: se devuelve el peer del socket. `CidrMatcher.looksLikeIp` es la puerta, y existe porque `InetAddress.getByName` **resuelve nombres** — una cabecera con un hostname provocaba una consulta DNS desde el borde |
| La cuota | Una bolsa por **(endpoint, cliente)**: `X-RateLimit-Remaining` es del que llama y agotar la de uno no toca la del otro. La clave no puede ser el path solo, que es lo que compartía la cuota entre todos |
| La cota de memoria | No hay dependencia nueva para acotar el mapa. Cada 256 requests se barren las bolsas que volvieron a su capacidad: una bolsa llena no guarda historia y responde exactamente lo que respondería una nueva, así que descartarla no cambia ninguna respuesta observable |
| La cadena de filtros | Termina en `denyAll()`: un controller fuera de `/api/**` y `/actuator/**` ya no es anónimo por construcción. `/error` queda permitido aparte porque Spring Security filtra el dispatch de ERROR, y denegarlo convertiría una falla sin token en un 403 en vez del estado que el árbol ya responde |
| La documentación | Sale de `permitAll` y entra detrás de `security.docs-public` (`${SECURITY_DOCS_PUBLIC:false}`), el mismo idioma que el gateway ya usaba, con `true` sólo en `application-dev.properties`. En staging `/v3/api-docs` y `/swagger-ui.html` responden **401**. Un test de contrato que necesite leer el documento —`SalesOrderOpenApiContractTest`— enciende la propiedad en su propio contexto: lee el contrato, no afirma que sea público |
| Actuador del API | `/actuator/health` público y el resto por CIDR (`app.security.actuator-allowed-cidrs`), sin cambios de mecanismo pero con el default angosto. La clave es **plana**: el campo es `actuatorAllowedCidrs` y la forma anidada `actuator.allowed-cidrs` no bindea nada —lo fue en silencio hasta el issue #979, y sólo se notó cuando el default dejó de coincidir con el archivo |
| Actuador del gateway | La exposición default es `health,prometheus`, los dos endpoints cuyos únicos lectores son el healthcheck del contenedor y el scraper. El resto de `/actuator/**` termina en `denyAll()` salvo `security.actuator-public` (dev). **No** se filtra por rol: este gateway deriva authorities del claim `scope`, así que `hasAuthority("ROLE_admin")` rechazaría también a los administradores |
| Los puertos | Todos los puertos publicados de la aplicación están en `127.0.0.1:` — `lifecontrol-api` 8082, la entrada del gateway (9000 dev / 9200 prod) y su management (9001 / 9201). El reparto los ató junto con los datastores en issue #890; esta unidad aporta el par que los vuelve seguros: el rango que el API acepta como proxy y lector del actuador sólo puede ser un contenedor de este stack porque el puerto no sale del host. `observability-port-binding.test.sh` congela el alcance de cada mapping y `datastore-port-binding.test.sh` la otra mitad |
| Enforcement | `docker/scripts/tests/observability-port-binding.test.sh` congela el alcance de host **por mapping** (`loopback` o `any`), así que un puerto que cambie de un lado al otro falla ahí. Del lado Java, `SecurityConfigAccessTest` y `SecurityConfigDocsPublicTest` son los primeros tests del árbol contra el `securityFilterChain` real —los ~44 `@WebMvcTest` de controller instalan cada uno su propio `TestSecurityConfig` y no prueban nada de esa clase—, y `RateLimitFilterTest`, `ClientIpResolverTest` y `CidrMatcherTest` fijan las cuatro reglas de arriba |
| Lo que **no** cubre | Los sub-paths de health en el gateway (`/actuator/health/**`) responden 403: no se ampliaron a propósito, porque esta unidad angosta superficie y ningún probe de este árbol los usa. `k8s/` no tiene overlay de entorno y su borde lo decide el ingress del cluster, que este repo no describe. Y el gateway no declara filtro propio de `X-Forwarded-For`: depende del `XForwardedHeadersFilter` de Spring Cloud Gateway, que **agrega** la dirección de la conexión — de ahí que el walk vaya de derecha a izquierda |
| La trampa que creó el renglón anterior | Un `TRUSTED_PROXIES` que se agregue a un `.env.<env>` sin estar en `.env.template` no llega al contenedor: compose lee el env file por interpolación, y cada key que el env file no declara corre con el default del compose en silencio. Es el mismo modo de falla que dejó el worker de provisioning apagado en dev |

---

## Development Commands

### Backend (Gradle)

Cada módulo Java es **su propio build**, con su propio wrapper: no hay build raíz (se eliminó en
el issue #894, porque nada lo ejecutaba). Los comandos se corren **desde el módulo**:

```bash
cd life-control-api      # o cd api-gateway
./gradlew build
./gradlew bootRun
./gradlew test
./gradlew spotlessCheck spotbugsMain   # el gate: formato + análisis estático
./gradlew spotlessApply                # aplica el formato en vez de sólo verificarlo
./gradlew bootJar
```

> **Los dos módulos corren el mismo gate** desde el slice B del issue #894. `api-gateway` declaraba
> `test` y nada más, así que su código pasaba CI sin formateo ni análisis estático; hoy los dos
> `build.gradle` declaran `spotless` y `spotbugs` con la **misma** configuración —Palantir Java
> Format, `effort MAX`, `reportLevel MEDIUM`, `ignoreFailures = false`— y las dos versiones de
> herramienta salen del catálogo compartido, nunca escritas en un `build.gradle`. El formateo alcanza
> **todo** el módulo por defecto (cuando el gate entró reformateó los 13 archivos Java que el módulo
> tenía entonces), así que un archivo nuevo queda alcanzado por existir y no hace falta listar un `target`.
>
> **El análisis estático ignora los tests por decisión, no por olvido**: `spotbugsTest` está
> deshabilitado en los dos módulos (los tests son ruidosos a propósito: dead stores, campos no leídos,
> format strings) y lo que se analiza es el código de producción. Cada módulo tiene su baseline en
> `config/spotbugs/exclude.xml`, y **cada exclusión lleva su razón escrita al lado**: una exclusión sin
> razón es el modo de falla que el archivo existe para evitar. El baseline de `api-gateway` está vacío a
> propósito: los cuatro hallazgos que la primera corrida reportó —`EI_EXPOSE_REP` / `EI_EXPOSE_REP2`
> sobre las dos `List` de `GatewayProperties`— se arreglaron en el código (copias inmutables en el
> constructor compacto del record, con `GatewayPropertiesTests` fijándolo), no se excluyeron.
>
> **En CI**, `api-ci.yml` corre `./gradlew spotlessCheck spotbugsMain --no-daemon` y `gateway-ci.yml`
> corre el mismo comando en el paso `Static analysis` de su job `Check`. El nombre del job no se parte en
> dos como en `api-ci.yml` porque los nombres de job son un contrato documentado en `CONTRIBUTING.md` §6
> y §7 (el runbook de branch protection nombra los checks por ese string), y
> `.github/scripts/tests/contributing-job-names.test.sh` falla ante cualquier nombre que §6 no declare.

El catálogo de versiones es uno sólo y vive en la raíz: `gradle/libs.versions.toml`. Cada módulo
lo declara en su `settings.gradle` (`dependencyResolutionManagement { versionCatalogs { libs {
from(files("../gradle/libs.versions.toml")) } } }`), que es el único mecanismo que Gradle ofrece
para dos builds independientes: un catálogo es visible a los proyectos de **un** build y un build
compuesto no comparte configuración. Una versión se escribe en el catálogo y se usa como
`libs.…` / `alias(libs.plugins.…)`; `.github/scripts/tests/version-catalog.test.sh` falla si una
versión manejada por el catálogo aparece escrita en un `build.gradle` o en un bloque `plugins`.

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
> cableada por existir. La corrida del 2026-11-20 levantó **31 suites**; el conteo se lee de la corrida
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