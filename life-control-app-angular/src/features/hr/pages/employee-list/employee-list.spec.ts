/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Signal, WritableSignal, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable, of, throwError } from 'rxjs';
import Keycloak from 'keycloak-js';
import { ConfirmDialog } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { Company, Page } from '@features/companies/companies/models/company.models';
import { EmployeeList } from './employee-list';
import { EmployeeService } from '../../data/employee.service';
import { EmployeeStatusService } from '../../data/employee-status.service';
import { Employee } from '../../models/employee.models';

describe('EmployeeList', () => {
  let fixture: ComponentFixture<EmployeeList>;
  let component: EmployeeList;
  let employeeService: {
    error: Signal<string | null>;
    getEmployees: ReturnType<typeof vi.fn>;
    removeEmployee: ReturnType<typeof vi.fn>;
    enableEmployee: ReturnType<typeof vi.fn>;
  };
  let statusService: { loadEmployeeStatusIds: ReturnType<typeof vi.fn> };
  let notifications: { showSuccess: ReturnType<typeof vi.fn> };
  let dialog: { open: ReturnType<typeof vi.fn> };
  let router: Router;
  let errorSignal: WritableSignal<string | null>;

  const WRITE_ROLES = ['lc-employee'];
  const READ_ONLY_ROLES = ['lc-sales'];
  /** In the HR menu-visibility union but deliberately absent from the employee write set. */
  const OTHER_HR_WRITE_ROLES = ['lc-department'];

  const company: Company = {
    id: 'company-1',
    companyKey: 'ACME',
    companyName: 'Acme Corp',
    tipoPersonaId: 2,
    razonSocial: 'Acme Corp SA',
    rfc: 'ACM200101010',
    email: 'contacto@acme.example',
    phone: '+525512345678',
    enabled: true,
    createdAt: '2026-01-01',
    updatedAt: '2026-01-01',
  };

  const companiesPage: Page<Company> = {
    content: [company],
    totalElements: 1,
    totalPages: 1,
    size: 1000,
    number: 0,
    first: true,
    last: true,
    empty: false,
  };

  const employee = (overrides: Partial<Employee> = {}): Employee => ({
    id: 'emp-1',
    companyId: 'company-1',
    employeeNumber: 'EMP-001',
    firstName: 'Ana',
    paternalLastName: 'Gómez',
    maternalLastName: 'Ruiz',
    email: 'ana.gomez@acme.example',
    phoneNumber: null,
    birthDate: '1990-05-01',
    hireDate: '2024-02-15',
    terminationDate: null,
    addressId: null,
    statusId: 'status-active',
    statusName: 'Active',
    keycloakUserId: null,
    enabled: true,
    version: 0,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  });

  /** The `statusName -> statusId` map `loadEmployeeStatusIds` resolves to. */
  const STATUS_IDS: ReadonlyMap<string, string> = new Map([
    ['Active', 'status-active'],
    ['Inactive', 'status-inactive'],
    ['OnLeave', 'status-onleave'],
    ['Terminated', 'status-terminated'],
  ]);

  /**
   * Emulates the real service's list contract: a successful read clears the
   * load error before emitting.
   */
  function successfulRead(rows: Employee[]): Observable<Employee[]> {
    errorSignal.set(null);
    return of(rows);
  }

  interface SetupOptions {
    roles?: string[];
    queryCompanyId?: string | null;
    rows?: Employee[];
    loadError?: boolean;
    dialogConfirmed?: boolean;
    statusCatalogue?: Observable<ReadonlyMap<string, string>>;
  }

  function setup(options: SetupOptions = {}): void {
    const rows = options.rows ?? [
      employee(),
      employee({
        id: 'emp-2',
        employeeNumber: 'EMP-002',
        firstName: 'Luis',
        paternalLastName: 'Pérez',
        maternalLastName: null,
        email: 'luis.perez@acme.example',
        statusName: 'Terminated',
        statusId: 'status-terminated',
        enabled: false,
      }),
    ];
    errorSignal = signal<string | null>(null);

    employeeService = {
      error: errorSignal.asReadonly(),
      getEmployees: vi.fn(() => {
        if (options.loadError) {
          errorSignal.set('Error al cargar los empleados');
          return throwError(() => new HttpErrorResponse({ status: 500 }));
        }
        return successfulRead(rows);
      }),
      removeEmployee: vi.fn().mockReturnValue(of(undefined)),
      enableEmployee: vi.fn().mockReturnValue(of(employee({ enabled: true }))),
    };

    statusService = {
      loadEmployeeStatusIds: vi.fn().mockReturnValue(options.statusCatalogue ?? of(STATUS_IDS)),
    };

    notifications = { showSuccess: vi.fn() };
    dialog = {
      open: vi.fn().mockReturnValue({ afterClosed: () => of(options.dialogConfirmed ?? false) }),
    };

    const roles = options.roles ?? WRITE_ROLES;

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [EmployeeList, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: EmployeeService, useValue: employeeService },
        { provide: EmployeeStatusService, useValue: statusService },
        {
          provide: CompanyService,
          useValue: { getCompanies: vi.fn().mockReturnValue(of(companiesPage)) },
        },
        { provide: NotificationService, useValue: notifications },
        { provide: MatDialog, useValue: dialog },
        {
          provide: Keycloak,
          useValue: { tokenParsed: { resource_access: { 'life-control-client': { roles } } } },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({}),
              queryParamMap: convertToParamMap(
                options.queryCompanyId ? { companyId: options.queryCompanyId } : {},
              ),
            },
          },
        },
      ],
    });

    fixture = TestBed.createComponent(EmployeeList);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
  }

  async function settle(): Promise<void> {
    for (let i = 0; i < 5; i += 1) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }

  /** Types a term and drives the debounce window to its end with fake timers. */
  async function typeAndDebounce(term: string): Promise<void> {
    vi.useFakeTimers();
    component.onSearchChange(term);
    fixture.detectChanges();
    vi.advanceTimersByTime(300);
    vi.useRealTimers();
    await settle();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function headers(): string[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('th.mat-mdc-header-cell'),
    ).map((th) => th.textContent?.trim() ?? '');
  }

  function actionButton(label: string): HTMLButtonElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(`[aria-label="${label}"]`);
  }

  function retryButton(): HTMLButtonElement | null {
    return (
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Reintentar',
      ) ?? null
    );
  }

  /** The rendered include-disabled toggle's switch button. */
  function includeDisabledToggle(): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
      'mat-slide-toggle button.mdc-switch',
    );
  }

  it('should create', () => {
    setup();
    expect(component).toBeTruthy();
  });

  it('should issue no list request until a company is selected', async () => {
    setup({ queryCompanyId: null });
    await settle();

    expect(employeeService.getEmployees).not.toHaveBeenCalled();
    expect(text()).toContain('Seleccioná una empresa');
  });

  it('should load and render the company employees with every required column header', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    expect(employeeService.getEmployees).toHaveBeenCalledWith(
      'company-1',
      undefined,
      undefined,
      false,
    );
    expect(text()).toContain('Ana Gómez Ruiz');
    expect(text()).toContain('ana.gomez@acme.example');
    expect(headers()).toEqual([
      'Número',
      'Nombre completo',
      'Correo',
      'Estado',
      'Fecha de ingreso',
      'Acciones',
    ]);
  });

  it('should render the full name without a trailing gap when there is no maternal last name', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    expect(text()).toContain('Luis Pérez');
    expect(text()).not.toContain('Luis Pérez ');
  });

  it('should render the Spanish status label, never the raw server name', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    expect(text()).toContain('Activo');
    expect(text()).toContain('Dado de baja');
    expect(text()).not.toContain('Terminated');
  });

  it('should re-issue the request with includeDisabled true when the toggle turns on', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.onIncludeDisabledChange(true);
    await settle();

    expect(includeDisabledToggle()?.getAttribute('aria-checked')).toBe('true');
    expect(employeeService.getEmployees).toHaveBeenLastCalledWith(
      'company-1',
      undefined,
      undefined,
      true,
    );
  });

  it('should send the selected status id as the server-side statusId filter', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.onStatusChange('status-terminated');
    await settle();

    expect(employeeService.getEmployees).toHaveBeenLastCalledWith(
      'company-1',
      undefined,
      'status-terminated',
      false,
    );
  });

  it('should combine the debounced search, the selected status and includeDisabled in one read', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();
    employeeService.getEmployees.mockClear();

    await typeAndDebounce('ana');
    component.onStatusChange('status-active');
    component.onIncludeDisabledChange(true);
    await settle();

    expect(employeeService.getEmployees).toHaveBeenLastCalledWith(
      'company-1',
      'ana',
      'status-active',
      true,
    );
  });

  it('should debounce the search so typing does not fire one request per keystroke', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();
    employeeService.getEmployees.mockClear();

    vi.useFakeTimers();
    try {
      component.onSearchChange('a');
      fixture.detectChanges();
      component.onSearchChange('an');
      fixture.detectChanges();
      component.onSearchChange('ana');
      fixture.detectChanges();

      // Nothing fired while the debounce window is still open.
      expect(employeeService.getEmployees).not.toHaveBeenCalled();

      vi.advanceTimersByTime(299);
      fixture.detectChanges();
      expect(employeeService.getEmployees).not.toHaveBeenCalled();

      vi.advanceTimersByTime(1);
    } finally {
      vi.useRealTimers();
    }
    await settle();

    expect(employeeService.getEmployees).toHaveBeenCalledTimes(1);
    expect(employeeService.getEmployees).toHaveBeenCalledWith('company-1', 'ana', undefined, false);
  });

  it('should drop the search term from the read when it is cleared', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    await typeAndDebounce('ana');
    expect(employeeService.getEmployees).toHaveBeenLastCalledWith(
      'company-1',
      'ana',
      undefined,
      false,
    );

    await typeAndDebounce('');
    expect(employeeService.getEmployees).toHaveBeenLastCalledWith(
      'company-1',
      undefined,
      undefined,
      false,
    );
  });

  it('should render the empty state when the company has no employees', async () => {
    setup({ queryCompanyId: 'company-1', rows: [] });
    await settle();

    expect(text()).toContain('No hay empleados registrados');
    expect(text()).toContain('para crear el primero');
    expect(text()).not.toContain('No hay coincidencias');
  });

  it('should render the filtered-empty copy when a status filter matches no rows', async () => {
    setup({ queryCompanyId: 'company-1', rows: [] });
    await settle();

    component.onStatusChange('status-terminated');
    await settle();

    expect(text()).toContain('No hay coincidencias con los filtros aplicados');
    expect(text()).not.toContain('No hay empleados registrados');
    expect(text()).not.toContain('para crear el primero');
  });

  it('should render the filtered-empty copy when the search term matches no rows', async () => {
    setup({ queryCompanyId: 'company-1', rows: [] });
    await settle();

    await typeAndDebounce('zzz');

    expect(text()).toContain('No hay coincidencias con los filtros aplicados');
    expect(text()).not.toContain('No hay empleados registrados');
  });

  it('should render the error state and retry', async () => {
    setup({ queryCompanyId: 'company-1', loadError: true });
    await settle();

    expect(text()).toContain('Error al cargar los empleados');
    expect(text()).toContain('Reintentar');

    employeeService.getEmployees.mockImplementation(() => successfulRead([employee()]));
    component.reloadList();
    await settle();

    expect(text()).toContain('Ana Gómez Ruiz');
  });

  it('should re-issue the list read when the rendered retry button is clicked', async () => {
    setup({ queryCompanyId: 'company-1', loadError: true });
    await settle();

    expect(employeeService.getEmployees).toHaveBeenCalledTimes(1);
    employeeService.getEmployees.mockImplementation(() => successfulRead([employee()]));

    const button = retryButton();
    expect(button).not.toBeNull();
    button?.click();
    await settle();

    expect(employeeService.getEmployees).toHaveBeenCalledTimes(2);
    expect(text()).toContain('Ana Gómez Ruiz');
  });

  it('should not render a paginator: the endpoint answers a plain array (G17)', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    expect((fixture.nativeElement as HTMLElement).querySelector('mat-paginator')).toBeNull();
  });

  it('should not render a position column: EmployeeResponse carries none (G15)', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    expect(headers()).not.toContain('Puesto');
    expect(text()).not.toContain('Puesto');
  });

  describe('company selection', () => {
    it('should seed the selection from the companyId query param', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      expect(component.selectedCompanyId()).toBe('company-1');
      expect(employeeService.getEmployees).toHaveBeenCalledWith(
        'company-1',
        undefined,
        undefined,
        false,
      );
    });

    it('should write the selection back to the URL on change', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      component.onCompanyChange('company-2');
      await settle();

      expect(component.selectedCompanyId()).toBe('company-2');
      expect(router.navigate).toHaveBeenCalledWith(
        [],
        expect.objectContaining({ queryParams: { companyId: 'company-2' } }),
      );
    });

    it('should re-issue the list read for the newly selected company', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      component.onCompanyChange('company-2');
      await settle();

      expect(employeeService.getEmployees).toHaveBeenLastCalledWith(
        'company-2',
        undefined,
        undefined,
        false,
      );
    });
  });

  describe('status catalogue', () => {
    it('should build the status options from the resolver, labelled in Spanish', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      expect(statusService.loadEmployeeStatusIds).toHaveBeenCalledTimes(1);
      expect(component.catalogueResolved()).toBe(true);
      expect(component.statusOptions()).toEqual([
        { id: 'status-active', name: 'Active', label: 'Activo' },
        { id: 'status-inactive', name: 'Inactive', label: 'Inactivo' },
        { id: 'status-onleave', name: 'OnLeave', label: 'Con licencia' },
        { id: 'status-terminated', name: 'Terminated', label: 'Dado de baja' },
      ]);
    });

    it('should fall back to the raw status name when the resolver returns one outside the label map', async () => {
      setup({
        queryCompanyId: 'company-1',
        statusCatalogue: of(new Map([['Sabbatical', 'status-sabbatical']])),
      });
      await settle();

      expect(component.statusOptions()).toEqual([
        { id: 'status-sabbatical', name: 'Sabbatical', label: 'Sabbatical' },
      ]);
    });

    it('should fail closed when the resolver fails: no status option and an explanatory copy', async () => {
      setup({
        queryCompanyId: 'company-1',
        statusCatalogue: throwError(() => new HttpErrorResponse({ status: 500 })),
      });
      await settle();

      expect(component.catalogueFailed()).toBe(true);
      expect(component.statusOptions()).toEqual([]);
      expect(text()).toContain('No se pudo cargar el catálogo de estados');
      // The list read does not depend on the catalogue, so it still ran.
      expect(employeeService.getEmployees).toHaveBeenCalledTimes(1);
      expect(text()).toContain('Ana Gómez Ruiz');
    });
  });

  describe('navigation', () => {
    it('should navigate to create carrying the selected company', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      component.onCreate();

      expect(router.navigate).toHaveBeenCalledWith(['/hr/employees/create'], {
        queryParams: { companyId: 'company-1' },
      });
    });

    it('should navigate to edit carrying the employee company', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      component.onEdit(employee());

      expect(router.navigate).toHaveBeenCalledWith(['/hr/employees/edit', 'emp-1'], {
        queryParams: { companyId: 'company-1' },
      });
    });

    it('should navigate to the detail carrying the employee company', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      component.onView(employee());

      expect(router.navigate).toHaveBeenCalledWith(['/hr/employees', 'emp-1'], {
        queryParams: { companyId: 'company-1' },
      });
    });

    it('should navigate to the detail when the rendered Ver action is clicked', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      const button = actionButton('Ver');
      expect(button).not.toBeNull();
      button?.click();

      expect(router.navigate).toHaveBeenCalledWith(['/hr/employees', 'emp-1'], {
        queryParams: { companyId: 'company-1' },
      });
    });
  });

  describe('write-role gating', () => {
    it('should hide every write control for a read-only role', async () => {
      setup({ roles: READ_ONLY_ROLES, queryCompanyId: 'company-1' });
      await settle();

      expect(component.canWrite).toBe(false);
      expect(text()).not.toContain('Nuevo empleado');
      expect(actionButton('Editar')).toBeNull();
      expect(actionButton('Deshabilitar')).toBeNull();
      expect(actionButton('Habilitar')).toBeNull();
    });

    it('should still render the employee rows and the read filters for a caller without a write role', async () => {
      setup({ roles: READ_ONLY_ROLES, queryCompanyId: 'company-1' });
      await settle();

      expect(component.canWrite).toBe(false);
      expect(text()).toContain('Ana Gómez Ruiz');
      expect(text()).toContain('Mostrar deshabilitados');
      expect(text()).not.toContain('Nuevo empleado');
      expect(actionButton('Editar')).toBeNull();
    });

    it('should keep the additive Ver action for a caller without a write role', async () => {
      setup({ roles: READ_ONLY_ROLES, queryCompanyId: 'company-1' });
      await settle();

      expect(component.canWrite).toBe(false);
      expect(actionButton('Ver')).not.toBeNull();
      expect(actionButton('Editar')).toBeNull();
      expect(actionButton('Deshabilitar')).toBeNull();
    });

    it('should render the write controls for a write role', async () => {
      setup({ roles: WRITE_ROLES, queryCompanyId: 'company-1' });
      await settle();

      expect(component.canWrite).toBe(true);
      expect(text()).toContain('Nuevo empleado');
      expect(actionButton('Editar')).not.toBeNull();
      expect(actionButton('Deshabilitar')).not.toBeNull();
    });

    it('should hide the employee write controls for another HR write role outside the employee set', async () => {
      setup({ roles: OTHER_HR_WRITE_ROLES, queryCompanyId: 'company-1' });
      await settle();

      expect(component.canWrite).toBe(false);
      expect(text()).not.toContain('Nuevo empleado');
      expect(actionButton('Editar')).toBeNull();
      expect(actionButton('Deshabilitar')).toBeNull();
    });

    it('should offer Habilitar instead of Deshabilitar on a disabled row', async () => {
      setup({
        queryCompanyId: 'company-1',
        rows: [employee({ id: 'emp-2', enabled: false, statusName: 'Inactive' })],
      });
      await settle();

      expect(actionButton('Habilitar')).not.toBeNull();
      expect(actionButton('Deshabilitar')).toBeNull();
    });
  });

  describe('disable / re-enable', () => {
    it('should confirm before disabling and not call the service when cancelled', async () => {
      setup({ queryCompanyId: 'company-1', dialogConfirmed: false });
      await settle();

      component.onDisable(employee());
      await settle();

      expect(dialog.open).toHaveBeenCalledWith(ConfirmDialog, {
        data: {
          title: 'Deshabilitar empleado',
          message:
            '¿Confirmás que querés deshabilitar a "Ana Gómez Ruiz"? Deja de aparecer en el registro, pero se conserva y podés habilitarlo más adelante.',
          confirmLabel: 'Deshabilitar',
          destructive: true,
        },
      });
      // The copy names the disable (T3); it never conflates it with the Terminated status.
      const [, config] = dialog.open.mock.calls[0] as [
        unknown,
        { data: { title: string; message: string; confirmLabel: string; destructive: boolean } },
      ];
      const copy = [config.data.title, config.data.message, config.data.confirmLabel].join(' ');
      expect(copy).toMatch(/deshabilitar/i);
      expect(copy).not.toMatch(/baja|Terminated/);
      expect(employeeService.removeEmployee).not.toHaveBeenCalled();
    });

    it('should disable, notify and reload when confirmed', async () => {
      setup({ queryCompanyId: 'company-1', dialogConfirmed: true });
      await settle();
      employeeService.getEmployees.mockClear();

      component.onDisable(employee());
      await settle();

      expect(employeeService.removeEmployee).toHaveBeenCalledWith('company-1', 'emp-1');
      expect(notifications.showSuccess).toHaveBeenCalled();
      expect(employeeService.getEmployees).toHaveBeenCalledTimes(1);
    });

    it('should re-enable a disabled employee without a confirmation dialog', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();
      employeeService.getEmployees.mockClear();

      component.onEnable(employee({ id: 'emp-2', enabled: false }));
      await settle();

      expect(employeeService.enableEmployee).toHaveBeenCalledWith('company-1', 'emp-2');
      expect(dialog.open).not.toHaveBeenCalled();
      expect(notifications.showSuccess).toHaveBeenCalled();
      expect(employeeService.getEmployees).toHaveBeenCalledTimes(1);
    });

    it('should surface a disable failure instead of swallowing it', async () => {
      setup({ queryCompanyId: 'company-1', dialogConfirmed: true });
      await settle();
      employeeService.removeEmployee.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 403 })),
      );

      component.onDisable(employee());
      await settle();

      expect(text()).toContain('No tenés permisos');
    });
  });
});
