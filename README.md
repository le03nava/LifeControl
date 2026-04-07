# LifeControl

Plataforma de gestión integral basada en microservicios Spring Boot con frontend Angular. Sistema completo para gestión de productos, pedidos, inventario y notificaciones.

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
        ┌────────────────────────────────────┼────────────────────────────────────┐
        │                                    │                                    │
        ▼                                    ▼                                    ▼
┌───────────────┐                    ┌───────────────┐                    ┌───────────────┐
│ Product       │                    │ Inventory     │                    │ LifeControl   │
│ Service       │                    │ Service       │                    │ API           │
│ (Spring Boot) │                    │ (Spring Boot) │                    │ (Spring Boot) │
│   MongoDB     │                    │   PostgreSQL  │                    │   PostgreSQL  │
└───────────────┘                    └───────────────┘                    └───────────────┘
        │                                                                              
        │                                                                              
        ▼                                                                              
┌───────────────┐                                                                      
│ Order Service │                                                                      
│ (Spring Boot) │                                                                      
│     MySQL     │                                                                      
└───────────────┘                                                                      
                                                                                       
                                    ┌─────────────────────────────────────────────────┐
                                    │                    Kafka                         │
                                    │  (Mensajería asíncrona / Event-driven)          │
                                    └─────────────────────────────────────────────────┘
                                             │
                                    ┌────────▼────────┐
                                    │   Notification │
                                    │    Service     │
                                    └─────────────────┘
```

## Tech Stack

| Capa | Tecnología |
|------|------------|
| **Frontend** | Angular 20.3.0 (SSR), Angular Material, Keycloak Angular |
| **Backend** | Spring Boot 3.x (Java 21), Spring Cloud Gateway |
| **Bases de Datos** | MongoDB, MySQL, PostgreSQL |
| **Mensajería** | Apache Kafka |
| **Auth** | Keycloak 26 (OIDC/OAuth2) |
| **Container** | Docker, Docker Compose |
| **Orquestación** | Kubernetes (Kind) |
| **Observabilidad** | Prometheus, Grafana, Loki, Tempo |

## Estructura del Proyecto

```
LifeControl/
├── frontend/                          # Angular 18 (legacy)
├── life-control-app-angular/           # Angular 20 (nuevo - SSR + Material)
│   ├── src/
│   │   ├── app/
│   │   │   ├── features/              # Feature modules
│   │   │   │   └── products/           # Products feature
│   │   │   ├── services/              # Core services
│   │   │   └── models/                # TypeScript models
│   │   └── styles.scss                # Global styles
│   └── package.json
│
├── api-gateway/                       # Spring Cloud Gateway
│   ├── src/main/java/
│   ├── scripts/                       # Deployment scripts
│   └── docker/                        # Docker configs
│
├── product-service/                   # Product Management
├── inventory-service/                # Inventory Management
├── life-control-api/                  # Core API
├── order-service/                     # Order Management
├── notification-service/            # Notifications
│
├── docker/                            # Docker Compose configs
│   ├── docker-compose.yml             # Base compose
│   ├── docker-compose.prod.yml        # Production overrides
│   ├── .env                           # Base env vars
│   ├── .env.dev                       # Development
│   ├── .env.staging                   # Staging
│   ├── .env.secrets.template          # Secrets template
│   └── scripts/                       # Utility scripts
│
├── k8s/                               # Kubernetes manifests
│   ├── kind/                          # Kind cluster setup
│   └── manifests/                    # K8s deployments
│
└── backstage/                        # Spotify Backstage (Developer Portal)
```

## Quick Start

### Prerrequisitos

- Java 21+
- Node.js 20+
- Docker + Docker Compose
- Angular CLI (`npm i -g @angular/cli`)

### Levantar Infraestructura (Docker)

```bash
# Copiar y configurar variables de entorno
cp docker/.env.dev docker/.env.local

# Levantar servicios de infraestructura
cd docker
docker-compose up -d mongodb mysql keycloak

# Levantar servicios de aplicación
docker-compose up -d api-gateway product-service web-app
```

### Servicios Disponibles

| Servicio | URL | Puerto |
|----------|-----|--------|
| Web App (Angular) | http://localhost:4200 | 4200 |
| API Gateway | http://localhost:9000 | 9000 |
| Keycloak Admin | http://localhost:8181 | 8181 |
| Product Service | http://localhost:8080 | 8080 |
| Grafana | http://localhost:3000 | 3000 |
| Prometheus | http://localhost:9090 | 9090 |
| Kafka UI | http://localhost:8086 | 8086 |

### Desarrollo Local (Frontend)

```bash
# Angular 20 (nuevo)
cd life-control-app-angular
npm install
npm start

# Angular 18 (legacy)
cd frontend
npm install
npm start
```

### Desarrollo Local (Backend)

```bash
# Usando Gradle
cd product-service
./gradlew bootRun

