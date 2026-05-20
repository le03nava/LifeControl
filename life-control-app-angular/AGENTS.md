# Life Control App Angular - Developer Guide

## Descripción

Aplicación Angular principal del proyecto LifeControl. Esta es la aplicación Angular más moderna del proyecto (v20.3.0), utilizando patrones de última generación como Signals, Zoneless y componentes standalone.

---

## Tech Stack

| Componente | Tecnología |
|------------|------------|
| Framework | Angular 20.3.0 |
| State Management | Angular Signals |
| Change Detection | Zoneless |
| Componentes | 100% Standalone |
| Authentication | Keycloak v26 (`keycloak-js`) |
| UI Library | Angular Material 20 |
| SSR | `@angular/ssr` 20.3.6 |
| Testing | Vitest (migrado desde Karma+Jasmine) |
| Build System | esbuild (`@angular/build`) |

---

## Arquitectura

### Estructura de Directorios

```
src/
├── app/                    # Configuración de la app
│   ├── app.config.ts       # Providers (Zoneless, HTTP, Keycloak)
│   ├── app.routes.ts      # Rutas raíz con lazy loading
│   └── app.ts             # Root component
├── core/                  # Configuración central
│   ├── config/            # Configuración de Keycloak
│   ├── guards/            # Functional route guards
│   ├── interceptors/      # Functional HTTP interceptors
│   └── layout/            # Header, footer, layout
├── features/              # Módulos por característica
│   ├── companies/
│   ├── products/
│   ├── users/
│   ├── home/
│   └── auth/
├── shared/                # Componentes compartidos
│   └── ui/                # Atomic Design (atoms, molecules)
└── styles.scss            # Estilos globales
```

### Path Aliases

El proyecto usa TypeScript path aliases:

| Alias | Ruta Real |
|-------|-----------|
| `@app/*` | `src/app/*` |
| `@core/*` | `src/core/*` |
| `@features/*` | `src/features/*` |
| `@shared/*` | `src/shared/*` |

---

## Patrones de Código

### Service Pattern (Signals)

Los servicios deben usar signals privados con acceso público de solo lectura:

```typescript
@Injectable({ providedIn: 'root' })
export class CompanyService {
  private readonly http = inject(HttpClient);
  
  // Signals privados
  private _companies = signal<Company[]>([]);
  private _loading = signal(false);
  private _error = signal<string | null>(null);
  
  // Señales públicas de solo lectura
  readonly companies = this._companies.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();
  
  // Valor computado
  readonly hasCompanies = computed(() => this._companies().length > 0);
  
  getAll(): void {
    this._loading.set(true);
    this.http.get<Company[]>('http://localhost:9000/api/companies')
      .pipe(
        tap(companies => this._companies.set(companies)),
        catchError(error => {
          this._error.set(error.message);
          return EMPTY;
        }),
        finalize(() => this._loading.set(false))
      ).subscribe();
  }
}
```

### Component Pattern (Signal Inputs)

Componentes modernos con signal inputs:

```typescript
@Component({
  standalone: true,
  imports: [CommonModule, MatTableModule],
  templateUrl: './companies-table.html',
  styleUrl: './companies-table.scss',
})
export class CompaniesTable {
  // Signal input (Angular 17+)
  readonly companies = input.required<Company[]>();
  readonly loading = input<boolean>(false);
  
  // Signal output
  readonly editCompany = output<Company>();
  readonly deleteCompany = output<Company>();
  
  // Computed
  readonly displayedColumns = computed(() => 
    this.companies().length > 0 
      ? ['name', 'rfc', 'email', 'actions'] 
      : []
  );
}
```

### Route Pattern (Lazy Loading)

Rutas con lazy loading y guards funcionales:

```typescript
export const routes: Routes = [
  {
    path: 'companies',
    loadChildren: () => import('@features/companies/companies.routes')
      .then(m => m.companyRoutes),
  },
  {
    path: 'products',
    loadComponent: () => import('@features/products/pages/product-list')
      .then(m => m.ProductList),
  },
  {
    path: 'admin',
    canActivate: [keycloakRoleGuard],
    data: { role: 'admin' },
    loadChildren: () => import('@features/admin/admin.routes')
      .then(m => m.adminRoutes),
  },
];
```

### Guard Pattern (Functional)

Guards funcionales (no usar clase):

