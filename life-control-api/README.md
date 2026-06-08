# Life Control API

REST API central del sistema LifeControl. Microservicio Spring Boot que maneja la gestión de compañías, países, regiones, zonas, tiendas, usuarios, roles y auditoría operativa.

---

## Stack

| Componente        | Tecnología                        |
|-------------------|-----------------------------------|
| Framework         | Spring Boot 3.4.0                |
| Lenguaje          | Java 21                           |
| Build             | Gradle 8.x+                       |
| Base de datos     | PostgreSQL                        |
| Cache             | Redis (fallback in-memory)        |
| Seguridad         | OAuth2 / JWT (Keycloak)           |
| Documentación API | SpringDoc OpenAPI (Swagger UI)    |

---

## Quick Start

```bash
# 1. Compilar (sin tests para agilizar)
./gradlew build -x test

# 2. Ejecutar (requiere PostgreSQL y Redis accesibles)
./gradlew bootRun

# 3. Abrir Swagger UI
open http://localhost:8082/swagger-ui.html
```

> **Requiere**: Java 21, PostgreSQL corriendo, Redis (opcional — cae a in-memory).

---

## Endpoints principales

| Prefixo                                             | Auth                          | Descripción                              |
|-----------------------------------------------------|-------------------------------|------------------------------------------|
| `GET/POST /api/companies`                           | `lc-admin\|lc-company` (write) / `lc-admin\|lc-company\|lc-company-read` (read) | CRUD de compañías (paginado, búsqueda)  |
| `GET/POST /api/companies/{id}/countries`            | `lc-admin\|lc-company\|lc-company-country` (write) / `+ lc-company-country-read` (read) | Países asociados a una compañía          |
| `…/countries/{cid}/regions`                         | `lc-admin\|lc-company\|lc-company-country\|lc-company-region` (write) / `+ lc-company-region-read` (read) | Regiones por compañía-país               |
| `…/regions/{rid}/zones`                             | `lc-admin\|lc-company\|lc-company-country\|lc-company-region\|lc-company-zone` (write) / `+ lc-company-zone-read` (read) | Zonas por región                         |
| `…/zones/{zid}/stores`                              | `lc-admin\|lc-company\|lc-company-country\|lc-company-region\|lc-company-zone\|lc-company-store` (write) / `+ lc-company-store-read` (read) | Tiendas por zona                         |
| `GET/POST /api/countries`                           | autenticado                   | Catálogo de países                       |
| `GET /api/activity-logs`                            | `lc-admin`                    | Traza de auditoría (filtrable)           |
| `GET/POST /api/users-admin/users`                   | `admin`                       | Usuarios Keycloak (búsqueda, roles)      |
| `GET/POST /api/users-admin/roles`                   | `admin`                       | Roles Keycloak (realm y client)          |
| `GET /actuator/health`                              | público                       | Health check                             |
| `GET /actuator/prometheus`                          | público                       | Métricas Prometheus                      |

---

## Roles Keycloak

| Rol                      | Acceso                                             |
|--------------------------|----------------------------------------------------|
| `lc-admin`               | CRUD completo en compañías, países, regiones, zonas, tiendas y activity logs |
| `lc-company`             | CRUD en compañías asignadas y jerarquía completa (vía `company_id` JWT) |
| `lc-company-country`     | CRUD en asociaciones compañía-país                  |
| `lc-company-country-read`| Solo lectura de asociaciones compañía-país          |
| `lc-company-region`      | CRUD en regiones                                    |
| `lc-company-region-read` | Solo lectura de regiones                            |
| `lc-company-zone`        | CRUD en zonas                                       |
| `lc-company-zone-read`   | Solo lectura de zonas                               |
| `lc-company-store`       | CRUD en tiendas                                     |
| `lc-company-store-read`  | Solo lectura de tiendas                             |
| `lc-company-read`        | Solo lectura de compañías                           |
| `admin`                  | Endpoints de administración de usuarios y roles     |

---

## Variables de entorno