# O con Maven (si está configurado)
cd api-gateway
./mvnw spring-boot:run
```

## Scripts de Docker

Scripts utilitarios en `docker/scripts/`:

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
./docker/scripts/cleanup.sh [option] [env]

# Opciones:
# stop       - Detener solo contenedores (conserva volúmenes e imágenes)
# docker     - Limpiar Docker (contenedores, imágenes, redes) - conserva volúmenes
# volumes    - Eliminar TODOS los volúmenes (DESTRUCTIVO - pierde datos)
# local      - Limpiar directorios locales (./data, ./volume-data)
# builds     - Limpiar artifacts de build (./api-gateway/build)
# all        - Limpieza completa (stop + docker + volumes + local + builds)

# Entornos: dev (default), staging, prod

# Ejemplos:
./docker/scripts/cleanup.sh stop dev            # Detener contenedores
./docker/scripts/cleanup.sh docker dev          # Limpiar Docker (mantiene datos)
./docker/scripts/cleanup.sh volumes dev         # Eliminar volúmenes
./docker/scripts/cleanup.sh all staging         # Limpieza total
```

## Configuración

### Variables de Entorno

#### Desarrollo (`docker/.env.dev`)

```bash
# Perfil
SPRING_PROFILES_ACTIVE=dev

# Puertos
API_GATEWAY_PORT=9000
KEYCLOAK_PORT=8181
WEB_APP_PORT=4200

# Bases de datos
MONGO_ROOT_PASSWORD=devpass
MYSQL_ROOT_PASSWORD=devpass
PRODUCT_POSTGRES_PASSWORD=devpass
KEYCLOAK_POSTGRES_PASSWORD=devpass

# Keycloak
KC_ADMIN_USERNAME=admin
KC_ADMIN_PASSWORD=admin
KEYCLOAK_ISSUER_URI=http://localhost:8181/realms/life-control-realm

# Monitoring
LOG_LEVEL=DEBUG
TRACING_SAMPLING_PROBABILITY=1.0
```

#### Producción (`docker/.env.prod`)

```bash
# Perfil
SPRING_PROFILES_ACTIVE=prod

# Secrets (usar .env.secrets)
# MYSQL_ROOT_PASSWORD=<secure>
# KEYCLOAK_POSTGRES_PASSWORD=<secure>
# DATABASE_PASSWORD=<secure>
# JWT_SECRET_KEY=<256-bit-key>

# Monitoring
LOG_LEVEL=WARN
TRACING_SAMPLING_PROBABILITY=0.1
```

### Autenticación (Keycloak)

Credenciales por defecto:
- **Admin Console**: http://localhost:8181
- **Usuario**: `admin`
- **Contraseña**: `admin`

Realm: `life-control-realm`
Client: `life-control-client`

## API Endpoints

### API Gateway (Puerto 9000)

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| GET | `/api/product/**` | Product Service |
| GET | `/api/inventory/**` | Inventory Service |
| GET | `/api/order/**` | Order Service |
| GET | `/api/lifecontrol/**` | LifeControl API |

### Health Checks

```
GET /actuator/health          # Health general
GET /actuator/prometheus     # Métricas Prometheus
GET /actuator/circuitbreakers # Estado Circuit Breaker
```

## Testing

```bash
# Tests unitarios (Angular)
cd life-control-app-angular
npm test

# Tests con Coverage
npm test -- --coverage

# Tests de integración (Backend)
cd product-service
./gradlew integrationTest
```

## Kubernetes (Opcional)

```bash
# Crear Kind cluster
./k8s/kind/create-kind-cluster.sh

# Deploy infraestructura
kubectl apply -f k8s/manifests/infrastructure.yaml

# Deploy aplicaciones
kubectl apply -f k8s/manifests/applications.yaml

# Port-forward para acceso
kubectl port-forward svc/gateway-service 9000:9000
kubectl port-forward svc/keycloak 8181:8080
kubectl port-forward svc/grafana 3000:3000
```

## Observabilidad

### Grafana Dashboards

- **API Gateway**: Métricas de tráfico, latencia, errores
- **JVM Metrics**: Memoria, GC, threads
- **Business Metrics**: Orders, Products, Inventory

### Tracing Distribuido

- **Tempo**: http://localhost:3110
- **Loki** (Logs): http://localhost:3100

### Logs Estructurados

Formato: `{timestamp} [{traceId},{spanId}] {level} {logger} - {message}`

## Contribuir

1. Crear feature branch: `git checkout -b feature/nueva-feature`
2. Commitear cambios: `git commit -m 'feat: descripción'`
3. Push: `git push origin feature/nueva-feature`
4. Crear Pull Request

### Convenciones de Commits

```
feat:     Nueva feature
fix:      Bug fix
docs:     Documentación
refactor: Refactoring
test:     Tests
chore:    Mantenimiento
```

## Licencia

MIT License - Ver [LICENSE](LICENSE) para detalles.

---

**Nota**: Este proyecto está en constante evolución. La versión más reciente puede diferir de la documentación.
