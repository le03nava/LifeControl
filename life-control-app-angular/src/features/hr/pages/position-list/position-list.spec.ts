/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
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
import { PositionList } from './position-list';
import { PositionService } from '../../data/position.service';
import { DepartmentService } from '../../data/department.service';
import { Position } from '../../models/position.models';
import { Department } from '../../models/department.models';

describe('PositionList', () => {
  let fixture: ComponentFixture<PositionList>;
  let component: PositionList;
  let positionService: {
    error: Signal<string | null>;
    positions: Signal<Position[]>;
    loading: Signal<boolean>;
    getPositions: ReturnType<typeof vi.fn>;
    removePosition: ReturnType<typeof vi.fn>;
    enablePosition: ReturnType<typeof vi.fn>;
  };
  let departmentService: { getDepartments: ReturnType<typeof vi.fn> };
  let notifications: { showSuccess: ReturnType<typeof vi.fn> };
  let dialog: { open: ReturnType<typeof vi.fn> };
  let router: Router;
  let errorSignal: WritableSignal<string | null>;

  const WRITE_ROLES = ['lc-position'];
  const READ_ONLY_ROLES = ['lc-sales'];
  /** In the HR menu-visibility union but deliberately absent from the position write set. */
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

  const department = (overrides: Partial<Department> = {}): Department => ({
    id: 'dep-1',
    companyId: 'company-1',
    departmentCode: 'OPS',
    departmentName: 'Operaciones',
    description: null,
    displayOrder: 1,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  });

  const position = (overrides: Partial<Position> = {}): Position => ({
    id: 'pos-1',
    companyId: 'company-1',
    departmentId: 'dep-1',
    positionCode: 'OPR',
    positionName: 'Operario',
    description: null,
    reportsToPositionId: null,
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
  function successfulRead(rows: Position[]): Observable<Position[]> {
    errorSignal.set(null);
    return of(rows);
  }

  interface SetupOptions {
    roles?: string[];
    queryCompanyId?: string | null;
    rows?: Position[];
    loadError?: boolean;
    dialogConfirmed?: boolean;
    departments?: Department[];
    departmentsError?: boolean;
  }

  function setup(options: SetupOptions = {}): void {
    const rows = options.rows ?? [
      position(),
      position({
        id: 'pos-2',
        positionCode: 'SUP',
        positionName: 'Supervisor',
        departmentId: 'dep-2',
        reportsToPositionId: 'pos-1',
        enabled: false,
      }),
    ];
    errorSignal = signal<string | null>(null);

    positionService = {
      error: errorSignal.asReadonly(),
      positions: signal<Position[]>([]).asReadonly(),
      loading: signal(false).asReadonly(),
      getPositions: vi.fn(() => {
        if (options.loadError) {
          errorSignal.set('Error al cargar los puestos');
          return throwError(() => new HttpErrorResponse({ status: 500 }));
        }
        return successfulRead(rows);
      }),
      removePosition: vi.fn().mockReturnValue(of(undefined)),
      enablePosition: vi.fn().mockReturnValue(of(position({ enabled: true }))),
    };

    departmentService = {
      getDepartments: vi.fn(() =>
        options.departmentsError
          ? throwError(() => new HttpErrorResponse({ status: 500 }))
          : of(
              options.departments ?? [
                department(),
                department({ id: 'dep-2', departmentName: 'Finanzas' }),
              ],
            ),
      ),
    };

    notifications = { showSuccess: vi.fn() };
    dialog = {
      open: vi.fn().mockReturnValue({ afterClosed: () => of(options.dialogConfirmed ?? false) }),
    };

    const roles = options.roles ?? WRITE_ROLES;

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [PositionList],
      providers: [
        provideRouter([]),
        { provide: PositionService, useValue: positionService },
        { provide: DepartmentService, useValue: departmentService },
        {
          provide: CompanyService,
          useValue: { getCompanySelector: vi.fn().mockReturnValue(of(companiesPage)) },
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

    fixture = TestBed.createComponent(PositionList);
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

  function rows(): HTMLElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('tr.mat-mdc-row'));
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

    expect(positionService.getPositions).not.toHaveBeenCalled();
    expect(text()).toContain('Seleccioná una empresa');
  });

  it('should load and render the company positions with every column header', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    expect(positionService.getPositions).toHaveBeenCalledWith('company-1', undefined, false);
    expect(headers()).toEqual([
      'Código',
      'Nombre',
      'Departamento',
      'Reporta a',
      'Orden',
      'Estado',
      'Acciones',
    ]);
    expect(text()).toContain('OPR');
    expect(text()).toContain('Supervisor');
    expect(text()).toContain('Activo');
    expect(text()).toContain('Deshabilitado');
  });

  it('should render the department name from the catalog instead of the raw id', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    expect(text()).toContain('Operaciones');
    expect(text()).toContain('Finanzas');
    // The wire carries `departmentId` only; the id itself must never be rendered.
    expect(text()).not.toContain('dep-1');
  });

  it('should render the reports-to label from the rows in hand', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    // `Supervisor` reports to `pos-1`, whose label is the code and the name.
    expect(text()).toContain('OPR — Operario');
  });

  it('should forward the department filter to the request', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.onDepartmentFilterChange('dep-2');
    await settle();

    expect(positionService.getPositions).toHaveBeenLastCalledWith('company-1', 'dep-2', false);
  });

  it('should forward the disabled toggle to the request instead of filtering client-side', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.onIncludeDisabledChange(true);
    await settle();

    expect(positionService.getPositions).toHaveBeenLastCalledWith('company-1', undefined, true);
  });

  it('should narrow the rendered rows by code or name without re-issuing the request', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();
    const callsAfterLoad = positionService.getPositions.mock.calls.length;
    expect(rows()).toHaveLength(2);

    component.onSearchChange('super');
    await settle();

    // The assertion counts rows instead of searching the whole page text: the
    // surviving row still mentions "Operario" in its reports-to label, which is
    // a true render of the parent's name and not a leaked row.
    expect(rows()).toHaveLength(1);
    expect(rows()[0].textContent).toContain('Supervisor');
    expect(positionService.getPositions.mock.calls.length).toBe(callsAfterLoad);
  });

  it('should show the write actions for the position write role', async () => {
    setup({ queryCompanyId: 'company-1', roles: WRITE_ROLES });
    await settle();

    expect(component.canWrite).toBe(true);
    expect(actionButton('Editar')).not.toBeNull();
    expect(actionButton('Deshabilitar')).not.toBeNull();
  });

  it('should hide every write action for a caller outside the position write set', async () => {
    setup({ queryCompanyId: 'company-1', roles: READ_ONLY_ROLES });
    await settle();

    expect(component.canWrite).toBe(false);
    expect(actionButton('Editar')).toBeNull();
    expect(actionButton('Deshabilitar')).toBeNull();
    expect(actionButton('Reactivar')).toBeNull();
  });

  it('should not widen the write gate to another HR write role', async () => {
    setup({ queryCompanyId: 'company-1', roles: OTHER_HR_WRITE_ROLES });
    await settle();

    // `lc-department` administers a different catalog; the HR menu union is not a write set.
    expect(component.canWrite).toBe(false);
    expect(actionButton('Editar')).toBeNull();
  });

  it('should navigate to the create route carrying the selected company', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.onCreate();

    expect(router.navigate).toHaveBeenCalledWith(['/hr/positions/create'], {
      queryParams: { companyId: 'company-1' },
    });
  });

  it('should navigate to the edit route of the row', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.onEdit(position({ id: 'pos-9' }));

    expect(router.navigate).toHaveBeenCalledWith(['/hr/positions/edit', 'pos-9'], {
      queryParams: { companyId: 'company-1' },
    });
  });

  it('should render the Configuración action for the position write role', async () => {
    setup({ queryCompanyId: 'company-1', roles: WRITE_ROLES });
    await settle();

    // D16's rule: the action ships **with** the settings screen behind it, so it is
    // a rendered control whose destination exists (the route tree pins that).
    expect(actionButton('Configuración')).not.toBeNull();
  });

  it('should hide the Configuración action for a caller outside the position write set', async () => {
    setup({ queryCompanyId: 'company-1', roles: READ_ONLY_ROLES });
    await settle();

    // The list's own mold: every row action of a write destination is gated; the
    // URL stays reachable, which is D17's declared menu/read disagreement.
    expect(actionButton('Configuración')).toBeNull();
  });

  it('should navigate to the settings route of the row carrying its company', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.onConfigure(position({ id: 'pos-9' }));

    expect(router.navigate).toHaveBeenCalledWith(['/hr/positions', 'pos-9', 'settings'], {
      queryParams: { companyId: 'company-1' },
    });
  });

  it('should disable the confirmed row and reload the list', async () => {
    setup({ queryCompanyId: 'company-1', dialogConfirmed: true });
    await settle();
    const callsBefore = positionService.getPositions.mock.calls.length;

    component.onDisable(position());
    await settle();

    expect(dialog.open).toHaveBeenCalledWith(ConfirmDialog, expect.anything());
    expect(positionService.removePosition).toHaveBeenCalledWith('company-1', 'pos-1');
    expect(notifications.showSuccess).toHaveBeenCalledWith('Puesto deshabilitado correctamente.');
    expect(positionService.getPositions.mock.calls.length).toBeGreaterThan(callsBefore);
  });

  it('should leave the row alone when the disable confirmation is dismissed', async () => {
    setup({ queryCompanyId: 'company-1', dialogConfirmed: false });
    await settle();

    component.onDisable(position());
    await settle();

    expect(positionService.removePosition).not.toHaveBeenCalled();
    expect(notifications.showSuccess).not.toHaveBeenCalled();
  });

  it('should re-enable a disabled row and reload the list', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.onEnable(position({ id: 'pos-2', enabled: false }));
    await settle();

    expect(positionService.enablePosition).toHaveBeenCalledWith('company-1', 'pos-2');
    expect(notifications.showSuccess).toHaveBeenCalledWith('Puesto reactivado correctamente.');
  });

  it('should surface a failed disable instead of swallowing it', async () => {
    setup({ queryCompanyId: 'company-1', dialogConfirmed: true });
    await settle();
    positionService.removePosition.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 409 })),
    );

    component.onDisable(position());
    await settle();

    expect(component.actionError()).toBeTruthy();
  });

  it('should name a failed department catalogue instead of rendering an empty column', async () => {
    setup({ queryCompanyId: 'company-1', departmentsError: true });
    await settle();

    // The positions still render; the failure is named rather than read as
    // "this company has no departments".
    expect(component.catalogueError()).toBe(
      'No se pudieron cargar los departamentos de la empresa',
    );
    expect(text()).toContain('No se pudieron cargar los departamentos de la empresa');
    expect(text()).toContain('OPR');
  });

  it('should show the load failure and re-issue the request on retry', async () => {
    setup({ queryCompanyId: 'company-1', loadError: true });
    await settle();

    expect(text()).toContain('Error al cargar los puestos');
    const callsBefore = positionService.getPositions.mock.calls.length;

    retryButton()?.click();
    await settle();

    expect(positionService.getPositions.mock.calls.length).toBeGreaterThan(callsBefore);
  });
});
