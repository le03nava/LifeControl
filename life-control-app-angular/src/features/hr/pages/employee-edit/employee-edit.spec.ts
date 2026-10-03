/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Signal, signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { EmployeeEdit } from './employee-edit';
import { EmployeeService } from '../../data/employee.service';
import { EmployeeStatusService } from '../../data/employee-status.service';
import { Employee, EmployeeEmailSuggestion } from '../../models/employee.models';

describe('EmployeeEdit', () => {
  let fixture: ComponentFixture<EmployeeEdit>;
  let component: EmployeeEdit;
  let employeeService: {
    error: Signal<string | null>;
    getEmployee: ReturnType<typeof vi.fn>;
    addEmployee: ReturnType<typeof vi.fn>;
    updateEmployee: ReturnType<typeof vi.fn>;
    suggestEmail: ReturnType<typeof vi.fn>;
  };
  let statusService: { loadEmployeeStatusIds: ReturnType<typeof vi.fn> };
  let router: Router;

  /** The `statusName -> statusId` map `loadEmployeeStatusIds` resolves to. */
  const STATUS_IDS: ReadonlyMap<string, string> = new Map([
    ['Active', 'status-active'],
    ['Inactive', 'status-inactive'],
    ['OnLeave', 'status-onleave'],
    ['Terminated', 'status-terminated'],
  ]);

  const employee = (overrides: Partial<Employee> = {}): Employee => ({
    id: 'emp-1',
    companyId: 'company-1',
    employeeNumber: 'EMP-001',
    firstName: 'Ana',
    paternalLastName: 'Gómez',
    maternalLastName: 'Ruiz',
    email: 'ana.gomez@acme.example',
    phoneNumber: '+525512345678',
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

  interface SetupOptions {
    id?: string | null;
    companyId?: string | null;
    employee?: Partial<Employee>;
    employeeError?: HttpErrorResponse;
    saveError?: HttpErrorResponse;
    suggestion?: EmployeeEmailSuggestion;
    statusCatalogue?: ReadonlyMap<string, string>;
    statusCatalogueError?: boolean;
  }

  function setup(options: SetupOptions = {}): void {
    employeeService = {
      error: signal<string | null>(null).asReadonly(),
      getEmployee: vi.fn(() =>
        options.employeeError
          ? throwError(() => options.employeeError)
          : of(employee(options.employee)),
      ),
      addEmployee: vi.fn(() =>
        options.saveError ? throwError(() => options.saveError) : of(employee()),
      ),
      updateEmployee: vi.fn(() =>
        options.saveError ? throwError(() => options.saveError) : of(employee()),
      ),
      suggestEmail: vi.fn(() => of(options.suggestion ?? { email: null, reason: null })),
    };

    statusService = {
      loadEmployeeStatusIds: vi.fn(() =>
        options.statusCatalogueError
          ? throwError(() => new HttpErrorResponse({ status: 500 }))
          : of(options.statusCatalogue ?? STATUS_IDS),
      ),
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [EmployeeEdit, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: EmployeeService, useValue: employeeService },
        { provide: EmployeeStatusService, useValue: statusService },
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

    fixture = TestBed.createComponent(EmployeeEdit);
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

  function emailInput(): HTMLInputElement {
    return (fixture.nativeElement as HTMLElement).querySelector(
      'input[formcontrolname="email"]',
    ) as HTMLInputElement;
  }

  /** The exact `PUT`/`POST` body for the hydrated fixture with only the first name changed. */
  const updatedBody = {
    employeeNumber: 'EMP-001',
    firstName: 'Ana Maria',
    paternalLastName: 'Gómez',
    maternalLastName: 'Ruiz',
    email: 'ana.gomez@acme.example',
    phoneNumber: '+525512345678',
    birthDate: '1990-05-01',
    hireDate: '2024-02-15',
    terminationDate: null,
    addressId: null,
    statusId: 'status-active',
  };

  it('should create in create mode with an empty form and the seeded Active status', async () => {
    setup({ companyId: 'company-1' });
    await settle();

    expect(component.isEditMode()).toBe(false);
    expect(employeeService.getEmployee).not.toHaveBeenCalled();
    expect(statusService.loadEmployeeStatusIds).toHaveBeenCalled();
    expect(component.formGroup().getRawValue()).toEqual({
      employeeNumber: '',
      firstName: '',
      paternalLastName: '',
      maternalLastName: null,
      email: null,
      phoneNumber: null,
      birthDate: '',
      hireDate: '',
      terminationDate: null,
      statusId: 'status-active',
    });
    expect(component.statusOptions()).toContainEqual({ id: 'status-active', label: 'Activo' });
    expect(component.emailFrozen()).toBe(false);
  });

  it('should label every resolved status through employeeStatusLabel', async () => {
    setup({ companyId: 'company-1' });
    await settle();

    expect(component.statusOptions().map((option) => option.label)).toEqual([
      'Activo',
      'Inactivo',
      'Con licencia',
      'Dado de baja',
    ]);
  });

  it('should say so and offer no option when the status catalogue cannot be resolved', async () => {
    setup({ companyId: 'company-1', statusCatalogueError: true });
    await settle();

    expect(component.statusOptions()).toEqual([]);
    expect(component.formGroup().controls.statusId.value).toBeNull();
    expect(text()).toContain('No se pudo cargar el catálogo de estados.');
  });

  it('should keep the loaded status selectable and unchanged on update when the catalogue fails', async () => {
    setup({ id: 'emp-1', companyId: 'company-1', statusCatalogueError: true });
    await settle();

    expect(component.statusOptions()).toEqual([{ id: 'status-active', label: 'Activo' }]);

    component.formGroup().patchValue({ firstName: 'Ana Maria' });
    component.onSubmit();
    await settle();

    // The loaded status id is carried verbatim; a failed catalogue must not let an
    // unrelated edit fall back to the service's Active default.
    expect(employeeService.updateEmployee).toHaveBeenCalledWith('company-1', 'emp-1', updatedBody);
  });

  it('should not submit an invalid form and should mark the controls touched', async () => {
    setup({ companyId: 'company-1' });
    await settle();

    component.onSubmit();
    await settle();

    expect(employeeService.addEmployee).not.toHaveBeenCalled();
    expect(component.formGroup().controls.employeeNumber.touched).toBe(true);
    expect(text()).toContain('Este campo es obligatorio.');
  });

  it('should reject a malformed email with the Angular email validator', async () => {
    setup({ companyId: 'company-1' });
    await settle();

    const email = component.formGroup().controls.email;
    email.setValue('not-an-email');
    email.markAsTouched();
    fixture.detectChanges();

    expect(email.errors?.['email']).toBeTruthy();
    expect(text()).toContain('Ingresá un correo válido.');
  });

  it('should post the exact create payload with null for every blank optional field', async () => {
    setup({ companyId: 'company-1' });
    await settle();

    component.formGroup().patchValue({
      employeeNumber: 'EMP-042',
      firstName: 'Ana',
      paternalLastName: 'Gómez',
      birthDate: '1990-05-01',
      hireDate: '2024-02-15',
    });
    component.onSubmit();
    await settle();

    expect(employeeService.addEmployee).toHaveBeenCalledWith('company-1', {
      employeeNumber: 'EMP-042',
      firstName: 'Ana',
      paternalLastName: 'Gómez',
      maternalLastName: null,
      email: null,
      phoneNumber: null,
      birthDate: '1990-05-01',
      hireDate: '2024-02-15',
      terminationDate: null,
      addressId: null,
      statusId: 'status-active',
    });
    expect(router.navigate).toHaveBeenCalledWith(['/hr/employees'], {
      queryParams: { companyId: 'company-1' },
    });
  });

  it('should post the exact create payload with every field filled', async () => {
    setup({ companyId: 'company-1' });
    await settle();

    component.formGroup().patchValue({
      employeeNumber: 'EMP-001',
      firstName: 'Ana',
      paternalLastName: 'Gómez',
      maternalLastName: 'Ruiz',
      email: 'ana.gomez@acme.example',
      phoneNumber: '+525512345678',
      birthDate: '1990-05-01',
      hireDate: '2024-02-15',
    });
    component.onSubmit();
    await settle();

    expect(employeeService.addEmployee).toHaveBeenCalledWith('company-1', {
      employeeNumber: 'EMP-001',
      firstName: 'Ana',
      paternalLastName: 'Gómez',
      maternalLastName: 'Ruiz',
      email: 'ana.gomez@acme.example',
      phoneNumber: '+525512345678',
      birthDate: '1990-05-01',
      hireDate: '2024-02-15',
      terminationDate: null,
      addressId: null,
      statusId: 'status-active',
    });
  });

  it('should hydrate the form from the loaded employee in edit mode', async () => {
    setup({ id: 'emp-1', companyId: 'company-1' });
    await settle();

    expect(employeeService.getEmployee).toHaveBeenCalledWith('company-1', 'emp-1');
    expect(component.isEditMode()).toBe(true);
    expect(component.formGroup().getRawValue()).toEqual({
      employeeNumber: 'EMP-001',
      firstName: 'Ana',
      paternalLastName: 'Gómez',
      maternalLastName: 'Ruiz',
      email: 'ana.gomez@acme.example',
      phoneNumber: '+525512345678',
      birthDate: '1990-05-01',
      hireDate: '2024-02-15',
      terminationDate: null,
      statusId: 'status-active',
    });
  });

  it('should put the exact update payload and navigate back to the list', async () => {
    setup({ id: 'emp-1', companyId: 'company-1' });
    await settle();

    component.formGroup().patchValue({ firstName: 'Ana Maria' });
    component.onSubmit();
    await settle();

    expect(employeeService.updateEmployee).toHaveBeenCalledWith('company-1', 'emp-1', updatedBody);
    expect(router.navigate).toHaveBeenCalledWith(['/hr/employees'], {
      queryParams: { companyId: 'company-1' },
    });
  });

  describe('trap 1 — the address survives an unrelated update', () => {
    it('should carry the loaded addressId back in the update payload though no control renders it', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', employee: { addressId: 'addr-9' } });
      await settle();

      // No address control exists anywhere in the form (G16).
      expect(component.formGroup().contains('addressId')).toBe(false);
      expect(fixture.nativeElement.querySelector('[formcontrolname="addressId"]')).toBeNull();

      component.formGroup().patchValue({ firstName: 'Ana Maria' });
      component.onSubmit();
      await settle();

      expect(employeeService.updateEmployee).toHaveBeenCalledWith('company-1', 'emp-1', {
        ...updatedBody,
        addressId: 'addr-9',
      });
    });

    it('should send a null addressId on create, where there is nothing to preserve', async () => {
      setup({ companyId: 'company-1' });
      await settle();

      component.formGroup().patchValue({
        employeeNumber: 'EMP-042',
        firstName: 'Ana',
        paternalLastName: 'Gómez',
        birthDate: '1990-05-01',
        hireDate: '2024-02-15',
      });
      component.onSubmit();
      await settle();

      expect(employeeService.addEmployee).toHaveBeenCalledWith(
        'company-1',
        expect.objectContaining({ addressId: null }),
      );
    });
  });

  describe('trap 2 — the provisioned email', () => {
    it('should render the email read-only and explain why when keycloakUserId is set', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        employee: { keycloakUserId: 'kc-1' },
      });
      await settle();

      expect(component.emailFrozen()).toBe(true);
      expect(emailInput().readOnly).toBe(true);
      expect(component.formGroup().controls.email.value).toBe('ana.gomez@acme.example');
      expect(text()).toContain('correo está congelado');
    });

    it('should send a null email on update when frozen, letting the service keep the stored address', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        employee: { keycloakUserId: 'kc-1', addressId: 'addr-9' },
      });
      await settle();

      component.formGroup().patchValue({ firstName: 'Ana Maria' });
      component.onSubmit();
      await settle();

      expect(employeeService.updateEmployee).toHaveBeenCalledWith('company-1', 'emp-1', {
        ...updatedBody,
        email: null,
        addressId: 'addr-9',
      });
    });

    it('should leave a non-provisioned email editable and echo the control value on update', async () => {
      setup({ id: 'emp-1', companyId: 'company-1' });
      await settle();

      expect(component.emailFrozen()).toBe(false);
      expect(emailInput().readOnly).toBe(false);

      component.formGroup().controls.email.setValue('ana.nueva@acme.example');
      component.onSubmit();
      await settle();

      expect(employeeService.updateEmployee).toHaveBeenCalledWith('company-1', 'emp-1', {
        ...updatedBody,
        firstName: 'Ana',
        email: 'ana.nueva@acme.example',
      });
    });
  });

  describe('the live email suggestion', () => {
    it('should debounce the names and ask once only after the window closes', async () => {
      vi.useFakeTimers();
      setup({
        companyId: 'company-1',
        suggestion: { email: 'ana.gomez@acme.example', reason: null },
      });
      fixture.detectChanges();

      component.formGroup().controls.firstName.setValue('Ana');
      component.formGroup().controls.paternalLastName.setValue('Gómez');
      expect(employeeService.suggestEmail).not.toHaveBeenCalled();

      vi.advanceTimersByTime(299);
      expect(employeeService.suggestEmail).not.toHaveBeenCalled();

      vi.advanceTimersByTime(1);
      vi.useRealTimers();
      await settle();

      expect(employeeService.suggestEmail).toHaveBeenCalledTimes(1);
      expect(employeeService.suggestEmail).toHaveBeenCalledWith('company-1', 'Ana', 'Gómez');
    });

    it('should not call the service while either name is blank', async () => {
      vi.useFakeTimers();
      setup({ companyId: 'company-1' });
      fixture.detectChanges();

      component.formGroup().controls.firstName.setValue('Ana');
      vi.advanceTimersByTime(400);
      vi.useRealTimers();
      await settle();

      expect(employeeService.suggestEmail).not.toHaveBeenCalled();
    });

    it('should not call the service at all when the email is frozen', async () => {
      vi.useFakeTimers();
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        employee: { keycloakUserId: 'kc-1' },
        suggestion: { email: 'ana.gomez@acme.example', reason: null },
      });
      fixture.detectChanges();

      component.formGroup().controls.firstName.setValue('Ana Maria');
      component.formGroup().controls.paternalLastName.setValue('Ruiz');
      vi.advanceTimersByTime(400);
      vi.useRealTimers();
      await settle();

      expect(employeeService.suggestEmail).not.toHaveBeenCalled();
      expect(component.suggestedEmail()).toBeNull();
    });

    it('should seed the free suggestion into a pristine email control in create mode', async () => {
      vi.useFakeTimers();
      setup({
        companyId: 'company-1',
        suggestion: { email: 'ana.gomez@acme.example', reason: null },
      });
      fixture.detectChanges();

      component.formGroup().controls.firstName.setValue('Ana');
      component.formGroup().controls.paternalLastName.setValue('Gómez');
      vi.advanceTimersByTime(300);
      vi.useRealTimers();
      await settle();

      expect(component.formGroup().controls.email.value).toBe('ana.gomez@acme.example');
    });

    it('should never overwrite a value the operator typed and offer it as an acceptable hint', async () => {
      vi.useFakeTimers();
      setup({
        companyId: 'company-1',
        suggestion: { email: 'ana.gomez@acme.example', reason: null },
      });
      fixture.detectChanges();

      const email = component.formGroup().controls.email;
      email.setValue('manual@acme.example');
      email.markAsDirty();

      component.formGroup().controls.firstName.setValue('Ana');
      component.formGroup().controls.paternalLastName.setValue('Gómez');
      vi.advanceTimersByTime(300);
      vi.useRealTimers();
      await settle();

      expect(email.value).toBe('manual@acme.example');
      expect(component.suggestedEmail()).toBe('ana.gomez@acme.example');

      component.acceptSuggestion();
      await settle();

      expect(email.value).toBe('ana.gomez@acme.example');
      expect(component.suggestedEmail()).toBeNull();
    });

    it('should not seed a hydrated edit, offering the suggestion as a hint instead', async () => {
      vi.useFakeTimers();
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        suggestion: { email: 'ana.gomez2@acme.example', reason: null },
      });
      fixture.detectChanges();

      component.formGroup().controls.firstName.setValue('Ana Maria');
      vi.advanceTimersByTime(300);
      vi.useRealTimers();
      await settle();

      expect(component.formGroup().controls.email.value).toBe('ana.gomez@acme.example');
      expect(component.suggestedEmail()).toBe('ana.gomez2@acme.example');
    });

    it.each([
      ['NO_EMAIL_DOMAIN', 'Configurá el dominio de correo de la empresa'],
      ['EMPTY_LOCAL_PART', 'Los nombres no permiten construir un correo'],
      ['NO_FREE_CANDIDATE', 'ya están en uso'],
    ])(
      'should render the %s reason as its own copy, never a generic error',
      async (reason, expected) => {
        vi.useFakeTimers();
        setup({ companyId: 'company-1', suggestion: { email: null, reason } });
        fixture.detectChanges();

        component.formGroup().controls.firstName.setValue('Ana');
        component.formGroup().controls.paternalLastName.setValue('Gómez');
        vi.advanceTimersByTime(300);
        vi.useRealTimers();
        await settle();

        expect(text()).toContain(expected);
        expect(component.formGroup().controls.email.value).toBeNull();
      },
    );

    it('should say the NO_FREE_CANDIDATE suggestion reserves nothing', async () => {
      vi.useFakeTimers();
      setup({
        companyId: 'company-1',
        suggestion: { email: null, reason: 'NO_FREE_CANDIDATE' },
      });
      fixture.detectChanges();

      component.formGroup().controls.firstName.setValue('Ana');
      component.formGroup().controls.paternalLastName.setValue('Gómez');
      vi.advanceTimersByTime(300);
      vi.useRealTimers();
      await settle();

      expect(component.suggestionMessage()?.toLowerCase()).toContain('no reserva');
    });
  });

  describe('the status / termination-date rule (mirrors the service, which stays authoritative)', () => {
    it('should require a termination date when the status is Terminated', async () => {
      setup({ id: 'emp-1', companyId: 'company-1' });
      await settle();

      component.onStatusChange('status-terminated');
      fixture.detectChanges();

      const terminationDate = component.formGroup().controls.terminationDate;
      expect(terminationDate.errors?.['terminationDateRequired']).toBe(true);

      component.onSubmit();
      await settle();

      expect(employeeService.updateEmployee).not.toHaveBeenCalled();
      expect(text()).toContain('La fecha de baja es obligatoria cuando el estado es Dado de baja.');
    });

    it('should forbid a termination date for any other status', async () => {
      setup({ id: 'emp-1', companyId: 'company-1' });
      await settle();

      const terminationDate = component.formGroup().controls.terminationDate;
      terminationDate.setValue('2026-06-01');
      terminationDate.markAsTouched();
      fixture.detectChanges();

      expect(terminationDate.errors?.['terminationDateForbidden']).toBe(true);
      expect(text()).toContain('Solo un empleado dado de baja puede tener fecha de baja.');
    });

    it('should accept Terminated with a termination date', async () => {
      setup({ id: 'emp-1', companyId: 'company-1' });
      await settle();

      component.onStatusChange('status-terminated');
      component.formGroup().controls.terminationDate.setValue('2026-06-01');

      expect(component.formGroup().controls.terminationDate.errors).toBeNull();
      expect(component.formGroup().valid).toBe(true);
    });
  });

  describe('server errors', () => {
    it('should surface a 409 as a banner carrying the server message', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        saveError: new HttpErrorResponse({
          status: 409,
          error: {
            message: "Employee with email 'ana.gomez@acme.example' already exists for this company",
          },
        }),
      });
      await settle();

      component.onSubmit();
      await settle();

      expect(text()).toContain(
        "Employee with email 'ana.gomez@acme.example' already exists for this company",
      );
    });

    it('should map a 400 per-field error onto its control', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        saveError: new HttpErrorResponse({
          status: 400,
          error: { message: 'Validation error', errors: { employeeNumber: 'número inválido' } },
        }),
      });
      await settle();

      component.onSubmit();
      await settle();

      expect(text()).toContain('número inválido');
    });
  });

  describe('load and navigation', () => {
    it('should surface a load error when the edit has no company in the URL', async () => {
      setup({ id: 'emp-1', companyId: null });
      await settle();

      expect(employeeService.getEmployee).not.toHaveBeenCalled();
      expect(text()).toContain('No se indicó la empresa del empleado.');
    });

    it('should surface a failed edit load with the HTTP message', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        employeeError: new HttpErrorResponse({ status: 404 }),
      });
      await settle();

      expect(text()).toContain('No se encontró el recurso solicitado');
    });

    it('should navigate back to the list on cancel', async () => {
      setup({ companyId: 'company-1' });
      await settle();

      component.onCancel();

      expect(router.navigate).toHaveBeenCalledWith(['/hr/employees'], {
        queryParams: { companyId: 'company-1' },
      });
    });
  });
});
