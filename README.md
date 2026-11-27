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
                                    │  API (Modular    │◄──────►│  (Material)      │
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
| **Frontend**        | Angular 22.2.3 (SPA), Angular Material, Keycloak Angular |
| **Backend**         | Spring Boot 4.1.1 (Java 21)                         |
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
| LifeControl API     | `life-control-api/`     | Spring Boot 4.1 + PostgreSQL     | **Módulo central** — compañías, países, regiones, zonas, usuarios, roles, productos, ventas, compras, auditoría |
| API Gateway         | `api-gateway/`          | Spring Cloud Gateway             | Proxy, enrutamiento        |
| Angular App         | `life-control-app-angular/` | Angular 22.2.3, SPA, Material | Frontend de gestión        |

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
├── life-control-app-angular/           # Angular 22 (SPA + Material)
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
```

---

## Quick Start

### Prerrequisitos

- Java 21+
- Node.js 24.15+
- Docker + Docker Compose
- Angular CLI (`npm i -g @angular/cli`)

### 1. Infraestructura (Docker)

```bash
# Configurar el entorno (variables, directorios de volúmenes)
./docker/scripts/setup-env.sh dev        # dev | staging | prod

# Construir y levantar servicios
./docker/scripts/deploy.sh dev start

# Verificar estado
./docker/scripts/deploy.sh dev status
```

> **Orden de build**: `deploy.sh start` compila primero los servicios Java porque sus
> imágenes no compilan nada: el Dockerfile solo copia `build/libs/<servicio>-<version>.jar`.
> Si corrés `docker compose build` por tu cuenta, la imagen se arma con el JAR que ya esté
> en el directorio, sin garantía de que corresponda al código actual.

### 2. Base de datos

PostgreSQL (`lifecontrol-postgres`) se levanta junto con el resto de servicios vía `deploy.sh start`. El schema y los datos se gestionan con **Flyway**, la única fuente de verdad de la base de datos. Las migraciones versionadas viven en `life-control-api/src/main/resources/db/migration/` y se aplican automáticamente al arrancar la API.

**Una base nueva se migra entera, desde `V1`; una base que ya tiene historia no se toca.** El baseline de Flyway está en la versión **0** a propósito: un volumen recién creado **no está vacío** cuando Flyway llega, porque `docker/postgres-init/lifecontrol-roles.sql` ya creó `task_employee_company(uuid)` en `public`, y con baseline en 1 Flyway daba `V1` por aplicada sin ejecutarla (issue #722). El baseline no crea ni recrea nada en los entornos existentes: sólo se dispara cuando no hay tabla de historia. Si alguna vez hay que migrar una base sembrada **antes** de que existiera Flyway —el único caso para el que servía la versión 1—, eso es una acción explícita del operador, una sola vez: `flyway baseline -baselineVersion=1`.

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

| Servicio              | dev       | staging   | prod      |
|-----------------------|-----------|-----------|-----------|
| API Gateway           | :9000     | :9100     | :9200     |
| API Gateway Actuator  | :9001     | :9101     | :9201     |
| Keycloak              | :8181     | :8281     | :8381     |
| Grafana               | :3000     | :3100     | :3200     |
| Prometheus            | :9090     | :9190     | :9290     |
| Loki                  | :3100     | :3200     | :3300     |
| Tempo                 | :3110     | :3210     | :3310     |
| Angular App           | :4200     | :4320     | :4420     |
| LifeControl API       | :8082     | —         | —         |
| Swagger UI            | :8082/swagger-ui.html | —   | —   |

Los puertos web aplicados por entorno se definen en `docker/.env.<env>`. `LifeControl API` y `Swagger UI` se exponen solo en el desarrollo local vía `./gradlew bootRun`.

---

## Variables de Entorno Clave

| Variable                    | Default                                  | Descripción                    |
|-----------------------------|------------------------------------------|--------------------------------|
| `SERVER_PORT`               | `8082`                                   | Puerto HTTP life-control-api   |
| `DATABASE_URL`              | `jdbc:postgresql://localhost:5432/lifecontrol` | JDBC URL                |
| `DATABASE_USERNAME`         | `lifecontrol_app`                        | Rol del pool (ADR-0005)         |
| `DATABASE_PASSWORD`         | _(secreto: `lifecontrol_app_password`)_   | Password del pool               |
| `FLYWAY_USERNAME`           | `lifecontrol_owner`                       | Rol que corre las migraciones   |
| `FLYWAY_PASSWORD`           | _(secreto: `lifecontrol_postgres_password`)_ | Password de Flyway           |
| `REDIS_HOST`                | `localhost`                               | Host Redis                     |
| `KEYCLOAK_URI`              | `http://localhost:8080/realms/life-control-realm` | Keycloak realm URL    |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | _(secreto, ver Secretos)_              | Client secret admin            |

Ver [life-control-api/README.md](life-control-api/README.md) para la lista completa.

---

## Secretos

Todas las contraseñas se manejan con el mismo patrón en **dev, staging y prod**: archivos
materializados en `docker/secrets/` montados read-only en `/run/secrets/<nombre>` dentro del
contenedor. Ninguna contraseña vive en los `.env.*` (setup-env.sh las elimina al crear el archivo).

### Secretos existentes

El dueño, el grupo y el modo de cada archivo son parte del contrato: `setup-env.sh` los aplica y
`validate-env.sh` los verifica (la tabla completa, con el porqué de cada forma, está en
[`AGENTS.md`](AGENTS.md#secretos)). Un archivo con `0400` lo lee un solo uid; uno con `0440` lo leen dos,
y el `group_add` de esos dos servicios es lo que se lo permite.

| Archivo (`docker/secrets/`)           | Uso                                        | Quién lo lee                                    |
|---------------------------------------|--------------------------------------------|-------------------------------------------------|
| `lifecontrol_postgres_password`       | DB principal de la app                     | lifecontrol-postgres + lifecontrol-api + exporter |
| `keycloak_postgres_password`          | DB de Keycloak                             | keycloak-postgres + keycloak + exporter         |
| `keycloak_admin_password`             | Admin de Keycloak                          | keycloak (`KC_BOOTSTRAP_ADMIN_PASSWORD`)        |
| `grafana_admin_password`              | Admin de Grafana                           | grafana (`GF_SECURITY_ADMIN_PASSWORD`)          |
| `keycloak_admin_client_secret`        | Client secret de `life-control-admin-client` | lifecontrol-api (`KEYCLOAK_ADMIN_CLIENT_SECRET`) |
| `redis_password`                      | Password del cache                         | redis (`--requirepass`) + lifecontrol-api       |
| `smtp_password`                       | Credencial del relay SMTP                  | grafana (`GF_SMTP_PASSWORD`)                    |
| `fiscal_*`                            | Proveedor fiscal (CFDI)                    | lifecontrol-api                                 |

### Flujo de materialización

1. `./docker/scripts/setup-env.sh <env>` copia cada `docker/secrets/*.template` a
   `docker/secrets/<nombre>`, quitando comentarios, si el archivo aún no existe, y después aplica
   **dueño y modo por archivo** (tabla en [`AGENTS.md`](AGENTS.md#secretos)): `0400` con el uid que lo
   lee como dueño, o `0440 root:<gid>` cuando son dos uids distintos. Si ya existe, lo conserva y sólo
   corrige la forma.
2. Editar cada archivo con un valor real (reemplazar `CHANGEME`). `redis_password` y los cuatro del
   proveedor fiscal **se crean a mano**: no tienen `.template`.
3. `docker-compose` monta cada secreto read-only y los entrypoint wrappers
   (`docker/entrypoints/*.sh`) leen el archivo y exportan la env var que la imagen espera.
4. `./docker/scripts/deploy.sh <env> start` levanta los servicios. En todos los entornos
   `validate-env.sh` valida que cada secreto exista, no esté vacío, no contenga `CHANGEME` y que su
   modo, dueño y grupo coincidan con la tabla.

> **Primera vez en un checkout existente: un solo paso con `sudo`.** Los archivos de hoy son del
> operador, así que `setup-env.sh` no puede cambiarles el dueño por su cuenta. En vez de abortar en el
> primero, **junta todos** los que no pudo arreglar e imprime **un bloque copiable** con el orden que
> funciona — `deploy.sh <env> stop`, un `sudo chown … && sudo chmod …` por archivo, `deploy.sh <env> up`
> — y sale distinto de cero. Un viaje privilegiado, no once.

```bash
# Generar un valor fuerte para un secreto
openssl rand -base64 32
```

> **Rotación**: `./docker/scripts/cleanup.sh secrets <env>` regenera los archivos desde los
> templates (destructivo, pide confirmación). Luego reiniciar: `deploy.sh <env> restart`.
>
> **Primera migración**: al pasar de env vars a secretos, el datadir de postgres existente retiene
> la contraseña anterior. Para que la password nueva se aplique hay que recrearlo (pérdida de datos
> del entorno local): en dev y staging ese datadir es un **bind mount** bajo `$VOLUMES_ROOT`, así que
> lo borra `./docker/scripts/cleanup.sh local <env>` o `./docker/scripts/deploy.sh <env> clean --data`
> — `cleanup.sh volumes` sólo poda volúmenes con nombre y no lo toca. Los dos apuntan al directorio
> propio de ese entorno, `./volumes-<env>`, y sólo si es un directorio real (no un symlink); una ruta
> absoluta, otra ruta o un symlink se reporta y el borrado queda para el operador. En prod el datadir
> está fuera del checkout y el borrado es manual.

---

## Keycloak — Roles

| Rol        | Acceso                                                             |
|------------|--------------------------------------------------------------------|
| `lc-admin` | Rol de cliente de `life-control-client` — CRUD de todos los dominios |
| `admin`    | Realm role — endpoints de administración de usuarios y roles       |

> Los roles legacy `life-control-admin` y `life-control-country` están **retirados**: la API no
> acepta autoridad derivada de ellos y `keycloak-setup.sh` no los provisiona. Ver
> [`odd/tasks/retire-legacy-realm-roles.md`](odd/tasks/retire-legacy-realm-roles.md).

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
./docker/scripts/cleanup.sh [stop|docker|volumes|local|builds|all]
./docker/scripts/deploy.sh dev clean           # Baja el stack; los datos bajo VOLUMES_ROOT quedan
./docker/scripts/deploy.sh dev clean --data    # Además borra ./volumes-dev, el directorio propio del entorno (2ª confirmación)
```

### Build antes de la imagen

Las tres imágenes no se construyen igual:

- `api-gateway` y `life-control-api` **no compilan**: su Dockerfile solo hace `COPY` de un
  JAR generado localmente (`./gradlew bootJar`). Hay que compilar antes de construir la imagen.
- `web-app` **sí compila**: su Dockerfile multi-stage corre `npm run build` dentro del build,
  así que la imagen siempre refleja el working tree y no necesita pre-build.

`deploy.sh` aborta con exit code distinto de cero cuando:

- falta `api-gateway/gradlew` o `life-control-api/gradlew` (comandos `start` y `build`);
- falla el build de Gradle (comandos `start` y `build`);
- al construir imágenes, el JAR está ausente, vacío o es más viejo que las entradas de build
  del módulo (`src/main`, los archivos Gradle de la raíz, `gradle/`, `buildSrc/`) o que su
  último commit que toca esas entradas (comandos `start` y `build-images`).

La verificación del JAR corre en los caminos que construyen imágenes: `start` y
`build-images`. `deploy.sh <env> build` solo compila y no construye imágenes, y
`SKIP_BUILD=true start` tampoco las construye (usa las que ya existen), así que ninguno de
los dos corre esa verificación.

Antes seguía con un warning, lo que podía publicar una imagen armada con un JAR viejo sin
que se notara. El guard resuelve la versión del JAR como lo hace `docker/docker-compose.yml`
(shell, luego `--env-file`, luego `0.0.1-SNAPSHOT`).

Los guardas tienen tests propios, sin Docker ni Gradle, que salen con 0 solo si pasan todos
los casos:

```bash
./docker/scripts/tests/freshness-guard.test.sh
```

### Procedencia de la imagen

Las imágenes construidas a través de `deploy.sh` (es decir, por `docker compose`) llevan la
revisión git desde la que se generaron, así podés rastrear qué código contienen:

```bash
docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' lifecontrol-dev-lifecontrol-api
```

El valor lo sella `deploy.sh` con `git rev-parse HEAD` y cae a `unknown` cuando git no está
disponible. Un `docker build` directo, que no recibe el build arg `GIT_COMMIT`, graba el
literal `unknown`.

---

## Kubernetes (Opcional)

El árbol de `k8s/` es un playground local de `kind`: nada de este repo lo buildea, lo pushea ni lo
despliega, y por eso sus versiones de imagen están congeladas en una tabla propia, con esa razón
escrita, en `docker/scripts/tests/image-pins.test.sh`. Hay **una sola copia** de cada workload —se
borró la segunda, que nada mantenía en sincronía— y el punto de entrada es un kustomization.

Todo el árbol aterriza en el namespace `life-control`, que declara `k8s/manifests/namespace.yml` y
repite el campo `namespace:` del kustomization: esas dos son las únicas copias del nombre. Los
manifests referencian los Services por nombre corto, que resuelve dentro del namespace del pod, así
que renombrarlo son esas dos líneas y no una edición por archivo. Nada queda en `default`.

```bash
./k8s/kind/create-kind-cluster.sh
./k8s/kind/create-secrets.sh      # los Secrets se generan acá, no viven en el repo
kubectl apply -k k8s/manifests
kubectl get pods -n life-control  # los ocho workloads aterrizan acá
```

> **El nodo tiene que poder ejecutar la imagen del gateway, y no es gratis.**
> `saiupadhyayula007/new-api-gateway` está publicada **sólo para `linux/arm64`**: un tag, una
> arquitectura, sin build amd64 y sin digest que pinear. Un nodo de `kind` comparte el kernel del host,
> así que en un host amd64 el pod moría con `exec /cnb/process/web: exec format error` antes de
> arrancar —ningún `securityContext` arregla una arquitectura que la imagen no trae—. Por eso
> `create-kind-cluster.sh` registra el emulador arm64 (`tonistiigi/binfmt`, congelado en la tabla de
> `docker/scripts/tests/image-pins.test.sh`) **antes** de crear el clúster: el registro vive en el
> kernel del host y el nodo lo hereda sin instalar nada adentro. El script **lee el registro de vuelta**
> y aborta si no quedó, porque un emulador que no se registró es el mismo pod que no arranca, una hora
> después y en otro script. Bajo emulación el arranque es lento y **el criterio de «los ocho pods
> `Ready` en 10 minutos» no está garantizado**: dos corridas medidas tardaron el `Started
> ApiGatewayApplication` **165 s** y **884 s**, y la segunda pasa del límite. Un pod `NotReady` esos
> minutos es lo esperado y no un cuelgue; que el playground cumpla el criterio depende de un host arm64
> nativo, no de éste.
>
> Y el `readinessProbe` del gateway apunta a **`/actuator/prometheus`**, no a `/actuator/health`: la
> imagen contesta `401` en `/`, `/actuator`, `/actuator/health`, sus hijos y `/actuator/info`, y el
> kubelet lee un 401 como probe fallido —ese probe no podía pasar nunca—, mientras que la ruta de
> scrape del mismo puerto contesta `200` sin token. Es exactamente el path que `infra/prometheus.yml`
> ya scrapea.

> El orden importa: ningún `kind: Secret` vive en este repo, así que los tres objetos que los
> contenedores leen (`mysql-secrets`, `keycloak-mysql-secrets`, `keycloak-secrets`) tienen que existir
> antes de que arranquen los pods que los leen. Los crea `k8s/kind/create-secrets.sh`, que genera
> cada password en el momento: un objeto Secret commiteado es el archivo que alguien aplica por
> costumbre, y base64 no es cifrado. Correrlo dos veces no cambia nada, y rotar una password es
> borrar el Secret y volver a correrlo, a mano.
>
> Ese script escribe en el namespace que declara el kustomization y lo crea si no existe: corre
> **antes** del apply, así que el objeto `Namespace` todavía no está, y un Secret en `default` no lo
> puede leer ningún pod de este árbol. Si querés otro namespace, pasáselo como argumento, pero
> entonces el kustomization tiene que declarar ese mismo.
>
> La red también está cerrada por defecto. `k8s/manifests/network-policy.yml` deniega ingress y egress
> en todo el namespace y habilita **sólo** los flujos que los manifests declaran —Grafana a sus tres
> datasources, Prometheus al actuator del gateway, el gateway a Keycloak, Loki y Tempo, Keycloak a su
> MySQL, y DNS de clúster en el puerto 53 para todos—, cada uno con su regla de entrada y su regla de
> salida: una NetworkPolicy es direccional, y media regla no habilita nada. Un pod que no es uno de
> esos destinos no llega a MySQL.

---

## Licencia

MIT License — Ver [LICENSE](LICENSE) para detalles.

---

> **Nota**: Ver [AGENTS.md](AGENTS.md) para el estado actualizado de cada componente.
