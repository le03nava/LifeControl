/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';
import Keycloak from 'keycloak-js';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { Company, Page } from '@features/companies/companies/models/company.models';
import { EmployeeDetail } from './employee-detail';
import { EmployeeService } from '../../data/employee.service';
import { ContractService } from '../../data/contract.service';
import { Contract } from '../../models/contract.models';
import { Employee } from '../../models/employee.models';
import {
  ContractDialog,
  ContractDialogResult,
} from '../../components/contract-dialog/contract-dialog';
import { StoreAssignmentService } from '../../data/store-assignment.service';
import { StoreAssignment } from '../../models/store-assignment.models';
import {
  StoreAssignmentDialog,
  StoreAssignmentDialogResult,
} from '../../components/store-assignment-dialog/store-assignment-dialog';
import { EMPLOYEE_WRITE_ROLES } from '@core/security/roles';
import { ConfirmDialog } from '@shared/ui';

/** The client roles that reach the employee write endpoints (EMPLOYEE_WRITE_ROLES). */
const WRITE_ROLES = ['lc-admin'];
/** A caller that can read but must not render a write control. */
const READ_ROLES: string[] = [];

/** An ISO date `days` away from today, in local time (month/year rollover included). */
function isoDaysFromToday(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

describe('EmployeeDetail', () => {
  let fixture: ComponentFixture<EmployeeDetail>;
  let component: EmployeeDetail;
  let employeeService: { getEmployee: ReturnType<typeof vi.fn> };
  let contractService: { getContracts: ReturnType<typeof vi.fn> };
  let assignmentService: {
    getAssignments: ReturnType<typeof vi.fn>;
    closeAssignment: ReturnType<typeof vi.fn>;
    error: ReturnType<typeof vi.fn>;
  };
  let dialog: { open: ReturnType<typeof vi.fn> };
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

  const contract = (overrides: Partial<Contract> = {}): Contract => ({
    id: 'contract-1',
    employeeId: 'emp-1',
    positionId: 'pos-1',
    positionName: 'Analista Senior',
    seniorityLevelId: 'level-1',
    seniorityLevelName: 'Senior',
    contractType: 'PERMANENT',
    monthlySalary: 25000,
    startDate: '2024-01-01',
    endDate: null,
    enabled: true,
    ...overrides,
  });

  const assignment = (overrides: Partial<StoreAssignment> = {}): StoreAssignment => ({
    id: 'assignment-1',
    companyStoreId: 'store-1',
    companyStoreName: 'Tienda Centro',
    validFrom: '2024-01-01',
    validTo: null,
    enabled: true,
    derived: {
      companyId: 'company-1',
      companyName: 'Acme Corp',
      companyCountryId: 'company-country-1',
      companyCountryName: 'México',
      companyRegionId: 'region-1',
      companyRegionName: 'Centro',
      companyZoneId: 'zone-1',
      companyZoneName: 'Zona Norte',
    },
    ...overrides,
  });

  interface SetupOptions {
    id?: string | null;
    companyId?: string | null;
    employee?: Partial<Employee>;
    employeeError?: HttpErrorResponse;
    contracts?: Contract[];
    contractsError?: HttpErrorResponse;
    assignments?: StoreAssignment[];
    assignmentsError?: HttpErrorResponse;
    /** What a close write resolves with, or rejects with. */
    closeError?: HttpErrorResponse;
    /** What the assignment service's own error signal holds after a failed write. */
    assignmentWriteError?: string | null;
    roles?: string[];
    /** What the contract dialog closes with; `undefined` is a bare dismissal. */
    dialogResult?: ContractDialogResult | undefined;
    /** What the assign dialog closes with; `undefined` is a bare dismissal. */
    assignmentDialogResult?: StoreAssignmentDialogResult;
    /** What the shared ConfirmDialog closes with. */
    dialogConfirmed?: boolean;
  }

  function setup(options: SetupOptions = {}): void {
    employeeService = {
      getEmployee: vi.fn(() =>
        options.employeeError
          ? throwError(() => options.employeeError)
          : of(employee(options.employee)),
      ),
    };
    contractService = {
      getContracts: vi.fn(() =>
        options.contractsError
          ? throwError(() => options.contractsError)
          : of(options.contracts ?? [contract()]),
      ),
    };
    assignmentService = {
      getAssignments: vi.fn(() =>
        options.assignmentsError
          ? throwError(() => options.assignmentsError)
          : of(options.assignments ?? [assignment()]),
      ),
      closeAssignment: vi.fn(() =>
        options.closeError
          ? throwError(() => options.closeError)
          : of(assignment({ validTo: isoDaysFromToday(0) })),
      ),
      error: vi.fn(() => options.assignmentWriteError ?? null),
    };
    dialog = {
      // Two dialogs share `MatDialog` on this page: the contract/assignment editor and the shared
      // ConfirmDialog. Answer each with what the caller expects to read.
      open: vi.fn((component: unknown) => ({
        afterClosed: () => {
          if (component === ConfirmDialog) {
            return of(options.dialogConfirmed ?? false);
          }
          return component === StoreAssignmentDialog
            ? of(options.assignmentDialogResult)
            : of(options.dialogResult);
        },
      })),
    };

    const roles = options.roles ?? WRITE_ROLES;

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [EmployeeDetail, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: EmployeeService, useValue: employeeService },
        { provide: ContractService, useValue: contractService },
        { provide: StoreAssignmentService, useValue: assignmentService },
        { provide: MatDialog, useValue: dialog },
        {
          provide: Keycloak,
          useValue: { tokenParsed: { resource_access: { 'life-control-client': { roles } } } },
        },
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

    fixture = TestBed.createComponent(EmployeeDetail);
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

  /** The header's current-position cell (the `D15`/`G15` fact only the detail shows). */
  function positionValue(): string {
    return (
      (fixture.nativeElement as HTMLElement)
        .querySelector('.position-value')
        ?.textContent?.trim() ?? ''
    );
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

  it('should issue no read until a company is selected', async () => {
    setup({ id: 'emp-1', companyId: null });
    await settle();

    expect(employeeService.getEmployee).not.toHaveBeenCalled();
    expect(contractService.getContracts).not.toHaveBeenCalled();
    expect(assignmentService.getAssignments).not.toHaveBeenCalled();
    expect(text()).toContain('Seleccioná una empresa');
  });

  it('should fetch the employee, its contracts and its store assignments for the route id', async () => {
    setup({ id: 'emp-9', companyId: 'company-1' });
    await settle();

    expect(employeeService.getEmployee).toHaveBeenCalledWith('company-1', 'emp-9');
    expect(contractService.getContracts).toHaveBeenCalledWith('company-1', 'emp-9');
    // The history is the whole record, so the soft-deleted rows are asked for explicitly.
    expect(assignmentService.getAssignments).toHaveBeenCalledWith('company-1', 'emp-9', true);
  });

  it('should render the employee header data with the Spanish status label', async () => {
    setup({ id: 'emp-1', companyId: 'company-1' });
    await settle();

    expect(text()).toContain('Detalle del empleado');
    expect(text()).toContain('EMP-001');
    expect(text()).toContain('Ana Gómez Ruiz');
    expect(text()).toContain('ana.gomez@acme.example');
    expect(text()).toContain('Activo');
    expect(text()).not.toContain('Active');
  });

  it('should render the position of the current contract in the header', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      contracts: [
        contract({ id: 'c-2', positionName: 'Analista Senior', enabled: true, endDate: null }),
        contract({
          id: 'c-1',
          positionName: 'Analista Junior',
          enabled: true,
          endDate: '2023-12-31',
        }),
      ],
    });
    await settle();

    expect(positionValue()).toBe('Analista Senior');
  });

  it('should render an em dash when no contract is current', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      contracts: [contract({ enabled: true, endDate: '2023-12-31' })],
    });
    await settle();

    expect(positionValue()).toBe('—');
  });

  it('should not supply the header position from a contract that has not started', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      contracts: [contract({ enabled: true, startDate: isoDaysFromToday(1), endDate: null })],
    });
    await settle();

    expect(positionValue()).toBe('—');
    expect(text()).toContain('Programado');
  });

  it('should render the contracts through the contract-history component', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      contracts: [contract({ positionName: 'Analista Senior', seniorityLevelName: 'Senior' })],
    });
    await settle();

    expect(text()).toContain('Contratos');
    expect(text()).toContain('Analista Senior');
    expect(text()).toContain('Senior');
    expect(text()).toContain('Vigente');
  });

  it('should render the empty contract history in Spanish', async () => {
    setup({ id: 'emp-1', companyId: 'company-1', contracts: [] });
    await settle();

    expect(text()).toContain('No hay contratos registrados para este empleado');
  });

  it('should render a distinct not-found state on a 404', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      employeeError: new HttpErrorResponse({ status: 404 }),
    });
    await settle();

    expect(component.notFound()).toBe(true);
    expect(text()).toContain('No se encontró el empleado');
    expect(text()).not.toContain('Ocurrió un error');
    expect(retryButton()).toBeNull();
  });

  it('should return to the list from the not-found state carrying the company', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      employeeError: new HttpErrorResponse({ status: 404 }),
    });
    await settle();

    const button = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((candidate) => candidate.textContent?.trim() === 'Volver al registro');
    expect(button).not.toBeNull();
    button?.click();

    expect(router.navigate).toHaveBeenCalledWith(['/hr/employees'], {
      queryParams: { companyId: 'company-1' },
    });
  });

  it('should render the error state and retry a failed read', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      employeeError: new HttpErrorResponse({ status: 500 }),
    });
    await settle();

    expect(component.notFound()).toBe(false);
    expect(text()).toContain('Ocurrió un error en el servidor');
    expect(text()).not.toContain('No se encontró el empleado');

    employeeService.getEmployee.mockImplementation(() => of(employee()));
    const button = retryButton();
    expect(button).not.toBeNull();
    button?.click();
    await settle();

    expect(text()).toContain('Ana Gómez Ruiz');
  });

  it('should keep the employee card and show the contracts section error when only the contracts read fails', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      contractsError: new HttpErrorResponse({ status: 500 }),
    });
    await settle();

    expect(text()).toContain('Ana Gómez Ruiz');
    expect(text()).toContain('Ocurrió un error en el servidor');
    expect(text()).not.toContain('No se encontró el empleado');
    expect(positionValue()).toBe('—');
    expect(retryButton()).not.toBeNull();
  });

  it('should retry only the contracts read from the section error', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      contractsError: new HttpErrorResponse({ status: 500 }),
    });
    await settle();

    expect(employeeService.getEmployee).toHaveBeenCalledTimes(1);
    expect(contractService.getContracts).toHaveBeenCalledTimes(1);

    contractService.getContracts.mockImplementation(() => of([contract()]));
    const button = retryButton();
    expect(button).not.toBeNull();
    button?.click();
    await settle();

    expect(employeeService.getEmployee).toHaveBeenCalledTimes(1);
    expect(contractService.getContracts).toHaveBeenCalledTimes(2);
    expect(text()).toContain('Analista Senior');
  });

  it('should write a company change back to the URL and re-read', async () => {
    setup({ id: 'emp-1', companyId: 'company-1' });
    await settle();

    component.onCompanyChange('company-2');
    await settle();

    expect(component.selectedCompanyId()).toBe('company-2');
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({ queryParams: { companyId: 'company-2' } }),
    );
    expect(employeeService.getEmployee).toHaveBeenLastCalledWith('company-2', 'emp-1');
  });

  it('should offer no write control and no dialog to a caller without a write role', async () => {
    setup({ id: 'emp-1', companyId: 'company-1', roles: READ_ROLES });
    await settle();

    expect(component.canWrite).toBe(false);
    expect(text()).not.toContain('Nuevo contrato');
    expect(text()).not.toContain('Cerrar contrato vigente');

    component.onNewContract();
    expect(dialog.open).not.toHaveBeenCalled();
  });

  it('should render the contract write actions for a write role', async () => {
    setup({ id: 'emp-1', companyId: 'company-1', roles: WRITE_ROLES });
    await settle();

    expect(component.canWrite).toBe(true);
    expect(text()).toContain('Nuevo contrato');
  });

  it('should open the create dialog with the employee, the contracts and the company', async () => {
    setup({ id: 'emp-1', companyId: 'company-1', roles: WRITE_ROLES, dialogResult: null });
    await settle();

    component.onNewContract();

    expect(dialog.open).toHaveBeenCalledTimes(1);
    const [opened, config] = dialog.open.mock.calls[0] as [unknown, { data: unknown }];
    expect(opened).toBe(ContractDialog);
    expect(config.data).toMatchObject({
      mode: 'create',
      companyId: 'company-1',
      employee: expect.objectContaining({ id: 'emp-1' }),
    });
  });

  it('should reload the contracts after a created contract', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      roles: WRITE_ROLES,
      dialogResult: { outcome: 'created', contract: contract({ id: 'contract-new' }) },
    });
    await settle();
    expect(contractService.getContracts).toHaveBeenCalledTimes(1);

    component.onNewContract();
    await settle();

    expect(contractService.getContracts).toHaveBeenCalledTimes(2);
    expect(employeeService.getEmployee).toHaveBeenCalledTimes(1);
  });

  it('should reload the contracts after a closed contract', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      roles: WRITE_ROLES,
      dialogResult: { outcome: 'closed', contract: contract() },
    });
    await settle();
    expect(contractService.getContracts).toHaveBeenCalledTimes(1);

    component.onCloseContract();
    await settle();

    expect(contractService.getContracts).toHaveBeenCalledTimes(2);
  });

  it('should treat an undefined dialog close as no write', async () => {
    setup({ id: 'emp-1', companyId: 'company-1', roles: WRITE_ROLES, dialogResult: undefined });
    await settle();
    expect(contractService.getContracts).toHaveBeenCalledTimes(1);

    component.onNewContract();
    await settle();

    expect(contractService.getContracts).toHaveBeenCalledTimes(1);
  });

  it('should tell the truth when the contract was created but the store assignment failed (D9)', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      roles: WRITE_ROLES,
      dialogResult: {
        outcome: 'created',
        contract: contract({ id: 'contract-new' }),
        assignmentError: 'Ya existe una asignación en esa tienda que se superpone',
      },
    });
    await settle();
    expect(contractService.getContracts).toHaveBeenCalledTimes(1);
    expect(assignmentService.getAssignments).toHaveBeenCalledTimes(1);

    component.onNewContract();
    await settle();

    expect(contractService.getContracts).toHaveBeenCalledTimes(2);
    expect(assignmentService.getAssignments).toHaveBeenCalledTimes(2);
    expect(text()).toContain('El contrato quedó creado');
    expect(text()).toContain('Ya existe una asignación en esa tienda que se superpone');
  });

  it('should reload both sections when the act created the contract and the assignment (D8)', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      roles: WRITE_ROLES,
      dialogResult: {
        outcome: 'created',
        contract: contract({ id: 'contract-new' }),
        assignment: assignment({ id: 'assignment-new' }),
      },
    });
    await settle();
    expect(contractService.getContracts).toHaveBeenCalledTimes(1);
    expect(assignmentService.getAssignments).toHaveBeenCalledTimes(1);

    component.onNewContract();
    await settle();

    expect(contractService.getContracts).toHaveBeenCalledTimes(2);
    expect(assignmentService.getAssignments).toHaveBeenCalledTimes(2);
    expect(text()).not.toContain('El contrato quedó creado');
  });

  it('should reload only the contracts when the picker was empty and no assignment was in the act (D8)', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      roles: WRITE_ROLES,
      dialogResult: { outcome: 'created', contract: contract({ id: 'contract-new' }) },
    });
    await settle();
    expect(assignmentService.getAssignments).toHaveBeenCalledTimes(1);

    component.onNewContract();
    await settle();

    expect(contractService.getContracts).toHaveBeenCalledTimes(2);
    expect(assignmentService.getAssignments).toHaveBeenCalledTimes(1);
    expect(text()).not.toContain('El contrato quedó creado');
  });

  it('should clear the partial message once the W3a retry assigns the store', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      roles: WRITE_ROLES,
      dialogResult: {
        outcome: 'created',
        contract: contract({ id: 'contract-new' }),
        assignmentError: 'Ya existe una asignación en esa tienda que se superpone',
      },
    });
    await settle();
    component.onNewContract();
    await settle();
    expect(text()).toContain('El contrato quedó creado');

    // The retry path D9 names: the assign dialog of the `Tiendas asignadas` section.
    assignmentService.getAssignments.mockImplementation(() => of([assignment()]));
    dialog.open.mockImplementation((opened: unknown) => ({
      afterClosed: () => of(opened === ConfirmDialog ? false : { outcome: 'created' }),
    }));
    component.onAssign();
    await settle();

    expect(text()).not.toContain('El contrato quedó creado');
  });

  it('should reveal "Cerrar contrato vigente" only with a current contract', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      roles: WRITE_ROLES,
      contracts: [contract({ endDate: isoDaysFromToday(-1) })],
    });
    await settle();

    expect(component.currentContract()).toBeNull();
    expect(text()).not.toContain('Cerrar contrato vigente');
  });

  it('should open the close dialog in close mode for the current contract', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      roles: WRITE_ROLES,
      contracts: [contract({ id: 'contract-current' })],
      dialogResult: null,
    });
    await settle();

    expect(text()).toContain('Cerrar contrato vigente');
    component.onCloseContract();

    expect(dialog.open).toHaveBeenCalledTimes(1);
    const [, config] = dialog.open.mock.calls[0] as [unknown, { data: unknown }];
    expect(config.data).toMatchObject({ mode: 'close', companyId: 'company-1' });
  });

  describe('store assignments', () => {
    it('should render the section with the store, the validity and the derived chain', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', assignments: [assignment()] });
      await settle();

      expect(text()).toContain('Tiendas asignadas');
      expect(text()).toContain('Tienda Centro');
      expect(text()).toContain('2024-01-01 – Sin fecha de fin');
      expect(text()).toContain('Acme Corp / México / Centro / Zona Norte');
      expect(text()).toContain('Abierta');
    });

    it('should render the empty assignments state in Spanish', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', assignments: [] });
      await settle();

      expect(text()).toContain('No hay tiendas asignadas para este empleado');
    });

    it('should keep the rest of the page standing when only the assignments read fails', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        assignmentsError: new HttpErrorResponse({ status: 500 }),
      });
      await settle();

      expect(text()).toContain('Ana Gómez Ruiz');
      expect(text()).toContain('Analista Senior');
      expect(text()).toContain('Ocurrió un error en el servidor');
      expect(retryButton()).not.toBeNull();
    });

    it('should retry only the assignments read from the section error', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        assignmentsError: new HttpErrorResponse({ status: 500 }),
      });
      await settle();

      expect(employeeService.getEmployee).toHaveBeenCalledTimes(1);
      expect(contractService.getContracts).toHaveBeenCalledTimes(1);
      expect(assignmentService.getAssignments).toHaveBeenCalledTimes(1);

      assignmentService.getAssignments.mockImplementation(() => of([assignment()]));
      retryButton()?.click();
      await settle();

      expect(employeeService.getEmployee).toHaveBeenCalledTimes(1);
      expect(contractService.getContracts).toHaveBeenCalledTimes(1);
      expect(assignmentService.getAssignments).toHaveBeenCalledTimes(2);
      expect(text()).toContain('Tienda Centro');
    });

    it('should offer no assignment write control to a caller without a write role', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', roles: READ_ROLES });
      await settle();

      expect(text()).not.toContain('Asignar tienda');
      expect(text()).not.toContain('Cerrar');

      component.onAssign();
      component.onCloseAssignment(assignment());
      expect(dialog.open).not.toHaveBeenCalled();
    });

    it('should open the assign dialog with the company and the employee, and no store tree', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: WRITE_ROLES,
        assignmentDialogResult: null,
      });
      await settle();

      expect(text()).toContain('Asignar tienda');
      component.onAssign();

      expect(dialog.open).toHaveBeenCalledTimes(1);
      const [opened, config] = dialog.open.mock.calls[0] as [unknown, { data: unknown }];
      expect(opened).toBe(StoreAssignmentDialog);
      // T16: the company arrives as an input, so the dialog needs nothing else to walk the tree.
      expect(config.data).toEqual({ companyId: 'company-1', employeeId: 'emp-1' });
    });

    it('should reload the assignments after a created assignment and nothing else', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: WRITE_ROLES,
        assignmentDialogResult: {
          outcome: 'created',
          assignment: assignment({ id: 'assignment-new' }),
        },
      });
      await settle();
      expect(assignmentService.getAssignments).toHaveBeenCalledTimes(1);

      component.onAssign();
      await settle();

      expect(assignmentService.getAssignments).toHaveBeenCalledTimes(2);
      expect(assignmentService.closeAssignment).not.toHaveBeenCalled();
      expect(contractService.getContracts).toHaveBeenCalledTimes(1);
      expect(employeeService.getEmployee).toHaveBeenCalledTimes(1);
    });

    it('should treat an undefined assign dialog close as no write', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: WRITE_ROLES,
        assignmentDialogResult: undefined,
      });
      await settle();

      component.onAssign();
      await settle();

      expect(assignmentService.getAssignments).toHaveBeenCalledTimes(1);
    });

    it('should ask the shared ConfirmDialog before closing an assignment', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: WRITE_ROLES,
        dialogConfirmed: false,
      });
      await settle();

      component.onCloseAssignment(assignment({ id: 'assignment-9' }));
      await settle();

      expect(dialog.open).toHaveBeenCalledWith(
        ConfirmDialog,
        expect.objectContaining({
          data: expect.objectContaining({
            title: 'Cerrar asignación de tienda',
            confirmLabel: 'Cerrar asignación',
            destructive: true,
          }),
        }),
      );
      // Dismissed: nothing is written and nothing is reloaded.
      expect(assignmentService.closeAssignment).not.toHaveBeenCalled();
      expect(assignmentService.getAssignments).toHaveBeenCalledTimes(1);
    });

    it('should close the assignment after the confirmation and reload the list', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: WRITE_ROLES,
        dialogConfirmed: true,
      });
      await settle();

      component.onCloseAssignment(assignment({ id: 'assignment-9' }));
      await settle();

      expect(assignmentService.closeAssignment).toHaveBeenCalledWith(
        'company-1',
        'emp-1',
        'assignment-9',
      );
      expect(assignmentService.getAssignments).toHaveBeenCalledTimes(2);
      expect(contractService.getContracts).toHaveBeenCalledTimes(1);
    });

    it('should show the mapped service message when the close fails and not reload', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: WRITE_ROLES,
        dialogConfirmed: true,
        closeError: new HttpErrorResponse({ status: 409 }),
        assignmentWriteError: 'La asignación ya está cerrada',
      });
      await settle();

      component.onCloseAssignment(assignment());
      await settle();

      expect(text()).toContain('La asignación ya está cerrada');
      expect(assignmentService.getAssignments).toHaveBeenCalledTimes(1);
    });
  });
});

