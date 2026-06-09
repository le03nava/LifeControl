# Life Control App Angular — Guía de Desarrollo

## Descripción

Aplicación Angular principal del proyecto LifeControl (v20.3.0), construida con patrones modernos: **Signals, Zoneless, Standalone**, y **Angular Material 3** con soporte de tema oscuro.

---

## Tech Stack

| Componente              | Tecnología                                        |
|------------------------|---------------------------------------------------|
| Framework              | Angular 20.3.0                                    |
| State Management       | Angular Signals                                   |
| Change Detection       | Zoneless (`provideZonelessChangeDetection()`)     |
| Componentes            | 100% Standalone                                   |
| Autenticación          | Keycloak v26 (`keycloak-angular`, `keycloak-js`) |
| UI Library             | Angular Material 20 (M3 theme)                   |
| SSR                   | `@angular/ssr` 20.3.6                            |
| Testing                | Vitest via `@angular/build:unit-test`            |
| Build System           | esbuild (`@angular/build`)                       |
| Layout Utilities       | `@angular/cdk/layout` (BreakpointObserver)        |
| HTTP                   | `withFetch()` + functional interceptors           |

---

## Arquitectura

### Estructura de Directorios

```
src/
├── app/                          # Config raíz de la app
│   ├── app.config.ts             # Providers globales
│   ├── app.config.server.ts      # Providers SSR
│   ├── app.routes.ts             # Rutas raíz con lazy loading
│   ├── app.ts                    # Root component
│   ├── app.scss                  # Estilos del layout raíz
│   ├── models/                   # Modelos globales (AppConfig)
│   ├── services/                 # Servicios globales (ConfigService)
│   ├── core/                     # Repite estructura de src/core/
│   └── models/                   # Modelos globales (AppConfig)
├── core/                         # Configuración central cross-feature
│   ├── config/
│   │   └── keycloak.ts           # Re-export de provideKeycloak
│   ├── guards/
│   │   └── auth-keycloak-guard.ts
│   ├── interceptors/
│   │   └── bearer-token.interceptor.ts
│   └── layout/
│       ├── header/               # Header responsivo con navegación
│       ├── footer/
│       └── index.ts              # Barrel export
├── features/                     # Feature modules
│   ├── auth/
│   │   └── login.ts
│   ├── companies/                # Feature "Companies" (con sub-features)
│   │   ├── companies.routes.ts   # Rutas anidadas con guard de roles
│   │   ├── index.ts
│   │   ├── companies/            # Sub-feature: empresas
│   │   │   ├── components/       # Componentes de UI locales
│   │   │   ├── data/             # Servicios + specs
│   │   │   ├── index.ts
│   │   │   ├── models/           # Modelos de datos
│   │   │   ├── pages/            # Páginas (company-list, company-edit, etc.)
│   │   │   └── ui/               # UI específica (dialogs, etc.)
│   │   ├── countries/            # Sub-feature: países
│   │   │   ├── components/
│   │   │   ├── data/
│   │   │   ├── index.ts
│   │   │   ├── models/
│   │   │   └── pages/
│   │   └── regions/              # Sub-feature: regiones
│   │       ├── components/
│   │       ├── data/
│   │       ├── index.ts
│   │       ├── models/
│   │       └── pages/
│   ├── countries/                # Feature independiente: catálogo de países
│   │   ├── data/
│   │   └── index.ts
│   ├── home/
│   │   ├── home.ts
│   │   ├── home.html
│   │   └── home.scss
│   └── users-admin/              # Feature: administración de usuarios
│       ├── models/
│       ├── pages/
│       ├── services/
│       ├── index.ts
│       └── users-admin.routes.ts
├── shared/                       # Código compartido cross-feature
│   ├── data/                     # Servicios globales
│   │   ├── auth.ts
│   │   ├── company-context.service.ts
│   │   ├── error-interceptor.ts
│   │   ├── loading-interceptor.ts
│   │   ├── loading.ts
│   │   ├── notification.ts
│   │   └── index.ts
│   ├── models/
│   │   ├── api-error.model.ts
│   │   └── index.ts
│   ├── styles/                   # SCSS partials compartidos
│   │   ├── _variables.scss       # Breakpoints ($bp-sm, $bp-md, $bp-lg)
│   │   └── _form-layout.scss     # Layout de formularios CRUD
│   └── ui/                       # Atomic Design
│       ├── button/
│       ├── company-selector/
│       ├── field/
│       ├── form-input/
│       ├── hyperlink/
│       ├── input/
│       ├── modal.ts
│       ├── loading-indicator.ts
│       ├── not-found.ts
│       ├── notification-toast/
│       ├── page-header/
│       ├── spinner.ts
│       ├── unauthorized.ts
│       └── index.ts              # Barrel export
├── styles.scss                   # Estilos globales (M3 theme, dark mode)
├── main.ts                       # Entry point browser
├── main.server.ts                # Entry point SSR
└── test-setup.ts                 # Configuración de tests (Vitest)
```

