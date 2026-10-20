# ODD feature: hr-employee-detail-tabs

**Repository**: LifeControl — `life-control-app-angular/`
**Status**: merged — PR **#488** (`feat/hr-employee-detail-tabs` @ `5fd86cf`), 2026-10-20. No work left.
**Created**: 2026-10-20 · **Risk**: bajo — restructuración de una vista: no toca datos, endpoints ni contratos; los gates de rol (`EMPLOYEE_WRITE_ROLES`, `ACCESS_READ_ROLES`) se preservan y el comportamiento está cubierto por tests.

## Objective

Que `/hr/employees/:id` presente sus cuatro secciones como pestañas — `Detalle`, `Contratos`,
`Tiendas`, `Accesos` — con las acciones de cada sección dentro de su pestaña, en vez de apilarlas en
una sola columna con los botones de contrato en el encabezado de la página.

## Specs

**S1 — la vista se separa en cuatro pestañas, con las secciones adentro.** Pedido del usuario,
verbatim: `quiero modificar la vista de un empleado /hr/employees/ para separarlo las secciones en
pestañas Detalle, Contratos, Tiendas, Accesos cada uno con sus botones correspondientes`.

**S2 — cada pestaña lleva los botones que le corresponden.** Verbatim: `cada uno con sus botones
correspondientes`. `Detalle`: `Editar` + `Volver al registro`; `Contratos`: `Nuevo contrato` y
`Cerrar contrato vigente` (bajan del encabezado, que queda sin acciones); `Tiendas` y `Accesos`
conservan los controles que ya renderizan sus componentes. Decisión del usuario sobre `Detalle`,
verbatim: `Editar + Volver al registro`.

**S3 — `Editar` abre el formulario de edición desde el detalle.** Navega a `/hr/employees/edit/:id`
con `queryParams { companyId }`, igual que `onEdit` del listado, y sólo se ofrece a quien tiene
`EMPLOYEE_WRITE_ROLES`, que es lo que exige la ruta de edición. `Volver al registro` es una lectura:
se ofrece a todo caller y navega a `/hr/employees` con `companyId`.

**S4 — la pestaña activa vive en la URL.** Decisión del usuario, verbatim: `Sí: ?tab=contratos`.
`?tab=detalle|contratos|tiendas|accesos` selecciona la pestaña; un valor ausente, desconocido o no
renderizable cae a `Detalle`; al cambiar de pestaña se escribe `?tab=` preservando `companyId`, con
`replaceUrl` (una pestaña es una vista de la página, no una entrada de historial).

**S5 — el gate de roles se preserva.** `Accesos` sigue existiendo sólo detrás de
`ACCESS_READ_ROLES` (registro T96): sin `lc-admin`/`lc-employee-access` hay tres pestañas, no se
emite ningún request de acceso, y `?tab=accesos` cae a `Detalle`. Las cuatro lecturas siguen siendo
independientes: el fallo de una deja las otras pestañas en pie, con su propio error y su propio
`Reintentar`.

## Tasks

| ID  | Qué                                                                                                                                                            | Spec           | Ruta   | Commit     |
| --- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------- | ------ | ---------- |
| T1  | Tests RED de la estructura de pestañas: orden de labels, cada sección en su pestaña, botones por pestaña, gate de `Accesos`, `?tab=` en sus cuatro direcciones | S1, S2, S4, S5 | inline | `feat(hr)` |
| T2  | `mat-tab-group` con las cuatro secciones y las acciones dentro de cada una; `app-page-header` sin acciones                                                     | S1, S2         | inline | `feat(hr)` |
| T3  | Estado de pestaña en `?tab=` (`visibleTabs`, `activeTab`, `selectedTabIndex`, `onTabChange`) y acción `Editar`                                                 | S3, S4         | inline | `feat(hr)` |
| T4  | SCSS (`.tab-content`, `.tab-actions`), doc-comment de la página y copy del aviso `D9` («pestaña «Tiendas»»)                                                    | S1, S2         | inline | `feat(hr)` |
| T5  | Ajuste de las 37 aserciones existentes que leían toda la página: ahora abren la pestaña que leen                                                               | S1, S5         | inline | `feat(hr)` |

