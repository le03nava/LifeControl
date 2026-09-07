# LifeControl

Plataforma de gestión integral con backend Spring Boot y frontend Angular, consolidada en un monolito modular.

---

## Arquitectura

```
                                    ┌─────────────────┐
                                    │   Keycloak      │
                                    │  (Auth/OIDC)    │
                                    └────────┬────────┘
                                             │
                                    ┌────────▼────────┐
                                    │  API Gateway    │
                                    │ (Spring Cloud)  │
                                    └────────┬────────┘
                                             │
                                    ┌────────▼────────┐        ┌──────────────────┐
                                    │  LifeControl     │        │  Angular App     │
                                    │  API (Modular    │◄──────►│  (SSR + Material)│
                                    │   Monolith)      │        └──────────────────┘
                                    │  PostgreSQL      │
                                    └─────────────────┘
                                             │
                                    ┌────────▼────────┐
                                    │      Redis      │
                                    │    (Caching)    │
                                    └─────────────────┘
```

El proyecto consolidó toda la lógica de negocio en el monolito modular `life-control-api`. Los servicios de la antigua arquitectura de microservicios fueron eliminados.

---

## Tech Stack

| Capa                | Tecnología                                           |
|---------------------|------------------------------------------------------|
| **Frontend**        | Angular 20.3.0 (SSR), Angular Material, Keycloak Angular |
| **Backend**         | Spring Boot 3.4.0 (Java 21)                         |
| **Base de datos**   | PostgreSQL (principal), Redis (caching)              |
| **Auth**            | Keycloak 26 (OIDC/OAuth2)                            |
| **Documentación**   | SpringDoc OpenAPI (Swagger UI)                       |
| **Container**       | Docker, Docker Compose                                |
| **Orquestación**    | Kubernetes (Kind) — opcional                          |
| **Observabilidad**  | Prometheus, Grafana, Loki, Tempo, Zipkin             |

---

## Componentes

| Componente          | Directorio              | Stack                            | Rol                        |
|---------------------|-------------------------|----------------------------------|----------------------------|
| LifeControl API     | `life-control-api/`     | Spring Boot 3.4 + PostgreSQL     | **Módulo central** — compañías, países, regiones, zonas, usuarios, roles, productos, ventas, compras, auditoría |
| API Gateway         | `api-gateway/`          | Spring Cloud Gateway             | Proxy, enrutamiento        |
| Angular App         | `life-control-app-angular/` | Angular 20.3, SSR, Material | Frontend de gestión        |
| Backstage           | `backstage/`            | Backstage framework              | Developer portal           |

---

## Estructura del Proyecto

```
LifeControl/
├── life-control-api/                   # ◄── MÓDULO CENTRAL (monolito modular)
│   ├── src/main/java/com/lifecontrol/api/
│   │   ├── company/                    # Compañías, países asociados, regiones, zonas, stores
│   │   ├── country/                    # Catálogo de países
│   │   ├── product/                    # Productos, variantes, proveedores
│   │   ├── salesorder/                 # Pedidos de venta
│   │   ├── purchaseorder/              # Pedidos de compra
│   │   ├── customer/                   # Clientes
│   │   ├── supplier/                   # Proveedores
│   │   ├── usersadmin/                 # Admin Keycloak (usuarios, roles)
│   │   ├── activity/                   # Traza de auditoría (AOP)
│   │   ├── common/                     # Base Auditable, CurrentUserContext
│   │   ├── config/                     # Seguridad, cache, rate-limit, logbook
│   │   ├── validation/                 # Validadores custom (RFC MX)
│   │   └── exception/                  # GlobalExceptionHandler
│   ├── sdd/                            # SDD artifacts
│   └── build.gradle
│
├── life-control-app-angular/           # Angular 20 (SSR + Material)
│
├── api-gateway/                        # Spring Cloud Gateway
│
├── docker/
│   ├── docker-compose.yml
│   ├── docker-compose.prod.yml
│   └── scripts/
│       ├── setup-env.sh
│       ├── validate-env.sh
│       ├── deploy.sh
│       └── cleanup.sh
│
├── k8s/                                # Kubernetes manifests (opcional)
│
└── backstage/                          # Developer portal
```