### Path Aliases

```json
{
  "paths": {
    "@app/*":     ["src/app/*"],
    "@core/*":    ["src/core/*"],
    "@features/*":["src/features/*"],
    "@shared/*":  ["src/shared/*"]
  }
}
```

---

## Patrones de Código

### Principios Generales

| ✅ Hacer                                                           | ❌ No hacer                          |
|-------------------------------------------------------------------|--------------------------------------|
| Componentes standalone (`standalone: true`, sin NgModules)         | Usar `NgModule`                      |
| Signals para estado síncrono                                       | NgRx o RxJS Subjects para estado local |
| Functional guards (`CanActivateFn`)                                | Class-based guards                    |
| Functional interceptors (`HttpInterceptorFn`)                      | Class-based interceptors              |
| `ChangeDetectionStrategy.OnPush` en todos los componentes          | `Default` change detection            |
| ReactiveForms con `NonNullableFormBuilder` y signals de errores   | Template-driven forms complejos       |
| RxJS para operaciones asíncronas (HTTP, eventos), signals para UI | Mezclar Signals y RxJS sin criterio   |
| `toSignal()` para convertir observables a signals                  | Subscribe manual en el template        |
| `effect()` con cleanup (`onCleanup`) para side effects             | Subscription manual sin DestroyRef     |
| Control flow (`@if`, `@for`, `@let`)                               | `*ngIf`, `*ngFor` estructurales       |
| SCSS `@use` en vez de `@import`                                    | `@import` (deprecated en Sass moderno) |

### Service Pattern (Observable + Signals)

Los servicios exponen **Observables** para llamadas HTTP pero mantienen **signals** para estado síncrono (loading, error, listas cacheadas). Usan `ConfigService` para URLs dinámicas (`window.env`).

