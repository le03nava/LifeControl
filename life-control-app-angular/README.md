# LifeControl — Angular App

Aplicación Angular 20 con Signals, Zoneless, Standalone components y Angular Material 3, parte del ecosistema LifeControl de microservicios.

## Stack

| Capa              | Tecnología                                 |
|-------------------|--------------------------------------------|
| Framework         | Angular 20.3.0                             |
| Change Detection  | Zoneless (`provideZonelessChangeDetection`) |
| Componentes       | 100% Standalone + OnPush                   |
| State Management  | Angular Signals + `rxResource`             |
| UI                | Angular Material 20 (M3 theme)             |
| Autenticación     | Keycloak v26 (`keycloak-angular`)          |
| SSR               | `@angular/ssr`                             |
| Testing           | Vitest via `@angular/build:unit-test`      |
| Build             | esbuild (`@angular/build`)                 |
| Layout            | `@angular/cdk/layout` (BreakpointObserver) |

## Requisitos

- Node.js 20+
- Docker (para el ecosistema completo)

## Desarrollo

```bash
# Instalar dependencias
npm install

# Servidor de desarrollo (http://localhost:4200)
npm start

# Build de producción
npm run build

# Build con SSR
npm run build:ssr
```

```bash
# Tests unitarios
npm test

# Tests en modo watch
npm run test:watch

# Tests con coverage
npm run test:coverage
```

## Arquitectura

```
src/
├── app/               # Config raíz (providers, routing, root component)
├── core/              # Cross-feature: guards, interceptors, layout, keycloak
├── features/          # Feature modules con lazy loading
│   ├── companies/     # Empresas, países asignados, regiones
│   ├── countries/     # Catálogo de países
│   ├── home/          # Página principal
│   ├── auth/          # Login
│   └── users-admin/   # Administración de usuarios
├── shared/            # UI components, servicios globales, modelos, estilos
└── styles.scss        # Tema Material 3 + dark mode + CSS custom properties
```

Cada feature sigue esta estructura: `components/`, `data/`, `models/`, `pages/`, `index.ts`.

Path aliases disponibles: `@app`, `@core`, `@features`, `@shared`.

## Patrones Clave

- **Servicios**: retornan `Observable` y mantienen signals para estado síncrono (loading, error, listas cacheadas via `tap()`)
- **Páginas de listado**: `rxResource` para ciclo de vida HTTP automático con signals
- **Formularios**: `NonNullableFormBuilder` + `signal<FormGroup>` + `serverErrors` signal + `ApiError` handling
- **Navegación**: `history.state` para pasar datos a edit, `queryParams` para create/pre-selección
- **ConfigService**: URLs dinámicas desde `window.env`, cargado via `provideAppInitializer`

Para una guía detallada de patrones y convenciones, ver [`AGENTS.md`](./AGENTS.md).

## Roles y Permisos

La aplicación usa dos tipos de roles de Keycloak para control de acceso en frontend:

### Roles de Cliente (`life-control-client`)

Controlan el acceso a la feature **Companies**. Son roles jerárquicos: cada nivel acumula acceso a los subniveles inferiores.

| Rol | Companies CRUD | Countries | Regions | Zones | Stores | Menú lateral |
|-----|:---:|:---:|:---:|:---:|:---:|--------------|
| `lc-admin` | ✅ | ✅ | ✅ | ✅ | ✅ | Companies + Products + Compras + Users Admin |
| `lc-company` | ✅ | ✅ | ✅ | ✅ | ✅ | Companies |
| `lc-company-country` | ❌ | ✅ | ✅ | ✅ | ✅ | Companies |
| `lc-company-region` | ❌ | ❌ | ✅ | ✅ | ✅ | Companies |
| `lc-company-zone` | ❌ | ❌ | ❌ | ✅ | ✅ | Companies |
| `lc-company-store` | ❌ | ❌ | ❌ | ❌ | ✅ | Companies |

El dashboard **Companies Administration** oculta las cards a las que el usuario no tiene acceso.

### Realm Roles

| Rol | Acceso |
|-----|--------|
| `life-control-admin` | Products, Purchases |
| `life-control-country` | Products |
| `admin` | Users Admin |

> **Nota:** `lc-admin` es un rol de cliente (no realm). Los roles de realm usan nomenclatura distinta.

## Entorno

Las URLs de API y Keycloak se configuran en tiempo de ejecución via `window.env` (sin rebuild). Ver `ConfigService` en `src/app/services/config.service.ts`.

## Docker

Para rebuild rápido de solo el frontend:

```bash
npm run build
docker build --no-cache -t life-control-app-angular:latest .
docker compose -f ../docker/docker-compose.yml up -d --force-recreate web-app
```

## Links

- [Angular](https://angular.dev)
- [Angular Material](https://material.angular.io)
- [Keycloak Angular](https://www.npmjs.com/package/keycloak-angular)
- [Vitest](https://vitest.dev)
