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
| Creación | `herdr worktree create --path ... --base main --no-focus` |
| Limpieza | `herdr workspace close` → `git worktree remove` → `git worktree prune`, en ese orden |

> **Requisito de trust**: Pi carga `.agents/skills/` desde el `cwd` y sus directorios **ancestros**, y sólo
> después de confiar el proyecto. Un worktree no es hijo del repo, así que una entrada de trust para el
> anchor **no lo cubre**. Hay que confiar `~/workspace/LifeControl-worktrees` como carpeta padre: una sola
> entrada cubre todos los worktrees actuales y futuros.
>
> **`herdr workspace close --group` cierra el workspace primario y todos los worktrees linkeados.** No lo
> uses para saltear un error de cierre.

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

> CI: `.github/workflows/angular-ci.yml` corre `lint` + `build` + `test:coverage:check` en pushes a `main` y PRs que toquen `life-control-app-angular/`.

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