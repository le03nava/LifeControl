# Repository Guidelines

## How to Use This Guide

- Start here for project-wide norms.
- Each component has specific guidelines in su `AGENTS.md` (ej: `frontend/AGENTS.md`, `order-service/AGENTS.md`).
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

LifeControl es un sistema de gestión con arquitectura de microservicios.

| Component | Location | Tech Stack |
|-----------|----------|------------|
| Frontend | `frontend/` | Angular 19, Standalone, Signals |
| API Gateway | `api-gateway/` | Spring Boot |
| Life Control API | `life-control-api/` | Spring Boot |
| Order Service | `order-service/` | Spring Boot, Gradle |
| Inventory Service | `inventory-service/` | Spring Boot, Gradle |
| Product Service | `product-service/` | Spring Boot, Gradle |
| Notification Service | `notification-service/` | Spring Boot |
| Angular App | `life-control-app-angular/` | Angular legacy |
| Backstage | `backstage/` | Backstage framework |

---

## Docker Scripts

Scripts en `docker/scripts/`:

### setup-env.sh
Configura el entorno de Docker. Crea volúmenes, copia archivos de entorno y valida Docker.
```bash
./docker/scripts/setup-env.sh [dev|staging|prod]
```
- **dev**: Environment de desarrollo (puerto 9000)
- **staging**: Environment de staging (puerto 9100)
- **prod**: Environment de producción (puerto 9200)

### validate-env.sh
Valida la configuración del entorno (archivo .env, variables requeridas, puertos, Docker).
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
./docker/scripts/cleanup.sh [docker|local|builds|all]

# Opciones:
# docker  - Limpia solo contenedores, imágenes, volúmenes de Docker
# local   - Limpia directorios locales (./data, ./volume-data)
# builds  - Limpia artifacts de build (./api-gateway/build)
# all     - Limpieza completa (requiere confirmación)
```

### Service URLs

| Environment | API Gateway | Keycloak | Grafana | Prometheus |
|-------------|-------------|----------|---------|------------|
| dev         | localhost:9000 | localhost:8181 | localhost:3000 | localhost:9090 |
| staging     | localhost:9100 | localhost:8281 | localhost:3100 | localhost:9190 |
| prod        | localhost:9200 | localhost:8381 | localhost:3200 | localhost:9290 |

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
cd frontend
npm install
npm start
npm run build
```

### Docker
```bash
cd docker
docker-compose up -d
docker-compose -f docker-compose.prod.yml up -d
```

---

## Commit & Pull Request Guidelines

Follow conventional-commit style: `<type>[scope]: <description>`

**Types:** `feat`, `fix`, `docs`, `chore`, `perf`, `refactor`, `style`, `test`

---

## Component-Specific Guidelines

Para cada componente, ver su propio `AGENTS.md`:
- `frontend/AGENTS.md` - Angular patterns
- `order-service/AGENTS.md` - Spring Boot service patterns
- `inventory-service/AGENTS.md` - Spring Boot service patterns
- `product-service/AGENTS.md` - Spring Boot service patterns