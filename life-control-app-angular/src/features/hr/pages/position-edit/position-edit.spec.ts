/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Signal, WritableSignal, signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { Company, Page } from '@features/companies/companies/models/company.models';
import { PositionEdit } from './position-edit';
import { PositionService } from '../../data/position.service';
import { DepartmentService } from '../../data/department.service';
import { Position } from '../../models/position.models';
import { Department } from '../../models/department.models';

describe('PositionEdit', () => {
  let fixture: ComponentFixture<PositionEdit>;
  let component: PositionEdit;
  let positionService: {
    error: Signal<string | null>;
    positions: Signal<Position[]>;
    loading: Signal<boolean>;
    getPosition: ReturnType<typeof vi.fn>;
    getPositions: ReturnType<typeof vi.fn>;
    addPosition: ReturnType<typeof vi.fn>;
    updatePosition: ReturnType<typeof vi.fn>;
  };
  let departmentService: { getDepartments: ReturnType<typeof vi.fn> };
  let router: Router;
  let errorSignal: WritableSignal<string | null>;

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
    description: 'Planta',
    reportsToPositionId: null,
    displayOrder: 4,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  });

  interface SetupOptions {
    queryCompanyId?: string | null;
    editId?: string | null;
    loadError?: boolean;
    submitError?: HttpErrorResponse;
    siblings?: Position[];
    departmentsError?: boolean;
    siblingsError?: boolean;
  }

  function setup(options: SetupOptions = {}): void {
    errorSignal = signal<string | null>(null);

    positionService = {
      error: errorSignal.asReadonly(),
      positions: signal<Position[]>([]).asReadonly(),
      loading: signal(false).asReadonly(),
      getPosition: vi.fn(() => {
        if (options.loadError) {
          return throwError(() => new HttpErrorResponse({ status: 404 }));
        }
        return of(position());
      }),
      getPositions: vi.fn(() => {
        if (options.siblingsError) {
          return throwError(() => new HttpErrorResponse({ status: 500 }));
        }
        return of(
          options.siblings ?? [
            position(),
            position({ id: 'pos-2', positionCode: 'SUP', positionName: 'Supervisor' }),
          ],
        );
      }),
      addPosition: vi.fn(() => of(position())),
      updatePosition: vi.fn(() => of(position())),
    };

    departmentService = {
      getDepartments: vi.fn(() =>
        options.departmentsError
          ? throwError(() => new HttpErrorResponse({ status: 500 }))
          : of([department(), department({ id: 'dep-2', departmentName: 'Finanzas' })]),
      ),
    };

    if (options.submitError) {
      positionService.addPosition.mockReturnValue(throwError(() => options.submitError));
      positionService.updatePosition.mockReturnValue(throwError(() => options.submitError));
    }

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [PositionEdit],
      providers: [
        provideRouter([]),
        { provide: PositionService, useValue: positionService },
        { provide: DepartmentService, useValue: departmentService },
        {
          provide: CompanyService,
          useValue: { getCompanySelector: vi.fn().mockReturnValue(of(companiesPage)) },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap(options.editId ? { id: options.editId } : {}),
              queryParamMap: convertToParamMap(
                options.queryCompanyId ? { companyId: options.queryCompanyId } : {},
              ),
            },
          },
        },
      ],
    });

    fixture = TestBed.createComponent(PositionEdit);
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

  const validCreateValue = {
    departmentId: 'dep-1',
    positionCode: 'JEF',
    positionName: 'Jefe de almacén',
    description: 'Turno diurno',
    reportsToPositionId: null,
    displayOrder: 3,
    enabled: true,
  };

  it('should create', () => {
    setup();
    expect(component).toBeTruthy();
  });

  it('should not load a position in create mode', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    expect(component.isEditMode()).toBe(false);
    expect(positionService.getPosition).not.toHaveBeenCalled();
    expect(text()).toContain('Nuevo Puesto');
  });

  it('should offer the company departments as options', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    expect(departmentService.getDepartments).toHaveBeenCalledWith('company-1');
    expect(component.departments().map((d) => d.departmentName)).toEqual([
      'Operaciones',
      'Finanzas',
    ]);
  });

  it('should refuse to submit while the required fields are empty', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.onSubmit();
    await settle();

    expect(positionService.addPosition).not.toHaveBeenCalled();
    expect(component.formGroup.controls.positionCode.touched).toBe(true);
    expect(text()).toContain('Este campo es obligatorio.');
  });

  it('should create the position with the built request and return to the list', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.formGroup.setValue(validCreateValue);
    component.onSubmit();
    await settle();

    expect(positionService.addPosition).toHaveBeenCalledWith('company-1', {
      departmentId: 'dep-1',
      positionCode: 'JEF',
      positionName: 'Jefe de almacén',
      description: 'Turno diurno',
      reportsToPositionId: null,
      displayOrder: 3,
      enabled: true,
    });
    expect(router.navigate).toHaveBeenCalledWith(['/hr/positions'], {
      queryParams: { companyId: 'company-1' },
    });
  });

  it('should send an absent description as null instead of an empty string', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.formGroup.setValue({ ...validCreateValue, description: '   ' });
    component.onSubmit();
    await settle();

    expect(positionService.addPosition).toHaveBeenCalledWith(
      'company-1',
      expect.objectContaining({ description: null }),
    );
  });

  it('should load the position into the form in edit mode', async () => {
    setup({ queryCompanyId: 'company-1', editId: 'pos-1' });
    await settle();

    expect(component.isEditMode()).toBe(true);
    expect(positionService.getPosition).toHaveBeenCalledWith('company-1', 'pos-1');
    expect(component.formGroup.getRawValue()).toEqual({
      departmentId: 'dep-1',
      positionCode: 'OPR',
      positionName: 'Operario',
      description: 'Planta',
      reportsToPositionId: null,
      displayOrder: 4,
      enabled: true,
    });
    expect(text()).toContain('Editar Puesto');
  });

  it('should update the position in edit mode and return to the list', async () => {
    setup({ queryCompanyId: 'company-1', editId: 'pos-1' });
    await settle();

    component.formGroup.controls.positionName.setValue('Operario senior');
    component.onSubmit();
    await settle();

    expect(positionService.updatePosition).toHaveBeenCalledWith(
      'company-1',
      'pos-1',
      expect.objectContaining({ positionName: 'Operario senior' }),
    );
    expect(positionService.addPosition).not.toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/hr/positions'], {
      queryParams: { companyId: 'company-1' },
    });
  });

  it('should never offer the position itself as its own reporting line', async () => {
    setup({ queryCompanyId: 'company-1', editId: 'pos-1' });
    await settle();

    // The API rejects a self-reporting position; the screen removes the one
    // option it can prove invalid without re-implementing the cycle walk.
    expect(component.reportsToOptions().map((p) => p.id)).toEqual(['pos-2']);
  });

  it('should report a load failure instead of rendering an empty form', async () => {
    setup({ queryCompanyId: 'company-1', editId: 'pos-1', loadError: true });
    await settle();

    expect(component.loadError()).toBeTruthy();
  });

  it('should surface the duplicate-code conflict as a banner', async () => {
    setup({
      queryCompanyId: 'company-1',
      submitError: new HttpErrorResponse({
        status: 409,
        error: { message: 'Ya existe un puesto con ese código en el departamento' },
      }),
    });
    await settle();

    component.formGroup.setValue(validCreateValue);
    component.onSubmit();
    await settle();

    expect(text()).toContain('Ya existe un puesto con ese código en el departamento');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('should map a per-field error envelope onto the control', async () => {
    setup({
      queryCompanyId: 'company-1',
      submitError: new HttpErrorResponse({
        status: 400,
        error: { errors: { positionCode: 'Código inválido para la empresa' } },
      }),
    });
    await settle();

    component.formGroup.setValue(validCreateValue);
    component.onSubmit();
    await settle();

    expect(component.serverErrors()).toEqual({ positionCode: 'Código inválido para la empresa' });
    expect(text()).toContain('Código inválido para la empresa');
  });

  it('should clear the chosen parent and reporting line when the company changes', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.formGroup.setValue({ ...validCreateValue, reportsToPositionId: 'pos-2' });
    component.onCompanyChange('company-2');

    // Both belong to one company; keeping either one would submit a cross-company link.
    expect(component.formGroup.controls.departmentId.value).toBe('');
    expect(component.formGroup.controls.reportsToPositionId.value).toBeNull();
  });

  it('should name a failed catalogue read instead of rendering an empty select', async () => {
    setup({ queryCompanyId: 'company-1', departmentsError: true });
    await settle();

    // The form stays usable; the failure is named rather than read as
    // "this company has no departments".
    expect(component.catalogueError()).toBe(
      'No se pudieron cargar los departamentos de la empresa',
    );
    expect(text()).toContain('No se pudieron cargar los departamentos de la empresa');
    expect(text()).toContain('Nuevo Puesto');
  });

  it('should name a failed reports-to catalogue too', async () => {
    setup({ queryCompanyId: 'company-1', siblingsError: true });
    await settle();

    expect(component.catalogueError()).toBe('No se pudieron cargar los puestos de la empresa');
    expect(component.reportsToOptions()).toEqual([]);
  });

  it('should return to the list on cancel', async () => {
    setup({ queryCompanyId: 'company-1' });
    await settle();

    component.onCancel();

    expect(router.navigate).toHaveBeenCalledWith(['/hr/positions'], {
      queryParams: { companyId: 'company-1' },
    });
  });
});
