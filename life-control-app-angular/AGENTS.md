# Life Control App Angular - Developer Guide

## Descripción

Aplicación Angular principal del proyecto LifeControl. Esta es la aplicación Angular más moderna del proyecto (v20.3.0), utilizando patrones de última generación como Signals, Zoneless y componentes standalone.

> **Nota**: El directorio `frontend/` está deprecado. Usar `life-control-app-angular/` para todo desarrollo frontend.

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
| Testing | Karma + Jasmine |
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

- **Test Runner**: Karma (configurado en angular.json)
- **Framework**: Jasmine
- **Spec Files**: Co-locados con componentes (`*.spec.ts`)

### Ejemplo de Test

```typescript
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
  
  it('should load companies', () => {
    const mockCompanies: Company[] = [
      { id: '1', name: 'Test', rfc: 'XAXX010101', email: 'test@test.com' }
    ];
    
    service.getAll();
    
    httpMock.expectOne('http://localhost:9000/api/companies')
      .flush(mockCompanies);
    
    expect(service.companies()).toEqual(mockCompanies);
  });
});
```

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

```bash
# Build de imagen
docker build -t life-control-app-angular:latest .

# Ejecutar contenedor
docker run -p 4200:4200 life-control-app-angular:latest
```

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
