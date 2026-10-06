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

## Trabajo en paralelo con worktrees

Aislamiento para trabajo concurrente. El detalle completo, los comandos y los anti-patrones están en
[`references/worktrees.md`](.agents/skills/project-conventions/references/worktrees.md).

| Regla | Valor |
|-------|-------|
| Un worktree por unidad de trabajo | Una feature, un fix, un PR |
| Un agente por worktree | Dos agentes en el mismo cwd se pisan los archivos a nivel filesystem; Git no lo puede evitar |
| El anchor queda en `main` y limpio | `~/workspace/LifeControl` es la referencia para `fetch`, `log` y `rebase` |
| Path del worktree | `~/workspace/LifeControl-worktrees/<slug>`, hermano del repo, nunca adentro |
| Slug del directorio | Igual al de la rama, con `/` → `-`: `feat/x` → `feat-x` |
| Creación | `herdr worktree create --path ... --branch <branch> --base main --no-focus` (sin `--branch` la rama sale aleatoria) |
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
| Qué lleva el header | Estado **terminal** con su evidencia (PR, merge commit, fecha) o **qué trabajo queda** (diferido o bloqueado, con la razón) |
| Qué no lleva nunca | Estado vivo o pendiente: "sin pushear", "no hay PR abierto", "en progreso", "esperando review", "sin mergear" |
| Dónde va la entrega | En el log de evidencia fechado del propio registro, con fecha, PR y commit |
| Por qué | El header se escribe **antes** del merge y nadie lo revisita: un estado vivo queda falsificado por el próximo merge, sin que ningún commit toque la frase que mintió |
| Enforcement | Ninguno automático: es una convención de escritura. Los registros anteriores a esta regla no se reescriben, porque su narrativa congelada es en sí misma evidencia |

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
cd docker && ./scripts/validate-env.sh
```

### deploy.sh
Script principal de despliegue. Build y start de servicios.
```bash
./docker/scripts/deploy.sh [dev|staging|prod] [start|stop|restart|build|up|logs|status|clean|health]

# Ejemplos:
./docker/scripts/deploy.sh dev start      # Build y start desarrollo
./docker/scripts/deploy.sh dev up         # Start sin build
./docker/scripts/deploy.sh dev build      # Solo build
./docker/scripts/deploy.sh dev logs      # Ver logs
./docker/scripts/deploy.sh dev status    # Estado de servicios
./docker/scripts/deploy.sh dev health     # Health check
./docker/scripts/deploy.sh dev clean      # Remove volumes
```

### cleanup.sh
Limpia recursos de Docker y archivos locales.
```bash
./docker/scripts/cleanup.sh [stop|docker|volumes|local|builds|secrets|all|help] [dev|staging|prod]

# Opciones:
# stop    - Detener contenedores (conserva volúmenes, imágenes, redes)
# docker  - Limpia contenedores, imágenes y redes de Docker (conserva volúmenes)
# volumes - Limpia SOLO volúmenes de Docker (DESTRUCTIVO - borra todos los datos)
# local   - Limpia directorios locales (./data, ./volume-data)
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

### Secretos
Todas las contraseñas viajan por archivos `docker/secrets/*` (NO por los `.env.*`) con el **mismo patrón en dev, staging y prod**: `setup-env.sh` materializa cada `*.template` (strip de comentarios, `chmod 0444`), compose los monta read-only en `/run/secrets/<nombre>`, y los wrappers en `docker/entrypoints/*.sh` leen el archivo y exportan la env var que la imagen espera (`KC_DB_PASSWORD`, `POSTGRES_PASSWORD`, `DATABASE_PASSWORD`, `GF_SECURITY_ADMIN_PASSWORD`, `KEYCLOAK_ADMIN_CLIENT_SECRET`).
- Secretos: `keycloak_postgres_password`, `keycloak_admin_password`, `keycloak_admin_client_secret`, `lifecontrol_postgres_password`, `grafana_admin_password`.
- `validate-env.sh` valida los secretos en todos los entornos; `deploy.sh start` en prod lo usa como gate.

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

### Service URLs

> **Source of truth**: Service URLs are defined by port variables in `docker/.env.<env>` files.
> The scripts derive URLs from these env vars at runtime. The table below is a human-readable reference only.

| Environment | API Gateway | Actuator | Keycloak | Grafana | Prometheus | Loki | Tempo |
|-------------|-------------|----------|----------|---------|------------|------|-------|
| dev         | localhost:9000 | localhost:9001 | localhost:8181 | localhost:3000 | localhost:9090 | localhost:3100 | localhost:3110 |
| staging     | localhost:9100 | localhost:9101 | localhost:8281 | localhost:3100 | localhost:9190 | localhost:3200 | localhost:3210 |
| prod        | localhost:9200 | localhost:9201 | localhost:8381 | localhost:3200 | localhost:9290 | localhost:3300 | localhost:3310 |

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
npm run test:coverage:check # unit + enforcement de umbrales de cobertura (CI)
npm run test:e2e            # E2E con Playwright (mocks de Keycloak y API)
```

> CI: `.github/workflows/angular-ci.yml` corre `lint` + `build` + `test:coverage:check`. Los dos triggers
> no filtran igual: el de **PR** filtra por paths (`life-control-app-angular/**`), mientras que el de
> `push` a `main` **no** filtra, así que todo merge a `main` corre el workflow completo aunque el diff sea
> un solo `.md`. Lo mismo vale para los otros tres workflows, y tiene una consecuencia práctica:
> `gh pr checks` **vacío** en un PR no significa que su merge a `main` no corra CI.

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

---

## Component-Specific Guidelines

Para cada componente activo, ver su propio `AGENTS.md`:
- `life-control-api/AGENTS.md` - Spring Boot 3 patterns + NO Lombok (records, constructor injection)