```typescript
// ✅ CORRECTO - Functional guard
export const keycloakRoleGuard: CanActivateFn = async (route, state) => {
  const keycloak = inject(KeycloakService);
  
  const requiredRole = route.data?.['role'];
  
  if (!requiredRole) {
    return true;
  }
  
  const hasRole = keycloak.isUserInRole(requiredRole);
  
  if (!hasRole) {
    return createUrlTreeFromSnapshot(route, ['/unauthorized']);
  }
  
  return true;
};

// ❌ INCORRECTO - Class-based guard (deprecated)
@Injectable()
export class AuthGuard implements CanActivate { ... }
```

### Interceptor Pattern (Functional)

Interceptores funcionales:

```typescript
// ✅ CORRECTO - Functional interceptor
export const bearerTokenInterceptor: HttpInterceptorFn = (req, next) => {
  const keycloak = inject(KeycloakService);
  
  const token = keycloak.token;
  
  if (token) {
    const authReq = req.clone({
      setHeaders: { Authorization: `Bearer ${token}` }
    });
    return next(authReq);
  }
  
  return next(req);
};

// ❌ INCORRECTO - Class-based interceptor (deprecated)
@Injectable()
export class TokenInterceptor implements HttpInterceptor { ... }
```

### Atomic Design (UI Components)

Estructura de componentes usando Atomic Design:

```
src/shared/ui/
├── button/              # Atom: Botón básico
│   ├── button.ts
│   ├── button.html
│   └── button.scss
├── input/               # Atom: Input básico
│   └── ...
├── modal.ts             # Molecule: Modal dialog
├── form-input/          # Molecule: Input con label y validación
├── field.ts             # Molecule: Campo de formulario
├── spinner.ts           # Atom: Loading spinner
├── companies-table/     # Organism: Tabla de empresas
│   └── ...
└── companies-card/      # Organism: Card de empresa
    └── ...
```

---

## Configuración

### Zoneless Change Detection

```typescript
// app.config.ts
export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes),
    provideHttpClient(withInterceptors([bearerTokenInterceptor])),
    provideAnimations(),
    provideZonelessChangeDetection(),  // ✅ Zoneless
    provideClientHydration(),
    KeycloakService,
  ]
};
```

### Path Aliases (tsconfig.json)

```json
{
  "compilerOptions": {
    "baseUrl": ".",
    "paths": {
      "@app/*": ["src/app/*"],
      "@core/*": ["src/core/*"],
      "@features/*": ["src/features/*"],
      "@shared/*": ["src/shared/*"]
    }
  }
}
```

---

## Testing

### Configuración

- **Test Runner**: Vitest (configurado en `angular.json` con `@angular/build:unit-test`)
- **Framework**: Vitest + Angular Testing (TestBed)
- **Spec Files**: Co-locados con componentes (`*.spec.ts`)
- **Coverage**: `@vitest/coverage-v8`

### Commands

```bash
# Ejecutar tests
npm test

# Tests en modo watch
npm run test:watch

# Tests con coverage
npm run test:coverage
```

### Migración desde Jasmine (2026-05)

**Cambios principales:**

| Jasmine | Vitest |
|---------|--------|
| `jasmine.createSpy()` | `vi.fn()` |
| `jasmine.createSpyObj()` | `{ fn: vi.fn() }` |
| `jasmine.SpyObj<T>()` | `Partial<T>` o `Record<string, vi.fn()>` |
| `done()` callbacks | `async/await` + `firstValueFrom()` |
| `toBeTrue()` / `toBeFalse()` | `toBe(true)` / `toBe(false)` |
| `jasmine.any()` | `expect.any()` |

**Mocks de servicios con `done()`:**

```typescript
// ❌ ANTIGUO (Jasmine)
it('should load companies', (done) => {
  service.getAll();
  httpMock.expectOne('/api/companies').flush(mockData);
  setTimeout(() => {
    expect(service.companies()).toEqual(mockData);
    done();
  }, 100);
});

// ✅ NUEVO (Vitest)
it('should load companies', async () => {
  service.getAll();
  httpMock.expectOne('/api/companies').flush(mockData);
  await firstValueFrom(toObservable(service.companies));
  expect(service.companies()).toEqual(mockData);
});
```

**Mocks de matchMedia (para Angular Material):**

```typescript
// Setup global en test-setup.ts o beforeEach
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation(query => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
});
```

