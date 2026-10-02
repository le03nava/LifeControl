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
import { DepartmentList } from './department-list';
import { DepartmentService } from '../../data/department.service';
import { Department } from '../../models/department.models';

describe('DepartmentList', () => {
  let fixture: ComponentFixture<DepartmentList>;
  let component: DepartmentList;
  let departmentService: {
    error: Signal<string | null>;
    departments: Signal<Department[]>;
    loading: Signal<boolean>;
    getDepartments: ReturnType<typeof vi.fn>;
    removeDepartment: ReturnType<typeof vi.fn>;
    enableDepartment: ReturnType<typeof vi.fn>;
  };
  let notifications: { showSuccess: ReturnType<typeof vi.fn> };
  let dialog: { open: ReturnType<typeof vi.fn> };
  let router: Router;
  let errorSignal: WritableSignal<string | null>;

  const WRITE_ROLES = ['lc-department'];
  const READ_ONLY_ROLES = ['lc-sales'];
  /** In the HR menu-visibility union but deliberately absent from the department write set. */
  const OTHER_HR_WRITE_ROLES = ['lc-position'];

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

  const department = (overrides: Partial<Department> = {}): Department => ({
    id: 'dep-1',
    companyId: 'company-1',
    departmentCode: 'OPS',
    departmentName: 'Operaciones',
    description: 'Core operations',
    displayOrder: 1,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  });

  /**
   * Emulates the real service's list contract: a successful read clears the
   * load error before emitting.
   */
  function successfulRead(rows: Department[]): Observable<Department[]> {
    errorSignal.set(null);
    return of(rows);
  }

  interface SetupOptions {
    roles?: string[];
    queryCompanyId?: string | null;
    rows?: Department[];
    loadError?: boolean;
    dialogConfirmed?: boolean;
  }

  function setup(options: SetupOptions = {}): void {
    const rows = options.rows ?? [
      department(),
      department({
        id: 'dep-2',
        departmentCode: 'FIN',
        departmentName: 'Finanzas',
        enabled: false,
      }),
    ];
    errorSignal = signal<string | null>(null);

    departmentService = {
      error: errorSignal.asReadonly(),
      departments: signal<Department[]>([]).asReadonly(),
      loading: signal(false).asReadonly(),
      getDepartments: vi.fn(() => {
        if (options.loadError) {
          errorSignal.set('Error al cargar los departamentos');
          return throwError(() => new HttpErrorResponse({ status: 500 }));
        }
        return successfulRead(rows);
      }),
      removeDepartment: vi.fn().mockReturnValue(of(undefined)),
      enableDepartment: vi.fn().mockReturnValue(of(department({ enabled: true }))),
    };

    notifications = { showSuccess: vi.fn() };
    dialog = {
      open: vi.fn().mockReturnValue({ afterClosed: () => of(options.dialogConfirmed ?? false) }),
    };

    const roles = options.roles ?? WRITE_ROLES;

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [DepartmentList, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: DepartmentService, useValue: departmentService },
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

    fixture = TestBed.createComponent(DepartmentList);
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

  it('should create', () => {
    setup();
    expect(component).toBeTruthy();
  });

  it('should issue no list request until a company is selected', async () => {
    setup({ queryCompanyId: null });
    await settle();

    expect(departmentService.getDepartments).not.toHaveBeenCalled();
    expect(text()).toContain('Seleccioná una empresa');
  });

  it('should load and render the company departments with every column header', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    expect(departmentService.getDepartments).toHaveBeenCalledWith('company-1', false);
    expect(text()).toContain('Operaciones');
    expect(text()).toContain('Finanzas');
    expect(headers()).toEqual(['Código', 'Nombre', 'Descripción', 'Orden', 'Estado', 'Acciones']);
  });

  it('should re-issue the request with includeDisabled true when the toggle turns on', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.onIncludeDisabledChange(true);
    await settle();

    expect(component.includeDisabled()).toBe(true);
    expect(departmentService.getDepartments).toHaveBeenLastCalledWith('company-1', true);
  });

  it('should filter the rendered rows by the search term', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.onSearchChange('fin');
    await settle();

    expect(text()).toContain('Finanzas');
    expect(text()).not.toContain('Operaciones');
  });

  it('should render the empty state when the company has no departments', async () => {
    setup({ queryCompanyId: 'company-1', rows: [] });
    await settle();

    expect(text()).toContain('No hay departamentos registrados');
  });

  it('should render the error state and retry', async () => {
    setup({ queryCompanyId: 'company-1', loadError: true });
    await settle();

    expect(text()).toContain('Error al cargar los departamentos');
    expect(text()).toContain('Reintentar');

    departmentService.getDepartments.mockImplementation(() => successfulRead([department()]));
    component.reloadList();
    await settle();

    expect(text()).toContain('Operaciones');
  });

  it('should re-issue the list read when the rendered retry button is clicked', async () => {
    setup({ queryCompanyId: 'company-1', loadError: true });
    await settle();

    expect(departmentService.getDepartments).toHaveBeenCalledTimes(1);
    departmentService.getDepartments.mockImplementation(() => successfulRead([department()]));

    const button = retryButton();
    expect(button).not.toBeNull();
    button?.click();
    await settle();

    expect(departmentService.getDepartments).toHaveBeenCalledTimes(2);
    expect(text()).toContain('Operaciones');
  });

  describe('company selection', () => {
    it('should seed the selection from the companyId query param', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      expect(component.selectedCompanyId()).toBe('company-1');
      expect(departmentService.getDepartments).toHaveBeenCalledWith('company-1', false);
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
  });

  describe('navigation', () => {
    it('should navigate to create carrying the selected company', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      component.onCreate();

      expect(router.navigate).toHaveBeenCalledWith(['/hr/departments/create'], {
        queryParams: { companyId: 'company-1' },
      });
    });

    it('should navigate to edit carrying the department company', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      component.onEdit(department());

      expect(router.navigate).toHaveBeenCalledWith(['/hr/departments/edit', 'dep-1'], {
        queryParams: { companyId: 'company-1' },
      });
    });
  });

  describe('write-role gating', () => {
    it('should hide every write control for a read-only role', async () => {
      setup({ roles: READ_ONLY_ROLES, queryCompanyId: 'company-1' });
      await settle();

      expect(component.canWrite).toBe(false);
      expect(text()).not.toContain('Nuevo Departamento');
      expect(actionButton('Editar')).toBeNull();
      expect(actionButton('Deshabilitar')).toBeNull();
    });

    it('should still render the department rows for a caller without a write role', async () => {
      setup({ roles: READ_ONLY_ROLES, queryCompanyId: 'company-1' });
      await settle();

      expect(component.canWrite).toBe(false);
      expect(text()).toContain('Operaciones');
      expect(text()).toContain('Finanzas');
      expect(text()).not.toContain('Nuevo Departamento');
      expect(actionButton('Editar')).toBeNull();
      expect(actionButton('Deshabilitar')).toBeNull();
    });

    it('should render the write controls for a write role', async () => {
      setup({ roles: WRITE_ROLES, queryCompanyId: 'company-1' });
      await settle();

      expect(component.canWrite).toBe(true);
      expect(text()).toContain('Nuevo Departamento');
      expect(actionButton('Editar')).not.toBeNull();
      expect(actionButton('Deshabilitar')).not.toBeNull();
    });

    it('should hide the department write controls for another HR write role outside the department set', async () => {
      setup({ roles: OTHER_HR_WRITE_ROLES, queryCompanyId: 'company-1' });
      await settle();

      expect(component.canWrite).toBe(false);
      expect(text()).not.toContain('Nuevo Departamento');
      expect(actionButton('Editar')).toBeNull();
      expect(actionButton('Deshabilitar')).toBeNull();
    });
  });

  describe('disable / re-enable', () => {
    it('should confirm before disabling and not call the service when cancelled', async () => {
      setup({ queryCompanyId: 'company-1', dialogConfirmed: false });
      await settle();

      component.onDisable(department());
      await settle();

      expect(dialog.open).toHaveBeenCalledWith(ConfirmDialog, expect.anything());
      expect(departmentService.removeDepartment).not.toHaveBeenCalled();
    });

    it('should disable, notify and reload when confirmed', async () => {
      setup({ queryCompanyId: 'company-1', dialogConfirmed: true });
      await settle();
      departmentService.getDepartments.mockClear();

      component.onDisable(department());
      await settle();

      expect(departmentService.removeDepartment).toHaveBeenCalledWith('company-1', 'dep-1');
      expect(notifications.showSuccess).toHaveBeenCalled();
      expect(departmentService.getDepartments).toHaveBeenCalledTimes(1);
    });

    it('should re-enable a disabled department', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      component.onEnable(department({ id: 'dep-2', enabled: false }));
      await settle();

      expect(departmentService.enableDepartment).toHaveBeenCalledWith('company-1', 'dep-2');
    });

    it('should surface a disable failure instead of swallowing it', async () => {
      setup({ queryCompanyId: 'company-1', dialogConfirmed: true });
      await settle();
      departmentService.removeDepartment.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 403 })),
      );

      component.onDisable(department());
      await settle();

      expect(text()).toContain('No tenés permisos');
    });
  });
});