```typescript
@Injectable({ providedIn: 'root' })
export class CompanyRegionService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  // Signals privados
  private _regions = signal<CompanyRegion[]>([]);
  private _loading = signal(false);
  private _error = signal<string | null>(null);

  // Señales públicas de solo lectura
  readonly regions = this._regions.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private regionsUrl(companyId: string, countryId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/countries/${countryId}/regions`;
  }

  // GET: retorna Observable + actualiza signal via tap()
  getRegions(companyId: string, countryId: string): Observable<CompanyRegion[]> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.get<CompanyRegion[]>(this.regionsUrl(companyId, countryId)).pipe(
      tap(regions => this._regions.set(regions)),
      catchError(err => {
        this._error.set('Error al cargar las regiones');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  // Mutaciones: actualizan la señal local vía tap() para reactividad inmediata
  addRegion(companyId: string, countryId: string, request: CompanyRegionRequest): Observable<CompanyRegion> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.post<CompanyRegion>(this.regionsUrl(companyId, countryId), request).pipe(
      tap(region => {
        this._regions.update(current => [...current, region]);
      }),
      catchError(err => {
        this._error.set(err.status === 409 ? 'Ya existe una región con ese código' : 'Error al crear la región');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  // DELETE / PUT: actualizan in situ
  removeRegion(companyId: string, countryId: string, regionId: string): Observable<void> {
    this._loading.set(true);
    return this.http.delete<void>(`${this.regionsUrl(companyId, countryId)}/${regionId}`).pipe(
      tap(() => this._regions.update(current => current.filter(r => r.id !== regionId))),
      catchError(err => {
        this._error.set('Error al eliminar la región');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  clearError(): void { this._error.set(null); }
}
```

**Variante Observable-only** (para servicios de consulta sin estado local):

```typescript
// CompanyService: retorna Observable, el componente maneja estado via rxResource
export class CompanyService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  get apiUrl(): string {
    return `${this.configService.apiUrl}/companies`;
  }

  getCompanies(page = 0, size = 12, search?: string): Observable<Page<Company>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (search) params = params.set('search', search);
    return this.http.get<Page<Company>>(this.apiUrl, { params });
  }
}
```

### Page Pattern (rxResource)

Las páginas de listado usan `rxResource` para manejar el ciclo de vida HTTP + signals automáticamente:

```typescript
@Component({
  standalone: true,
  imports: [PageHeader, CompaniesCard, MatIconModule, MatPaginatorModule, /* ... */],
  templateUrl: './company-list.html',
  styleUrl: './company-list.scss',
})
export class CompanyList {
  private companyService = inject(CompanyService);
  private router = inject(Router);
  private dialog = inject(MatDialog);
  private destroyRef = inject(DestroyRef);

  // Paginación como signals
  readonly pageSize = signal(12);
  readonly pageIndex = signal(0);

  // Search con debounce
  readonly searchQuery = signal('');
  private readonly _debouncedSearch = signal('');

  // rxResource: llama al backend automáticamente cuando params cambian
  readonly companiesResource = rxResource({
    params: () => ({
      page: this.pageIndex(),
      size: this.pageSize(),
      search: this._debouncedSearch(),
    }),
    stream: ({ params }) =>
      this.companyService.getCompanies(params.page, params.size, params.search || undefined),
  });

  // Computed helpers
  readonly companies = this.companiesResource.value;
  readonly loading = this.companiesResource.isLoading;
  readonly error = this.companiesResource.error;

  constructor() {
    // Debounce effect: searchQuery → 300ms → _debouncedSearch
    effect((onCleanup) => {
      const query = this.searchQuery();
      const timer = setTimeout(() => this._debouncedSearch.set(query), 300);
      onCleanup(() => clearTimeout(timer));
    });

    // Reset a página 0 cuando cambia la búsqueda
    effect(() => {
      this._debouncedSearch();
      if (this.pageIndex() !== 0) this.pageIndex.set(0);
    });
  }

  confirmDelete(companyInfo: { id: string; name: string }): void {
    const dialogRef = this.dialog.open(DeleteCompanyDialogComponent, {
      data: { companyName: companyInfo.name },
    });
    dialogRef.afterClosed().pipe(takeUntilDestroyed(this.destroyRef)).subscribe(result => {
      if (result) {
        this.companyService.deleteCompany(companyInfo.id).pipe(
          takeUntilDestroyed(this.destroyRef),
        ).subscribe({ next: () => this.companiesResource.reload() });
      }
    });
  }
}
```

### Page Edit Pattern (Formularios)

Para create/edit, se usa `NonNullableFormBuilder` + `signal<FormGroup>` + `serverErrors` signal:

```typescript
@Component({
  standalone: true,
  imports: [ReactiveFormsModule, CompaniesForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanyEdit implements OnInit {
  private fb = inject(NonNullableFormBuilder);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private companyService = inject(CompanyService);

  // El ID se lee del snapshot (no de un observable)
  companyId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  companyForm = signal<FormGroup<CompanyControl>>(this.createForm());
  isEditMode = signal(false);
  serverErrors = signal<Record<string, string>>({});
  generalError = signal<string | null>(null);

  ngOnInit(): void {
    const id = this.companyId();
    if (id) {
      this.isEditMode.set(true);
      this.loadCompany(id);
    }
  }

  onSaveCompany(companyData: Company): void {
    if (!companyData.id) {
      this.companyService.createCompany(companyData).subscribe({
        next: (created) => this.router.navigate(['/companies/edit', created.id]),
        error: (err: HttpErrorResponse) => this.handleServerError(err),
      });
    } else {
      this.companyService.updateCompany(companyData.id, companyData).subscribe({
        next: () => this.router.navigate(['/companies']),
        error: (err: HttpErrorResponse) => this.handleServerError(err),
      });
    }
  }

  private handleServerError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
    } else if (apiError?.message) {
      this.serverErrors.set({});
      this.generalError.set(apiError.message);
    } else {
      this.serverErrors.set({});
      this.generalError.set('Error inesperado. Intente de nuevo más tarde.');
    }
  }
}
```

### Component Pattern (Signal Inputs)

```typescript
@Component({
  standalone: true,
  imports: [CommonModule, MatTableModule],
  templateUrl: './companies-table.html',
  styleUrl: './companies-table.scss',
})
export class CompaniesTable {
  readonly companies = input.required<Company[]>();
  readonly loading = input<boolean>(false);

  readonly editCompany = output<Company>();
  readonly deleteCompany = output<Company>();

  readonly displayedColumns = computed(() =>
    this.companies().length > 0 ? ['name', 'rfc', 'email', 'actions'] : []
  );
}
```

### Route Pattern (Lazy Loading + Guards Funcionales)

```typescript
// Rutas raíz (app.routes.ts)
export const routes: Routes = [
  { path: '', component: Home, title: 'Home' },
  { path: 'login', loadComponent: () => import('@features/auth/login').then(m => m.Login) },
  {
    path: 'companies',
    loadChildren: () => import('@features/companies/companies.routes').then(m => m.companyRoutes),
  },
  {
    path: 'users-admin',
    loadChildren: () => import('@features/users-admin/users-admin.routes').then(m => m.usersAdminRoutes),
    canActivate: [keycloakRoleGuard],
    data: { role: 'admin' },
  },
  { path: 'unauthorized', loadComponent: () => import('@shared/ui/unauthorized').then(m => m.Unauthorized) },
  { path: '**', loadComponent: () => import('@shared/ui/not-found').then(m => m.NotFound) },
];
```

```typescript
// Rutas anidadas con client roles (companies.routes.ts)
const BASE_ROLES = ['lc-admin', 'lc-company', 'lc-company-country'];
const COMPANY_CRUD_ROLES = ['lc-admin', 'lc-company'];
const REGION_ROLES = [...BASE_ROLES, 'lc-company-region'];
const ZONE_ROLES = [...REGION_ROLES, 'lc-company-zone'];
const STORE_ROLES = [...ZONE_ROLES, 'lc-company-store'];
const CLIENT_ID = 'life-control-client';

export const companyRoutes: Routes = [
  {
    path: '',
    canActivate: [keycloakRoleGuard],
    data: { roles: STORE_ROLES, clientId: CLIENT_ID },
    children: [
      { path: '', loadComponent: () => import('./companies/pages/companies-admin/...') },
      { path: 'list', canActivate: [keycloakRoleGuard], data: { roles: COMPANY_CRUD_ROLES, clientId: CLIENT_ID }, loadComponent: () => import('./companies/pages/company-list/...') },
      { path: 'create', canActivate: [keycloakRoleGuard], data: { roles: COMPANY_CRUD_ROLES, clientId: CLIENT_ID }, loadComponent: () => import('./companies/pages/company-edit/...') },
      { path: 'edit/:id', canActivate: [keycloakRoleGuard], data: { roles: COMPANY_CRUD_ROLES, clientId: CLIENT_ID }, loadComponent: () => import('./companies/pages/company-edit/...') },
      { path: 'countries', canActivate: [keycloakRoleGuard], data: { roles: BASE_ROLES, clientId: CLIENT_ID }, loadComponent: () => import('./countries/pages/countries-page/...') },
      { path: 'regions', canActivate: [keycloakRoleGuard], data: { roles: REGION_ROLES, clientId: CLIENT_ID }, loadComponent: () => import('./regions/pages/regions-page/...') },
      { path: 'zones', canActivate: [keycloakRoleGuard], data: { roles: ZONE_ROLES, clientId: CLIENT_ID }, loadComponent: () => import('./zones/pages/zones-page/...') },
      { path: 'stores', canActivate: [keycloakRoleGuard], data: { roles: STORE_ROLES, clientId: CLIENT_ID }, loadComponent: () => import('./stores/pages/stores-page/...') },
    ],
  },
];
```

### Guard Pattern (Functional)

```typescript
// ✅ CORRECTO — Dual-mode guard: client roles (con clientId) o realm roles (sin clientId)
export const keycloakRoleGuard: CanActivateFn = async (route, state) => {
  const keycloak = inject(Keycloak);
  const router = inject(Router);

  if (!keycloak.authenticated) {
    await keycloak.login({ redirectUri: window.location.origin + state.url });
    return false;
  }

  const requiredRoles = normalizeRequiredRoles(route.data);
  if (requiredRoles.length === 0) return true;

  const token = keycloak.tokenParsed;
  const clientId = route.data['clientId'] as string | undefined;

  // clientId presente → client roles; ausente → realm roles (legacy)
  const availableRoles: string[] = clientId
    ? token?.resource_access?.[clientId]?.roles ?? []
    : token?.realm_access?.roles ?? [];

  const hasRole = requiredRoles.some((role) => availableRoles.includes(role));
  if (!hasRole) router.navigate(['/unauthorized']);
  return hasRole;
};

// ❌ INCORRECTO - Class-based guard
@Injectable()
export class AuthGuard implements CanActivate { ... }
```

### Interceptor Pattern (Functional)

```typescript
// ✅ CORRECTO - Functional interceptor con try/catch para Keycloak opcional
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const notificationService = inject(NotificationService);

  // Keycloak puede no estar disponible durante la inicialización
  let keycloak: Keycloak | undefined;
  try { keycloak = inject(Keycloak); } catch { /* Keycloak no disponible aún */ }

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      let errorMessage = 'An unexpected error occurred';
      switch (error.status) {
        case 401: errorMessage = 'You are not authorized. Please log in.'; break;
        case 403: errorMessage = 'You do not have permission.'; break;
        case 404: errorMessage = 'Resource not found.'; break;
        case 500: errorMessage = 'Server error. Please try again later.'; break;
        default: if (error.error?.message) errorMessage = error.error.message;
      }
      notificationService.showError(errorMessage);
      return throwError(() => error);
    }),
  );
};

// ❌ INCORRECTO - Class-based interceptor
@Injectable()
export class TokenInterceptor implements HttpInterceptor { ... }
```

### Navegación con Contexto (queryParams + history.state)

Para preservar contexto entre páginas (ej: empresa/país seleccionado en regiones):

```typescript
// Al navegar desde RegionsPage → RegionsEdit:
onEditRegion(region: CompanyRegion): void {
  this.router.navigate(['/companies/regions/edit', region.id], {
    state: { region },  // history.state en el destino
  });
}

onCreateRegion(): void {
  const cc = this.selectedCountry();
  this.router.navigate(['/companies/regions/create'], {
    queryParams: { companyId: cc.companyId, countryId: cc.id },
  });
}

// En RegionsEdit, leer el state:
ngOnInit(): void {
  // Leer del history.state (NO de router.getCurrentNavigation() que devuelve null post-navegación)
  const regionFromState = (globalThis.history?.state as { region?: CompanyRegion })?.region;
  if (regionFromState) this.regionToEdit.set(regionFromState);

  // Create mode: query params para pre-selección
  const companyId = this.route.snapshot.queryParamMap.get('companyId');
  const countryId = this.route.snapshot.queryParamMap.get('countryId');
  if (companyId) this.initialCompanyId.set(companyId);
}

// Al volver a RegionsPage, preservar selección:
onCancelForm(): void {
  const qp: Record<string, string> = {};
  const region = this.regionToEdit();
  if (region) {
    qp['companyId'] = region.companyId;
    qp['countryId'] = region.companyCountryId;
  } else {
    if (this.initialCompanyId()) qp['companyId'] = this.initialCompanyId()!;
    if (this.initialCountryId()) qp['countryId'] = this.initialCountryId()!;
  }
  this.router.navigate(['/companies/regions'], { queryParams: qp });
}
```

### toSignal Pattern

Para convertir Observables a signals cuando el componente necesita valores reactivos:

```typescript
export class RegionsPage implements OnInit {
  private companyService = inject(CompanyService);

  // toSignal: Observable → Signal con valor inicial
  companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map(page => page.content)),
    { initialValue: [] },
  );

  // También para signals del servicio directamente:
  companyCountries = this.companyCountryService.assignedCountries;
}
```

---

## Configuración

### App Config (app.config.ts)

```typescript
export const appConfig: ApplicationConfig = {
  providers: [
    // 0. Animaciones (Material 3 async)
    provideAnimationsAsync(),

    // 1. HTTP Client con fetch API + interceptores
    provideHttpClient(
      withFetch(),
      withInterceptors([errorInterceptor, bearerTokenInterceptor])
    ),

    // 2. Zoneless change detection
    provideZonelessChangeDetection(),

    // 3. Router con component input binding
    provideRouter(routes, withComponentInputBinding()),

    // 4. App initializer (carga window.env antes de cualquier cosa)
    provideAppInitializer(initializeApp),
    ConfigService,

    // 5. Keycloak (después de inicialización)
    provideKeycloak({
      config: {
        url: (window as any).env.KEYCLOAK_URL || 'http://localhost:8181',
        realm: (window as any).env.KEYCLOAK_REALM || 'life-control-realm',
        clientId: (window as any).env.KEYCLOAK_CLIENT_ID || 'life-control-client',
      },
      initOptions: {
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: window.location.origin + '/assets/silent-check-sso.html',
      },
    }),
  ],
};
```

### ConfigService (Runtime Environment)

```typescript
@Injectable({ providedIn: 'root' })
export class ConfigService {
  private config = signal<AppConfig>(DEFAULT_CONFIG);
  readonly config$ = this.config.asReadonly();

  // Se carga via provideAppInitializer antes de que la app renderice
  async loadConfig(): Promise<void> {
    const envConfig = (window as any).env;
    if (envConfig) {
      this.config.set({
        keycloak: {
          url: envConfig.KEYCLOAK_URL || DEFAULT_CONFIG.keycloak.url,
          realm: envConfig.KEYCLOAK_REALM || DEFAULT_CONFIG.keycloak.realm,
          clientId: envConfig.KEYCLOAK_CLIENT_ID || DEFAULT_CONFIG.keycloak.clientId,
        },
        apiGateway: {
          url: envConfig.API_GATEWAY_URL || DEFAULT_CONFIG.apiGateway.url,
          basePath: envConfig.API_BASE_PATH || DEFAULT_CONFIG.apiGateway.basePath,
        },
      });
    }
  }

  get apiUrl(): string {
    return `${this.config().apiGateway.url}${this.config().apiGateway.basePath}`;
  }
}
```

**⚠️ Regla importante:** Todos los servicios deben usar `ConfigService.apiUrl` para las URLs de API. **No hardcodear URLs** (`http://localhost:9000/api/...`).

### Tema (styles.scss)

- **Material 3** con `mat.define-theme()` (primary: azure, tertiary: cyan)
- **Dark mode** vía `@media (prefers-color-scheme: dark)`
- **CSS Custom Properties**: --color-primary, --color-gray-*, --space-*, --radius-*, --shadow-*
- **Design Tokens**: --mat-sys-* (surface, primary, error, etc.)
- **Componentes Material incluidos**: button, card, checkbox, chips, dialog, form-field, icon, input, option, paginator, select, slide-toggle, table, tabs, tooltip

### SCSS Partials Compartidos

**`_variables.scss`** — Único lugar para SCSS breakpoints:
```scss
$bp-sm: 576px;
$bp-md: 1024px;
$bp-lg: 1440px;
```

**`_form-layout.scss`** — Layout responsivo para formularios CRUD:
```scss
@use 'variables' as *;

.form-card {
  max-width: 100%; margin: 0 auto; padding: var(--space-lg);
  background-color: var(--color-gray-50); border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);

  @media (min-width: $bp-sm) { max-width: 720px; }
  @media (min-width: $bp-md) { max-width: 960px; }

  form {
    display: grid; grid-template-columns: 1fr; gap: var(--space-md);
    @media (min-width: $bp-sm) { grid-template-columns: repeat(2, 1fr); }
    @media (min-width: $bp-md) { grid-template-columns: repeat(3, 1fr); }
  }
}

.form-actions {
  grid-column: 1 / -1; display: flex; gap: var(--space-md);
  justify-content: flex-end; margin-top: var(--space-lg);
  border-top: 1px solid var(--color-gray-200);
}
```

**Uso:** `@use '../../shared/styles/variables' as *;`

---

## Patrones de CSS

| Uso                                             | Ejemplo                                          |
|-------------------------------------------------|--------------------------------------------------|
| `@use 'variables' as *` para breakpoints        | `@media (min-width: $bp-sm)`                     |
| CSS custom properties para spacing/colores      | `var(--space-md)`, `var(--color-primary)`        |
| Design tokens Material 3                        | `var(--mat-sys-surface)`, `var(--mat-sys-error)` |
| `:host { display: block; width: 100%; }`        | Siempre en page components                       |
| Skeleton loading con animación                  | `animation: skeleton 1.5s infinite`              |
| Grid responsivo: `1fr → 2fr → auto-fill`        | `grid-template-columns: repeat(auto-fill, minmax(320px, 1fr))` |
| BEM-ish naming: `.block__element--modifier`     | `.form-card`, `.filters-section`, `.card-wrapper` |
| Mobile-first media queries                      | `min-width` en vez de `max-width`                |
| `color-mix()` para variantes translúcidas       | `color-mix(in srgb, var(--mat-sys-error) 8%, transparent)` |
| Animaciones con `--i` stagger                   | `animation-delay: calc(var(--i, 0) * 50ms)`      |

### Estados de UI Consistentes

Todas las páginas de listado implementan estos estados con clases consistentes:

```html
<!-- Loading: skeletons -->
@if (loading()) {
  <div class="loading-skeleton">
    @for (_ of [1, 2, 3, 4]; track $index) {
      <div class="skeleton-card">...</div>
    }
  </div>
}

<!-- Error state -->
@if (error(); as err) {
  <div class="error-state">
    <mat-icon fontIcon="error" />
    <p>{{ err }}</p>
  </div>
}

<!-- Empty state -->
@if (page && page.content.length > 0) {
  <!-- grid de cards -->
} @else {
  <div class="empty-state">
    <div class="empty-illustration"><!-- SVG --></div>
    <h3 class="empty-title">No hay elementos</h3>
    <p class="empty-subtitle">Clic en "Agregar" para crear el primero</p>
  </div>
}
```

---

## Testing

### Configuración

- **Builder**: `@angular/build:unit-test` (en `angular.json`)
- **Runner**: Vitest
- **Spec files**: Co-locados (`*.spec.ts`)
- **Setup**: `src/test-setup.ts` (zona.js + matchMedia mock)
- **Coverage**: `@vitest/coverage-v8`

```bash
npm test              # Ejecutar tests
npm run test:watch    # Modo watch
npm run test:coverage # Con coverage
```

### Patrones de Test

```typescript
// 1. Mocks con Partial<Service> + vi.fn()
let companyServiceMock: Partial<Record<keyof CompanyService, unknown>>;
companyServiceMock = {
  createCompany: vi.fn(),
  updateCompany: vi.fn(),
};

// 2. SpyOn para navegación
routerMock = { navigate: vi.fn() };

// 3. HttpErrorResponse factory function
function createApiError(overrides = {}): HttpErrorResponse {
  return new HttpErrorResponse({
    error: { status: 400, message: 'Error', errors: undefined, ...overrides },
    status: 400, statusText: 'Bad Request',
  });
}

// 4. ActivatedRoute mock con snapshot
{
  provide: ActivatedRoute,
  useValue: { snapshot: { paramMap: { get: () => null }, queryParamMap: { get: () => null } } },
}

// 5. Async testing con signals: evaluar después de cambios
it('should set serverErrors signal', () => {
  const httpError = createApiError({ errors: { rfc: 'RFC inválido' } });
  companyServiceMock.createCompany = vi.fn().mockReturnValue(throwError(() => httpError));
  component.onSaveCompany(createCompanyData());
  expect(component.serverErrors()).toEqual({ rfc: 'RFC inválido' });
});
```

**Importante:** `@angular/build:unit-test` **ignora** `vitest.config.ts`. Usar `setupFiles` en `angular.json` y `test-setup.ts`.

---

## Roles y Permisos (RBAC)

La aplicación usa dos tipos de roles de Keycloak, con un **guard dual-mode** (`keycloakRoleGuard`) que los resuelve según la presencia de `clientId` en los datos de ruta.

### Jerarquía de Roles de Cliente (`life-control-client`)

Son los roles que controlan el acceso a la feature **Companies**. Son jerárquicos y acumulativos hacia abajo (los niveles superiores heredan acceso a todo lo inferior, pero no al revés).

```
lc-admin
 └─ lc-company
      └─ lc-company-country
           └─ lc-company-region
                └─ lc-company-zone
                     └─ lc-company-store
```

### Visibility Rules

Tres capas de control independientes:

| Capa | Archivo | Lógica |
|------|---------|--------|
| **Menú lateral** | `header.ts` | `isCompanyRole` signal → `lc-admin \|\| lc-company \|\| lc-company-country \|\| lc-company-region \|\| lc-company-zone \|\| lc-company-store` |
| **Admin menus** | `header.ts` | `isAdmin` signal → `lc-admin` solamente (Products, Compras, Users Admin) |
| **Route guard** | `companies.routes.ts` | Roles por ruta con `BASE_ROLES`, `COMPANY_CRUD_ROLES`, `REGION_ROLES`, `ZONE_ROLES`, `STORE_ROLES` |
| **Dashboard cards** | `companies-admin.component.ts` | `requiredRoles` por cada `STATIC_CARD`; las cards sin acceso se ocultan |

### Matriz de Permisos

| Rol | Menú Companies | Menús Admin | Companies CRUD | Countries | Regions | Zones | Stores |
|-----|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `lc-admin` | ✅ | ✅ (Products + Compras + Users Admin) | ✅ | ✅ | ✅ | ✅ | ✅ |
| `lc-company` | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `lc-company-country` | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ |
| `lc-company-region` | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ |
| `lc-company-zone` | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| `lc-company-store` | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |

### Realm Roles (legacy)

Usados por features fuera de Companies (Products, Purchases, Users Admin). No usan `clientId` en los datos de ruta, por lo que el guard resuelve contra `realm_access.roles`.

| Rol | Acceso |
|-----|--------|
| `life-control-admin` | Products, Purchases |
| `life-control-country` | Products |
| `admin` | Users Admin |

> **Importante:** `lc-admin` es un **rol de cliente** (no realm). `life-control-admin` es un **realm role**. Son distintos.

### Guard Dual-Mode

```typescript
// Con clientId → resuelve de resource_access[clientId].roles (client roles)
data: { roles: ['lc-admin', 'lc-company'], clientId: 'life-control-client' }

// Sin clientId → resuelve de realm_access.roles (realm roles, legacy)
data: { role: 'admin' }
data: { roles: ['life-control-admin'] }
```

### Header Menu Control

```typescript
// header.ts — role-to-signal mapping
const clientRoles = token?.resource_access?.['life-control-client']?.roles ?? [];

this.isAdmin.set(clientRoles.includes('lc-admin'));
this.isCompanyRole.set(
  clientRoles.includes('lc-admin') ||
  clientRoles.includes('lc-company') ||
  clientRoles.includes('lc-company-country') ||
  clientRoles.includes('lc-company-region') ||
  clientRoles.includes('lc-company-zone') ||
  clientRoles.includes('lc-company-store'),
);

// items() computed:
//   base: [Home]
//   + isCompanyRole → [Companies]
//   + isAdmin → [Products, Purchases, Users Admin]
```

### Companies Route Guards

```typescript
// companies.routes.ts — constantes de roles
const BASE_ROLES = ['lc-admin', 'lc-company', 'lc-company-country'];
const COMPANY_CRUD_ROLES = ['lc-admin', 'lc-company'];
const REGION_ROLES = [...BASE_ROLES, 'lc-company-region'];
const ZONE_ROLES = [...REGION_ROLES, 'lc-company-zone'];
const STORE_ROLES = [...ZONE_ROLES, 'lc-company-store'];

// Aplicación:
//   /companies (parent)     → STORE_ROLES
//   /companies/list         → COMPANY_CRUD_ROLES
//   /companies/create       → COMPANY_CRUD_ROLES
//   /companies/edit/:id     → COMPANY_CRUD_ROLES
//   /companies/countries    → BASE_ROLES
//   /companies/regions      → REGION_ROLES
//   /companies/zones        → ZONE_ROLES
//   /companies/stores       → STORE_ROLES
```

### Dashboard Card Visibility

```typescript
// companies-admin.component.ts — STATIC_CARDS requiredRoles
Companies: ['lc-admin', 'lc-company']                           // lc-company-country NO incluido
Countries: ['lc-admin', 'lc-company', 'lc-company-country']
Regions:  [...Countries, 'lc-company-region']
Zones:    [...Regions, 'lc-company-zone']
Stores:   [...Zones, 'lc-company-store']

// cards computed: filtra .filter(card => !card.disabled)
// → cards sin acceso NO aparecen (deshabilitadas = ocultas)
```

### Keycloak Config

| Aspecto              | Detalle                                              |
|----------------------|------------------------------------------------------|
| Paquete              | `keycloak-angular` + `keycloak-js`                   |
| Provider             | `provideKeycloak()` desde `@core/config/keycloak`    |
| Token en requests    | `bearer-token.interceptor.ts` (functional)           |
| Eventos              | `KEYCLOAK_EVENT_SIGNAL` (signal reactiva)            |
| Inject               | `inject(Keycloak)` directamente                      |
| Silent SSO           | `silent-check-sso.html` actualizado para keycloak-js v26 |
| Login manual         | `keycloak.login()` — no auto-redirect en 401         |

---

## Convenciones y Reglas

### Estructura de Features

Cada feature (o sub-feature dentro de `companies/`) sigue esta estructura:

```
feature-name/
├── components/     # Componentes de UI específicos del feature
├── data/           # Servicios + specs
├── models/         # Interfaces + specs de modelos
├── pages/          # Componentes-página (ruteables)
└── index.ts        # Barrel export
```

Los **pages** son componentes ruteables (`loadComponent` en las rutas). Los **components** son UI reutilizable dentro del feature. Los **data** son servicios con lógica de negocio + llamadas HTTP.

### Navegación entre Páginas

| Origen → Destino                        | Mecanismo               |
|-----------------------------------------|-------------------------|
| List → Edit (con datos)                 | `router.navigate([...], { state: { item } })` + `globalThis.history?.state` |
| Page1 → Page2 (create, con selección)   | `queryParams: { companyId, countryId }` |
| Create/Edit → Cancel → List (preservar) | `queryParams` de vuelta al listado |
| `router.getCurrentNavigation()`         | ❌ No usar — devuelve `null` post-navegación |

### Nombres de Archivos

| Tipo                             | Convención                           |
|----------------------------------|--------------------------------------|
| Componente ruteable              | `company-list.ts`, `regions-edit.ts` |
| Componente UI interno            | `companies-card.ts`, `regions-form.ts` |
| Servicio                         | `company.service.ts`                 |
| Modelo                           | `company.models.ts`                  |
| Test                             | `company-list.spec.ts`               |
| Plantilla HTML                   | `company-list.html`                  |
| Estilos SCSS                     | `company-list.scss`                  |
| Barrel                           | `index.ts`                           |

### Cosas que NO hacer

- ❌ **No usar NgModules** — todo standalone
- ❌ **No hardcodear URLs de API** — usar `ConfigService.apiUrl`
- ❌ **No usar `*ngIf` / `*ngFor`** — usar `@if` / `@for`
- ❌ **No usar `router.getCurrentNavigation()`** — usar `globalThis.history?.state` o `ActivatedRoute.snapshot.queryParamMap`
- ❌ **No subscribirse manualmente sin cleanup** — usar `takeUntilDestroyed` o `rxResource`
- ❌ **No usar `provideAnimations()`** — usar `provideAnimationsAsync()`
- ❌ **No poner lógica de negocio en los componentes** — debe estar en servicios (data/)
- ❌ **No usar `@import` en SCSS** — usar `@use`
- ❌ **No mezclar signals y RxJS sin criterio** — signals para UI, RxJS para streams HTTP/eventos

---

## Referencias

- [Angular 20 Documentation](https://angular.dev)
- [Angular Signals Guide](https://angular.dev/guide/signals)
- [Angular rxResource](https://angular.dev/guide/rx-resource)
- [Keycloak Angular](https://www.npmjs.com/package/keycloak-angular)
- [Angular Material](https://material.angular.io)
- [Vitest](https://vitest.dev)