**Fake Timers:**

```typescript
// ✅ Vitest
it('should debounce search', async () => {
  vi.useFakeTimers();
  
  component.search('test');
  vi.advanceTimersByTime(300);
  
  expect(mockService.search).toHaveBeenCalledWith('test');
  
  vi.useRealTimers();
});
```

### Ejemplo de Test (Vitest)

```typescript
import { firstValueFrom, toObservable } from '@angular/core/rxjs-interop';

describe('CompanyService', () => {
  let service: CompanyService;
  let httpMock: HttpTestingController;
  
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CompanyService]
    });
    
    service = TestBed.inject(CompanyService);
    httpMock = TestBed.inject(HttpTestingController);
  });
  
  it('should be created', () => {
    expect(service).toBeTruthy();
  });
  
  it('should load companies', async () => {
    const mockCompanies: Company[] = [
      { id: '1', name: 'Test', rfc: 'XAXX010101', email: 'test@test.com' }
    ];
    
    service.getAll();
    
    httpMock.expectOne('http://localhost:9000/api/companies')
      .flush(mockCompanies);
    
    // Wait for signal update
    await firstValueFrom(toObservable(service.companies));
    
    expect(service.companies()).toEqual(mockCompanies);
  });
});
```

### Notas Importantes

1. `@angular/build:unit-test` ignora `vitest.config.ts` - usar `setupFiles` en `angular.json`
2. El mock de matchMedia debe incluir `addListener`/`removeListener` para Angular Material
3. Router mocks no pueden combinarse con `provideRouter()` - usar `vi.spyOn(router, 'navigate')`
4. Los tests asíncronos usar `firstValueFrom` + `toObservable` para esperar signal updates

---

## Commands

### Desarrollo

```bash
# Servidor de desarrollo
npm start

# Puerto por defecto: 4200
```

### Build

```bash
# Production build
npm run build

# Build con SSR
npm run build:ssr
```

### Testing

```bash
# Ejecutar tests
npm test

# Tests en modo watch
npm test -- --watch
```

### Docker

#### Rebuild Rápido (solo cambios en Angular)

```bash
# 1. Build local de Angular (desde el directorio del proyecto)
cd life-control-app-angular
npm run build

# 2. Build de imagen Docker (sin caché para actualizar COPY dist/)
docker build --no-cache -t life-control-app-angular:latest .

# 3. Recrear el contenedor (desde el directorio docker)
cd ../docker
docker compose up -d --force-recreate web-app
```

**Por qué no usar `deploy.sh dev start`:**
-Hace un rebuild multi-stage completo de TODOS los servicios (incluyendo Gradle)
-Los builds de Gradle dentro del contenedor fallan por falta de conectividad a internet
-Es innecesariamente lento para cambios solo en Angular

**Por qué `--force-recreate` y `--no-cache`:**
-`restart` solo reinicia el contenedor pero no carga la nueva imagen
-`--no-cache` evita que Docker use caché stale para el `COPY dist/`
-`--force-recreate` destruye y crea un contenedor nuevo con la imagen actualizada

**Verificar que los cambios aplican:**
```bash
# Verificar que los archivos en el contenedor tienen la fecha correcta
docker exec lifecontrol-dev-web-app ls -la /app/public/*.js | head -5

# Los archivos deben mostrar la fecha/hora del último build local
```

#### Troubleshooting

Si después del rebuild no ves los cambios:
1. Hard refresh en el browser: `Ctrl + Shift + R` (Chrome) o `Cmd + Shift + R` (Safari)
2. Verificar fecha de archivos en el contenedor (debe ser reciente)
3. Si sigue sin funcionar, limpiar cache de Docker: `docker builder prune -af`

---

## Referencias

- [Angular 20 Documentation](https://angular.dev)
- [Angular Signals](https://angular.dev/guide/signals)
- [Keycloak Angular](https://github.com/mauriziovigelati/angular-keycloak)
- [Angular Material](https://material.angular.io)

---

## Notas

- **NO usar NgModules** - Todos los componentes deben ser standalone
- **NO usar NgRx** - Usar Signals para state management
- **NO usar Lombok** - Esta regla aplica al backend (Spring Boot), no a Angular
- **Priorizar Signals** sobre RxJS para estado síncrono
- **Usar RxJS** solo para operaciones asíncronas (HTTP, eventos)