---

## Quick Start

### Prerrequisitos

- Java 21+
- Node.js 20+
- Docker + Docker Compose
- Angular CLI (`npm i -g @angular/cli`)

### 1. Infraestructura (Docker)

```bash
# Configurar variables de entorno
cp docker/.env.dev docker/.env.local

# Levantar servicios base
cd docker
docker-compose up -d keycloak redis prometheus grafana loki tempo
```

### 2. Base de datos

```bash
# PostgreSQL para life-control-api
docker-compose up -d lifecontrol-postgres
```

La API inicializa el schema automáticamente vía `schema.sql` con `spring.sql.init.mode=always`.

### 3. Backend — LifeControl API

```bash
cd life-control-api

# Compilar
./gradlew build -x test

# Ejecutar
./gradlew bootRun
```

### 4. Frontend

```bash
cd life-control-app-angular
npm install
npm start
```

### 5. Documentación de API

```
http://localhost:8082/swagger-ui.html
```

---

## Servicios Disponibles

| Servicio                    | URL                              |
|-----------------------------|----------------------------------|
| Angular App                 | http://localhost:4200            |
| API Gateway                 | http://localhost:9000            |
| LifeControl API             | http://localhost:8082            |
| Swagger UI                  | http://localhost:8082/swagger-ui.html |
| Keycloak Admin              | http://localhost:8181            |
| Grafana                     | http://localhost:3000            |
| Prometheus                  | http://localhost:9090            |

---

## Variables de Entorno Clave

| Variable                    | Default                                  | Descripción                    |
|-----------------------------|------------------------------------------|--------------------------------|
| `SERVER_PORT`               | `8082`                                   | Puerto HTTP life-control-api   |
| `DATABASE_URL`              | `jdbc:postgresql://localhost:5432/lifecontrol` | JDBC URL                |
| `DATABASE_USERNAME`         | `lifecontrol_user`                        | Usuario DB                     |
| `DATABASE_PASSWORD`         | _(requerido)_                             | Password DB                    |
| `REDIS_HOST`                | `localhost`                               | Host Redis                     |
| `KEYCLOAK_URI`              | `http://localhost:8080/realms/life-control-realm` | Keycloak realm URL    |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | _(requerido)_                          | Client secret admin            |

Ver [life-control-api/README.md](life-control-api/README.md) para la lista completa.

---

## Keycloak — Roles

| Rol                      | Acceso                                             |
|--------------------------|----------------------------------------------------|
| `life-control-admin`     | CRUD completo de compañías, activity logs          |
| `life-control-country`   | Acceso limitado a compañías asignadas (vía JWT)    |
| `admin`                  | Endpoints de administración de usuarios y roles    |

---

## Convenciones de Código

- **Sin Lombok** en `life-control-api` — inyección por constructor, getters/setters manuales
- **DTOs con `record`** — inmutables, compact constructors
- **IDs con `UUID`** en todas las entidades
- **Paquetes por dominio**, no por capa
- **Commits**: conventional commits (`feat:`, `fix:`, `docs:`, etc.)

---

## Scripts de Docker

```bash
# Configurar entorno
./docker/scripts/setup-env.sh [dev|staging|prod]

# Validar configuración
cd docker && ./scripts/validate-env.sh

# Deploy
./docker/scripts/deploy.sh dev start
./docker/scripts/deploy.sh dev logs
./docker/scripts/deploy.sh dev health

# Limpiar
./docker/scripts/cleanup.sh [docker|local|builds|all]
```

---

## Kubernetes (Opcional)

```bash
./k8s/kind/create-kind-cluster.sh
kubectl apply -f k8s/manifests/infrastructure.yaml
kubectl apply -f k8s/manifests/applications.yaml
```

---

## Licencia

MIT License — Ver [LICENSE](LICENSE) para detalles.

---

> **Nota**: Ver [AGENTS.md](AGENTS.md) para el estado actualizado de cada componente.
