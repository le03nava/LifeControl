# Repository Guidelines

## How to Use This Guide

- Start here for project-wide norms.
- Each active component has specific guidelines in su `AGENTS.md` (ej: `life-control-api/AGENTS.md`).
- Component docs override this file when guidance conflicts.

---

## Available Skills

### Generic Skills
| Skill | Description | URL |
|-------|-------------|-----|
| `angular-21` | Angular 18+ patterns (signals, standalone, control flow) | [SKILL.md](.opencode/skill/angular-21/SKILL.md) |
| `spring-boot-3` | Spring Boot 3 patterns (DI, config, web services) | [SKILL.md](.opencode/skill/spring-boot-3/SKILL.md) |
| `sdd-init` | Spec-Driven Development initialization | [SKILL.md](.opencode/skill/sdd-init/SKILL.md) |
| `skill-creator` | Create new AI agent skills | [SKILL.md](.opencode/skill/skill-creator/SKILL.md) |

### Project-Specific Skills
| Skill | Description | URL |
|-------|-------------|-----|
| `sdd-explore` | Explore and investigate ideas | [SKILL.md](.opencode/skills/sdd-explore/SKILL.md) |
| `sdd-propose` | Create change proposals | [SKILL.md](.opencode/skills/sdd-propose/SKILL.md) |
| `sdd-spec` | Write detailed specifications | [SKILL.md](.opencode/skills/sdd-spec/SKILL.md) |
| `sdd-tasks` | Break down specs into tasks | [SKILL.md](.opencode/skills/sdd-tasks/SKILL.md) |
| `sdd-apply` | Implement tasks from specs | [SKILL.md](.opencode/skills/sdd-apply/SKILL.md) |
| `sdd-verify` | Validate implementation against specs | [SKILL.md](.opencode/skills/sdd-verify/SKILL.md) |
| `sdd-archive` | Sync specs and archive changes | [SKILL.md](.opencode/skills/sdd-archive/SKILL.md) |

---

## Auto-invoke Skills

| Action | Skill |
|--------|-------|
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

## Project Overview

LifeControl es un sistema de gestión consolidado en un monolito modular `life-control-api`.

| Component | Location | Tech Stack | Estado |
|-----------|----------|------------|--------|
| API Gateway | `api-gateway/` | Spring Boot | Activo |
| Life Control API | `life-control-api/` | Spring Boot, Java 21, PostgreSQL | **Activo** — núcleo del sistema |
| Angular App | `life-control-app-angular/` | Angular 20.3.0, Material | Activo |
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
npm start
npm run build
```

### Docker

Flujo canónico vía scripts (ver [Docker Scripts](#docker-scripts) arriba):

```bash
# Setup de entorno (una vez por entorno)
./docker/scripts/setup-env.sh dev        # dev | staging | prod
./docker/scripts/deploy.sh dev start     # Build + start
./docker/scripts/deploy.sh dev status    # Estado
```

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