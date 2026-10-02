/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Signal, signal } from '@angular/core';
import { MatSelect } from '@angular/material/select';
import { of, throwError } from 'rxjs';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { Company, Page } from '@features/companies/companies/models/company.models';
import { DepartmentEdit } from './department-edit';
import { DepartmentService } from '../../data/department.service';
import { Department } from '../../models/department.models';

describe('DepartmentEdit', () => {
  let fixture: ComponentFixture<DepartmentEdit>;
  let component: DepartmentEdit;
  let departmentService: {
    error: Signal<string | null>;
    getDepartment: ReturnType<typeof vi.fn>;
    addDepartment: ReturnType<typeof vi.fn>;
    updateDepartment: ReturnType<typeof vi.fn>;
  };
  let router: Router;

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

  interface SetupOptions {
    id?: string | null;
    companyId?: string | null;
    departmentError?: HttpErrorResponse;
    saveError?: HttpErrorResponse;
  }

  function setup(options: SetupOptions = {}): void {
    departmentService = {
      error: signal<string | null>(null).asReadonly(),
      getDepartment: vi.fn(() =>
        options.departmentError ? throwError(() => options.departmentError) : of(department()),
      ),
      addDepartment: vi.fn(() =>
        options.saveError ? throwError(() => options.saveError) : of(department()),
      ),
      updateDepartment: vi.fn(() =>
        options.saveError ? throwError(() => options.saveError) : of(department()),
      ),
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [DepartmentEdit, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: DepartmentService, useValue: departmentService },
        {
          provide: CompanyService,
          useValue: { getCompanies: vi.fn().mockReturnValue(of(companiesPage)) },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap(options.id ? { id: options.id } : {}),
              queryParamMap: convertToParamMap(
                options.companyId ? { companyId: options.companyId } : {},
              ),
            },
          },
        },
      ],
    });

    fixture = TestBed.createComponent(DepartmentEdit);
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

  it('should create in create mode with an empty form', async () => {
    setup({ companyId: 'company-1' });
    await settle();

    expect(component.isEditMode()).toBe(false);
    expect(departmentService.getDepartment).not.toHaveBeenCalled();
    expect(component.formGroup.getRawValue()).toEqual({
      departmentCode: '',
      departmentName: '',
      description: '',
      displayOrder: null,
      enabled: true,
    });
  });

  it('should not submit an invalid form and should mark the controls touched', async () => {
    setup({ companyId: 'company-1' });
    await settle();

    component.onSubmit();
    await settle();

    expect(departmentService.addDepartment).not.toHaveBeenCalled();
    expect(component.formGroup.controls.departmentCode.touched).toBe(true);
    expect(text()).toContain('Este campo es obligatorio.');
  });

  it('should post a create and navigate back carrying the company', async () => {
    setup({ companyId: 'company-1' });
    await settle();

    component.formGroup.patchValue({
      departmentCode: 'RRHH',
      departmentName: 'Recursos Humanos',
      description: '  Gente  ',
      displayOrder: 3,
      enabled: true,
    });
    component.onSubmit();
    await settle();

    expect(departmentService.addDepartment).toHaveBeenCalledWith('company-1', {
      departmentCode: 'RRHH',
      departmentName: 'Recursos Humanos',
      description: 'Gente',
      displayOrder: 3,
      enabled: true,
    });
    expect(router.navigate).toHaveBeenCalledWith(['/hr/departments'], {
      queryParams: { companyId: 'company-1' },
    });
  });

  it('should load the department by id, hydrate the form and disable the company selector in edit mode', async () => {
    setup({ id: 'dep-1', companyId: 'company-1' });
    await settle();

    expect(departmentService.getDepartment).toHaveBeenCalledWith('company-1', 'dep-1');
    expect(component.isEditMode()).toBe(true);
    expect(component.formGroup.getRawValue()).toEqual({
      departmentCode: 'OPS',
      departmentName: 'Operaciones',
      description: 'Core operations',
      displayOrder: 1,
      enabled: true,
    });
    const select = fixture.debugElement.query(By.css('mat-select')).componentInstance as MatSelect;
    expect(select.disabled).toBe(true);
  });

  it('should put an edit and navigate back carrying the company', async () => {
    setup({ id: 'dep-1', companyId: 'company-1' });
    await settle();

    component.formGroup.patchValue({ departmentName: 'Operaciones 2' });
    component.onSubmit();
    await settle();

    expect(departmentService.updateDepartment).toHaveBeenCalledWith('company-1', 'dep-1', {
      departmentCode: 'OPS',
      departmentName: 'Operaciones 2',
      description: 'Core operations',
      displayOrder: 1,
      enabled: true,
    });
    expect(router.navigate).toHaveBeenCalledWith(['/hr/departments'], {
      queryParams: { companyId: 'company-1' },
    });
  });

  it('should surface a load error when the edit has no company in the URL', async () => {
    setup({ id: 'dep-1', companyId: null });
    await settle();

    expect(departmentService.getDepartment).not.toHaveBeenCalled();
    expect(text()).toContain('No se indicó la empresa');
  });

  it('should surface a failed edit load with the HTTP message', async () => {
    setup({
      id: 'dep-1',
      companyId: 'company-1',
      departmentError: new HttpErrorResponse({ status: 404 }),
    });
    await settle();

    expect(text()).toContain('No se encontró el recurso solicitado');
  });

  it('should surface a 409 duplicate as a banner message', async () => {
    setup({
      companyId: 'company-1',
      saveError: new HttpErrorResponse({
        status: 409,
        error: { message: 'Department with code OPS already exists for this company' },
      }),
    });
    await settle();

    component.formGroup.patchValue({ departmentCode: 'OPS', departmentName: 'Operaciones' });
    component.onSubmit();
    await settle();

    expect(text()).toContain('Department with code OPS already exists for this company');
  });

  it('should map a per-field API error onto its control', async () => {
    setup({
      companyId: 'company-1',
      saveError: new HttpErrorResponse({
        status: 400,
        error: { message: 'Validation error', errors: { departmentCode: 'código inválido' } },
      }),
    });
    await settle();

    component.formGroup.patchValue({ departmentCode: 'OPS', departmentName: 'Operaciones' });
    component.onSubmit();
    await settle();

    expect(text()).toContain('código inválido');
  });

  it('should navigate back on cancel', async () => {
    setup({ companyId: 'company-1' });
    await settle();

    component.onCancel();

    expect(router.navigate).toHaveBeenCalledWith(['/hr/departments'], {
      queryParams: { companyId: 'company-1' },
    });
  });
});