| Variable                          | Default                                              | Descripción                     |
|-----------------------------------|------------------------------------------------------|---------------------------------|
| `SERVER_PORT`                     | `8082`                                               | Puerto HTTP                     |
| `DATABASE_URL`                    | `jdbc:postgresql://localhost:5432/lifecontrol`       | JDBC URL                        |
| `DATABASE_USERNAME`               | `lifecontrol_user`                                   | Usuario DB                      |
| `DATABASE_PASSWORD`               | _(requerido)_                                        | Password DB                     |
| `REDIS_HOST`                      | `localhost`                                          | Host Redis                      |
| `REDIS_PORT`                      | `6379`                                               | Puerto Redis                    |
| `KEYCLOAK_URI`                    | `http://localhost:8080/realms/life-control-realm`    | Keycloak realm URL              |
| `KEYCLOAK_ADMIN_SERVER_URL`       | `http://localhost:8080`                              | Keycloak admin URL              |
| `KEYCLOAK_ADMIN_CLIENT_SECRET`    | _(requerido para admin)_                             | Client secret administrador     |
| `LOKI_URL`                        | `http://loki:3100/loki/api/v1/push`                  | Loki push endpoint              |

---

## Convenciones de código

- **Sin Lombok** — inyección por constructor explícito, getters/setters manuales
- **DTOs con `record`** — inmutables, compact constructors para defaults
- **`var` para variables locales** — inferencia de tipo donde el tipo es obvio
- **`@Transactional` en services** — `readOnly = true` en consultas
- **IDs con `UUID`** — `GenerationType.UUID` en todas las entidades
- **Soft delete** — columna `enabled`, DELETE setea a `false`
- **Paquetes por dominio** — no por capa (ver estructura abajo)

---

## Estructura del proyecto

```
src/main/java/com/lifecontrol/api/
├── company/          → Compañías, países asociados, regiones, zonas
├── country/          → Catálogo de países
├── usersadmin/       → Admin de Keycloak (usuarios, roles)
├── activity/         → Traza de auditoría (AOP + eventos)
├── common/           → Base Auditable, CurrentUserContext
├── config/           → Seguridad, cache, rate-limit, logbook, OpenAPI
├── validation/       → Validadores custom (RFC MX)
└── exception/        → GlobalExceptionHandler

src/test/java/com/lifecontrol/api/
├── company/controller/   → Tests de controladores (MockMvc)
├── company/service/      → Tests de servicios (Mockito)
├── company/dto/          → Tests de validación de DTOs
├── country/              → Tests de país
├── activity/             → Tests de auditoría
├── usersadmin/           → Tests de admin de usuarios
├── config/               → Tests de rate-limit, logbook, JWT
├── validation/           → Tests de RFCValidator
└── exception/            → Tests de GlobalExceptionHandler
```

---

## Comandos útiles

```bash
# Build completo con tests
./gradlew build

# Build sin tests
./gradlew build -x test

# Ejecutar
./gradlew bootRun

# Test específico
./gradlew test --tests "com.lifecontrol.api.company.controller.CompanyControllerTest"

# Perfil producción
./gradlew bootRun --args='--spring.profiles.active=prod'

# JAR
./gradlew bootJar
```

---

## Documentación de API

- **Swagger UI**: `http://localhost:8082/swagger-ui.html`
- **OpenAPI JSON**: `http://localhost:8082/api-docs`

---

## Docker

```bash
# Build JAR y luego imagen
./gradlew bootJar
docker build -t life-control-api:latest .

# Ejecutar
docker run -p 8082:8082 \
  -e DATABASE_PASSWORD=secreto \
  life-control-api:latest
```

Ver [`docker/scripts/`](../docker/scripts/) para scripts de deploy multi-entorno.

---

## Testing

El proyecto tiene ~40 clases de test que cubren:

- **Controllers**: MockMvc standalone + GlobalExceptionHandler
- **Services**: Mockito + JUnit 5 con `@Nested`
- **Security**: tests de autorización con JWT simulado
- **Validation**: unit tests de `@ValidRFC`
- **Integration**: rate-limit, activity-log aspect, JWT decoder

```bash
# Todos los tests
./gradlew test

# Solo tests de company
./gradlew test --tests "com.lifecontrol.api.company.*"
```

---

## Licencia

Proyecto interno — LifeControl.