/**
 * T17's pin: the store-assignment write actions gate on `EMPLOYEE_WRITE_ROLES`, and that list must
 * stay equal to the `@PreAuthorize` pair of the controller's two write routes.
 *
 * This is the `KeycloakClaimMapperCoverageTest` idiom — the coupling is read from the backend source
 * instead of restated here, so the two truths cannot drift silently: a new role on the endpoints, a
 * narrower pair, or a change to the UI constant fails below instead of shipping a button whose call
 * is refused with a 403. The controller expresses the pair through `Roles` constants, so those are
 * resolved from `Roles.java` rather than assumed.
 *
 * The filesystem is reached through `process.getBuiltinModule('fs')` and paths relative to the
 * Angular project root, exactly as `shared/constants/breakpoints.spec.ts` does, because the runner
 * bundles the specs for the browser platform where a static `node:*` import cannot resolve.
 */
describe('store assignment write-role pin (T17)', () => {
  const CONTROLLER_SOURCE =
    '../life-control-api/src/main/java/com/lifecontrol/api/hr/controller/EmployeeStoreAssignmentController.java';
  const ROLES_SOURCE =
    '../life-control-api/src/main/java/com/lifecontrol/api/common/security/Roles.java';

  function readSource(path: string): string {
    const fs = process.getBuiltinModule('fs');
    if (!fs.existsSync(path)) {
      throw new Error(
        `Cannot read ${path} from the working directory ${process.cwd()}. ` +
          'Run the tests from the Angular project root with the repository root checked out.',
      );
    }
    return fs.readFileSync(path, 'utf8');
  }

  /** The `static final String NAME = "lc-…";` pairs of `Roles.java`, the one indirection there is. */
  function roleConstants(): Map<string, string> {
    const constants = new Map<string, string>();
    for (const match of readSource(ROLES_SOURCE).matchAll(/String\s+(\w+)\s*=\s*"([^"]+)"/g)) {
      constants.set(match[1], match[2]);
    }
    return constants;
  }

  /**
   * Every `@PreAuthorize` of the controller, resolved to the expression the JVM evaluates: the
   * annotation is a Java string concatenation of literals and role constants, so both are rebuilt.
   */
  function preAuthorizeExpressions(): string[] {
    const constants = roleConstants();
    return readSource(CONTROLLER_SOURCE)
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.startsWith('@PreAuthorize('))
      .map((line) => line.slice('@PreAuthorize('.length, line.lastIndexOf(')')))
      .map((body) =>
        body
          .split('+')
          .map((part) => {
            const literal = /^"(.*)"$/.exec(part.trim());
            if (literal) {
              return literal[1];
            }
            const identifier = /^(\w+)$/.exec(part.trim());
            return (identifier && constants.get(identifier[1])) ?? part.trim();
          })
          .join(''),
      );
  }

  it('should resolve the controller role constants to the two client roles', () => {
    const constants = roleConstants();

    expect(constants.get('ADMIN')).toBe('lc-admin');
    expect(constants.get('EMPLOYEE')).toBe('lc-employee');
  });

  it('should pin EMPLOYEE_WRITE_ROLES to the pair the two write endpoints require', () => {
    const expressions = preAuthorizeExpressions();
    const writes = expressions.filter((expression) => expression.includes('hasAnyRole'));

    // GET is `isAuthenticated()`; POST and PATCH …/close share the write pair.
    expect(expressions).toContain('isAuthenticated()');
    expect(writes).toHaveLength(2);

    for (const write of writes) {
      const pair = [...write.matchAll(/'([^']+)'/g)].map((match) => match[1]).sort();
      expect(pair).toEqual(['lc-admin', 'lc-employee']);
      expect(pair).toEqual([...EMPLOYEE_WRITE_ROLES].sort());
    }
  });
});