## Log

**L1** — Pedido del usuario, verbatim: `quiero modificar la vista de un empleado /hr/employees/ para
separarlo las secciones en pestañas Detalle, Contratos, Tiendas, Accesos cada uno con sus botones
correspondientes`.

**L2** — Dos decisiones que el pedido no resolvía, consultadas antes de escribir código. `Detalle`
no tenía acciones propias, así que se preguntó qué va ahí; respuesta, verbatim: `Editar + Volver al
registro`. Y si la pestaña activa se refleja en la URL; respuesta, verbatim: `Sí: ?tab=contratos`.

**L3** — Hallazgo que cambió el alcance de los tests. `mat-tab-body` de Material 22 attachea el
contenido de una pestaña recién cuando termina su transición (`MatTabBody._simulateTransitionEvents`),
así que el cuerpo de una pestaña cerrada no está en el DOM: 37 aserciones preexistentes que leían
`text()` de toda la página pasaron a abrir su pestaña primero. Eso además las vuelve más fuertes
(prueban que el contenido está dentro de su pestaña, no en cualquier parte). En jsdom hace falta
`{ provide: ANIMATION_MODULE_TYPE, useValue: 'NoopAnimations' }`, el patrón que ya usan
`employee-list.spec.ts` y `product-edit.spec.ts`, porque `@angular/animations` no está instalado en
v22 y `provideNoopAnimations()` no resuelve.

**L4** — Verificación, con los comandos corridos y su resultado: `npx ng test --no-watch
--include='**/employee-detail.spec.ts'` → **75/75** (12 nuevos creados en rojo primero, y los 37
ajustados); `npm run test:coverage:check` → exit 0, los cuatro umbrales OK (statements 86.50%,
branches 85.55%, functions 79.00%, lines 89.11%); `npm run lint` → `All files pass linting`;
`npm run build` → bundle generado sin errores; `npx prettier --check` sobre los archivos tocados →
limpio.

**L5** — `assess` nativo devolvió `nativeRisk: "unassessable"` porque el repositorio tiene un archivo
sin trackear previo a este trabajo (`docker/.env.dev.bak.20261019-0119`). El plan resultante fue
verificación del writer, sin verificador independiente, y es la que corre L4.

**L6** — La slice quedó por encima del presupuesto advisory de ~400 líneas pautadas: **760 líneas
pautadas en un commit**, contadas por el harness sobre `origin/main` (archivos de lock excluidos).
No se parte en cadena porque no hay unidad cohesiva que sobreviva al corte: la reestructuración del
template y las 37 aserciones que la leen son la misma unidad — un slice con la vista y sin el spec
deja `main` en rojo, y uno con el spec y sin la vista no compila. El volumen es además engañoso: el
cuerpo de las cuatro secciones se re-indentó al entrar en su `mat-tab`, así que cuenta como líneas
borradas y agregadas el mismo markup. La entrega va en un solo PR y el cuerpo del PR declara el
tamaño para el revisor. La alternativa real, si el revisor la pide, es partir por comportamiento
(`Editar` como slice propio, ~40 líneas), no por archivo.

**L7** — Entrega, con la evidencia fechada. El issue **#485** recibió `status:approved` (aprobación
humana, CONTRIBUTING §1), y el PR **#488** abrió contra `main` desde `feat/hr-employee-detail-tabs@bdfdd1e`
con el label único `type:feature` y `Closes #485`. CI del PR, los seis checks en verde: `A linked,
approved issue` (6s), `Exactly one type:* label` (10s), `Lint & Build` (45s), `Unit tests (1)` (2m22s),
`Unit tests (2)` (2m9s) y `Coverage` (23s). El merge no lo hace este registro: es decisión del
mantenedor.

**L8** — El header de este registro afirmaba «entregado para revisión… el merge es una decisión del
mantenedor»: cierto al escribirlo y superado por el merge `5fd86cf`. Pasó a la forma canónica, con el
número de PR, la rama y el merge commit, y esta reparación queda registrada acá porque la regla del
repo pide registrar la reparación y no sólo hacerla. Ningún identificador de esta reparación se
escribe antes de que exista: su evidencia está en el historial de `main`.
