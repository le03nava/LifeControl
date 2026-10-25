/// <reference types="vitest/globals" />
import { ANIMATION_MODULE_TYPE } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
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
import { ScopeAssignmentService } from '../../data/scope-assignment.service';
import { ScopeAssignmentsSection } from '../../components/scope-assignments-section/scope-assignments-section';
import { ScopeAssignment } from '../../models/scope-assignment.models';
import { AccessProvisioningService } from '../../data/access-provisioning.service';
import {
  AccessProvisioningOverview,
  AccessProvisioningTaskView,
} from '../../models/access-provisioning.models';
import {
  AccessReviewDialog,
  AccessReviewDialogResult,
} from '../../components/access-review-dialog/access-review-dialog';
import { AccessSection } from '../../components/access-section/access-section';
import {
  ScopeAssignmentDialog,
  ScopeAssignmentDialogResult,
} from '../../components/scope-assignment-dialog/scope-assignment-dialog';
import { EMPLOYEE_WRITE_ROLES } from '@core/security/roles';
import { ConfirmDialog } from '@shared/ui';

/** The client roles that reach the employee write endpoints (EMPLOYEE_WRITE_ROLES). */
const WRITE_ROLES = ['lc-admin'];
/** A caller that can read but must not render a write control. */
const READ_ROLES: string[] = [];
/** The read pair the access endpoints require (ACCESS_READ_ROLES). */
const ACCESS_ROLES = ['lc-employee-access'];
/** The caller's own `sub`, compared against a task's `requestedBy` for the self-approval rule (O3). */
const CALLER_SUB = 'caller-sub';

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
  let companyService: {
    getCompanySelector: ReturnType<typeof vi.fn>;
    getCompanyById: ReturnType<typeof vi.fn>;
  };
  let contractService: { getContracts: ReturnType<typeof vi.fn> };
  let accessService: {
    getOverview: ReturnType<typeof vi.fn>;
    apply: ReturnType<typeof vi.fn>;
    deactivate: ReturnType<typeof vi.fn>;
    retry: ReturnType<typeof vi.fn>;
    resend: ReturnType<typeof vi.fn>;
    approve: ReturnType<typeof vi.fn>;
    reject: ReturnType<typeof vi.fn>;
    error: ReturnType<typeof vi.fn>;
  };
  let scopeAssignmentService: {
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
    emailDomain: 'acme.example',
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

  /** One row of the merged scope history (S2); a zone assignment by default. */
  const scopeAssignment = (overrides: Partial<ScopeAssignment> = {}): ScopeAssignment => ({
    id: 'scope-assignment-1',
    level: 'ZONE',
    companyCountryId: 'company-country-1',
    companyRegionId: 'region-1',
    companyZoneId: 'zone-1',
    companyStoreId: null,
    nodeName: 'Zona Norte',
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

  const accessTask = (
    overrides: Partial<AccessProvisioningTaskView> = {},
  ): AccessProvisioningTaskView => ({
    id: 'task-1',
    kind: 'ACTIVATE',
    status: 'APPROVAL_PENDING',
    attempts: 0,
    maxAttempts: 3,
    lastError: null,
    nextAttemptAt: null,
    requestedBy: 'requester-sub',
    requestedByName: 'Marta Revisora',
    requestedAt: '2026-10-14T10:00:00',
    decidedBy: null,
    decidedAt: null,
    enabled: true,
    ...overrides,
  });

  const accessOverview = (
    overrides: Partial<AccessProvisioningOverview> = {},
  ): AccessProvisioningOverview => ({
    keycloakUserId: null,
    accountLinked: true,
    requiredRoles: ['lc-employee'],
    currentRoles: ['lc-employee'],
    roleDiff: { added: [], removed: [] },
    frozenRoleDiff: null,
    openTask: null,
    history: [],
    claims: {
      companyId: ['company-1'],
      companyCountryId: [],
      companyRegionId: [],
      companyZoneId: [],
      companyStoreId: [],
    },
    ...overrides,
  });

  interface SetupOptions {
    id?: string | null;
    companyId?: string | null;
    /** The `?tab=` the deep link carries; absent models a bare `/hr/employees/:id`. */
    tab?: string | null;
    employee?: Partial<Employee>;
    employeeError?: HttpErrorResponse;
    contracts?: Contract[];
    contractsError?: HttpErrorResponse;
    roles?: string[];
    /** What the contract dialog closes with; `undefined` is a bare dismissal. */
    dialogResult?: ContractDialogResult | undefined;
    /** The employee's merged scope history, or the error its read fails with. */
    scopeAssignments?: ScopeAssignment[];
    scopeAssignmentsError?: HttpErrorResponse;
    /** What the scope close write resolves with, or rejects with. */
    scopeCloseError?: HttpErrorResponse;
    /** What the scope service's own error signal holds after a failed close. */
    scopeWriteError?: string | null;
    /** What the scope assign dialog closes with; `undefined` is a bare dismissal. */
    scopeAssignmentDialogResult?: ScopeAssignmentDialogResult;
    /** What the shared ConfirmDialog closes with. */
    dialogConfirmed?: boolean;
    /** The employee's access overview, or the error its read fails with. */
    accessOverview?: AccessProvisioningOverview;
    accessError?: HttpErrorResponse;
    /** What every access write resolves with, or rejects with. */
    accessWriteOverview?: AccessProvisioningOverview;
    accessWriteError?: HttpErrorResponse;
    /** What the access service's own error signal holds after a failed write. */
    accessServiceError?: string | null;
    /** What the review dialog closes with; `undefined` is a bare dismissal. */
    accessDialogResult?: AccessReviewDialogResult;
    /** The caller's own `sub`; `null` models a token that carries none. */
    sub?: string | null;
    /**
     * Overrides merged onto the single company the id-addressed company read returns. Used to model
     * a company with no deliverable `emailDomain` (T68/T85); the default carries one.
     */
    company?: Partial<Company>;
    /** The error the id-addressed company read fails with, leaving the domain unresolved. */
    companyError?: HttpErrorResponse;
  }

  function setup(options: SetupOptions = {}): void {
    companyService = {
      getCompanySelector: vi.fn().mockReturnValue(
        of({
          ...companiesPage,
          content: [
            { id: company.id, companyKey: company.companyKey, companyName: company.companyName },
          ],
        }),
      ),
      getCompanyById: vi.fn(() =>
        options.companyError
          ? throwError(() => options.companyError)
          : of({ ...company, ...options.company }),
      ),
    };
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
    scopeAssignmentService = {
      getAssignments: vi.fn(() =>
        options.scopeAssignmentsError
          ? throwError(() => options.scopeAssignmentsError)
          : of(options.scopeAssignments ?? [scopeAssignment()]),
      ),
      closeAssignment: vi.fn(() =>
        options.scopeCloseError
          ? throwError(() => options.scopeCloseError)
          : of(scopeAssignment({ validTo: isoDaysFromToday(0) })),
      ),
      error: vi.fn(() => options.scopeWriteError ?? null),
    };
    const writeResult = () =>
      options.accessWriteError
        ? throwError(() => options.accessWriteError)
        : of(options.accessWriteOverview ?? accessOverview());
    accessService = {
      getOverview: vi.fn(() =>
        options.accessError
          ? throwError(() => options.accessError)
          : of(options.accessOverview ?? accessOverview()),
      ),
      apply: vi.fn(writeResult),
      deactivate: vi.fn(writeResult),
      retry: vi.fn(writeResult),
      resend: vi.fn(writeResult),
      approve: vi.fn(writeResult),
      reject: vi.fn(writeResult),
      error: vi.fn(() => options.accessServiceError ?? null),
    };
    dialog = {
      // Three dialogs share `MatDialog` on this page: the contract/assignment editor, the shared
      // ConfirmDialog and the access review dialog. Answer each with what the caller expects.
      open: vi.fn((component: unknown) => ({
        afterClosed: () => {
          if (component === ConfirmDialog) {
            return of(options.dialogConfirmed ?? false);
          }
          if (component === ScopeAssignmentDialog) {
            return of(options.scopeAssignmentDialogResult);
          }
          if (component === AccessReviewDialog) {
            return of(options.accessDialogResult);
          }
          return of(options.dialogResult);
        },
      })),
    };

    const roles = options.roles ?? WRITE_ROLES;

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [EmployeeDetail],
      providers: [
        provideRouter([]),
        // `NoopAnimationsModule` went away with `@angular/animations` in v22, and what it did for
        // Material's tab group was set this token to `NoopAnimations`. Without it `MatTabBody`
        // waits for a real CSS `transitionstart` (with a 100ms fallback timer) before it attaches
        // the selected tab's content, and jsdom never fires one, so the tab body stayed empty.
        { provide: ANIMATION_MODULE_TYPE, useValue: 'NoopAnimations' },
        { provide: EmployeeService, useValue: employeeService },
        { provide: ContractService, useValue: contractService },
        { provide: ScopeAssignmentService, useValue: scopeAssignmentService },
        { provide: AccessProvisioningService, useValue: accessService },
        { provide: MatDialog, useValue: dialog },
        {
          provide: Keycloak,
          useValue: {
            tokenParsed: {
              sub: options.sub === undefined ? CALLER_SUB : options.sub,
              resource_access: { 'life-control-client': { roles } },
            },
          },
        },
        {
          provide: CompanyService,
          useValue: companyService,
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap(options.id ? { id: options.id } : {}),
              queryParamMap: convertToParamMap({
                ...(options.companyId ? { companyId: options.companyId } : {}),
                ...(options.tab ? { tab: options.tab } : {}),
              }),
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

  /**
   * Activates one tab the way the operator does.
   *
   * Material attaches a tab's body only while that tab is active, so a test that reads a section
   * other than `Detalle` has to open it first: the page text of a closed tab is not in the DOM, and
   * asserting it would read the active tab alone.
   */
  async function openTab(label: string): Promise<void> {
    const header = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('[role="tab"]'),
    ).find(
      (tab) => (tab.querySelector('.mdc-tab__text-label')?.textContent ?? '').trim() === label,
    );
    if (!header) {
      throw new Error(`No tab labelled "${label}"`);
    }
    header.click();
    await settle();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function has(selector: string): boolean {
    return (fixture.nativeElement as HTMLElement).querySelector(selector) !== null;
  }

  function button(label: string): HTMLButtonElement | undefined {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (candidate) => candidate.textContent?.trim() === label,
    );
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
    expect(scopeAssignmentService.getAssignments).not.toHaveBeenCalled();
    expect(accessService.getOverview).not.toHaveBeenCalled();
    expect(text()).toContain('Seleccioná una empresa');
  });

  it('should fetch the employee, its contracts and its single scope-assignment history', async () => {
    setup({ id: 'emp-9', companyId: 'company-1' });
    await settle();

    expect(employeeService.getEmployee).toHaveBeenCalledWith('company-1', 'emp-9');
    expect(contractService.getContracts).toHaveBeenCalledWith('company-1', 'emp-9');
    // S1/S2: the store is a level of the one scope history, so the merged tab is fed by one read,
    // asked for whole (includeDisabled) because a soft-deleted row is part of the record.
    expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(1);
    expect(scopeAssignmentService.getAssignments).toHaveBeenCalledWith('company-1', 'emp-9', true);
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
    await openTab('Contratos');
    expect(text()).toContain('Programado');
  });

  it('should render the contracts through the contract-history component', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      contracts: [contract({ positionName: 'Analista Senior', seniorityLevelName: 'Senior' })],
    });
    await settle();
    await openTab('Contratos');

    expect(text()).toContain('Analista Senior');
    expect(text()).toContain('Senior');
    expect(text()).toContain('Vigente');
  });

  it('should render the empty contract history in Spanish', async () => {
    setup({ id: 'emp-1', companyId: 'company-1', contracts: [] });
    await settle();
    await openTab('Contratos');

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

    // Detalle keeps the employee standing: the failed contracts read never reached it.
    expect(text()).toContain('Ana Gómez Ruiz');
    expect(positionValue()).toBe('—');

    // Contratos shows the failure and offers its own retry.
    await openTab('Contratos');
    expect(text()).toContain('Ocurrió un error en el servidor');
    expect(text()).not.toContain('No se encontró el empleado');
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
    await openTab('Contratos');
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

  /**
   * The tab layout: one tab per section, and every write control inside the tab that owns the act.
   *
   * Material attaches only the active tab's body, so each assertion opens the tab it reads and then
   * checks that tab's own body — "the button is somewhere on the screen" would otherwise pass for a
   * button parked in another tab.
   */
  describe('section tabs', () => {
    function element(): HTMLElement {
      return fixture.nativeElement as HTMLElement;
    }

    function tabHeaders(): HTMLElement[] {
      return Array.from(element().querySelectorAll<HTMLElement>('[role="tab"]'));
    }

    function tabLabels(): string[] {
      return tabHeaders().map((tab) =>
        (tab.querySelector('.mdc-tab__text-label')?.textContent ?? '').trim(),
      );
    }

    function activeTabLabel(): string | null {
      const active = element().querySelector<HTMLElement>('[role="tab"][aria-selected="true"]');
      return active
        ? (active.querySelector('.mdc-tab__text-label')?.textContent ?? '').trim()
        : null;
    }

    /** The body of the tab at `index`, in tab order, whichever one is active. */
    function tabBody(index: number): HTMLElement {
      return Array.from(element().querySelectorAll<HTMLElement>('mat-tab-body'))[index]!;
    }

    function tabButton(index: number, label: string): HTMLButtonElement | undefined {
      return Array.from(tabBody(index).querySelectorAll('button')).find(
        (candidate) => candidate.textContent?.trim() === label,
      );
    }

    const ACCESS_WRITER = [...WRITE_ROLES, ...ACCESS_ROLES];

    it('should render the four section tabs in the order the page declares them', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', roles: ACCESS_WRITER });
      await settle();

      // S1: `Tiendas` and `Alcance` are one tab, so the list lost a member instead of gaining one.
      expect(tabLabels()).toEqual(['Detalle', 'Contratos', 'Alcance', 'Accesos']);
    });

    it('should render each section inside its own tab', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: ACCESS_WRITER,
        accessOverview: accessOverview(),
      });
      await settle();

      expect(tabBody(0).querySelector('.employee-card')).not.toBeNull();
      // The grid of employee data is not repeated across tabs: it lives in Detalle alone.
      expect(tabBody(1).querySelector('.employee-card')).toBeNull();

      await openTab('Contratos');
      expect(tabBody(1).querySelector('app-contract-history')).not.toBeNull();

      await openTab('Alcance');
      expect(tabBody(2).querySelector('app-scope-assignments-section')).not.toBeNull();

      await openTab('Accesos');
      expect(tabBody(3).querySelector('app-access-section')).not.toBeNull();
    });

    it('should keep the contract write actions in the Contratos tab', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: ACCESS_WRITER,
        contracts: [contract({ endDate: null })],
      });
      await settle();
      await openTab('Contratos');

      expect(tabButton(1, 'Nuevo contrato')).toBeDefined();
      expect(tabButton(1, 'Cerrar contrato vigente')).toBeDefined();
      // The page header no longer carries the write actions: they follow the act into its tab.
      const header = element().querySelector('app-page-header') as HTMLElement;
      expect(Array.from(header.querySelectorAll('button'))).toHaveLength(0);
    });

    it('should offer Editar and Volver al registro in the Detalle tab', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', roles: WRITE_ROLES });
      await settle();

      expect(tabButton(0, 'Editar')).toBeDefined();
      expect(tabButton(0, 'Volver al registro')).toBeDefined();
    });

    it('should not offer Editar to a caller without a write role, and keep Volver al registro', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', roles: READ_ROLES });
      await settle();

      expect(tabButton(0, 'Editar')).toBeUndefined();
      expect(tabButton(0, 'Volver al registro')).toBeDefined();
    });

    it('should open the edit route carrying the company from the Detalle tab', async () => {
      setup({ id: 'emp-7', companyId: 'company-1', roles: WRITE_ROLES });
      await settle();

      tabButton(0, 'Editar')?.click();
      await settle();

      expect(router.navigate).toHaveBeenCalledWith(['/hr/employees/edit', 'emp-7'], {
        queryParams: { companyId: 'company-1' },
      });
    });

    it('should render no Accesos tab and issue no access read without ACCESS_READ_ROLES', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', roles: READ_ROLES });
      await settle();

      expect(tabLabels()).toEqual(['Detalle', 'Contratos', 'Alcance']);
      expect(accessService.getOverview).not.toHaveBeenCalled();
    });

    it('should open the Detalle tab when no ?tab= is given', async () => {
      setup({ id: 'emp-1', companyId: 'company-1' });
      await settle();

      expect(activeTabLabel()).toBe('Detalle');
    });

    it('should open Alcance for the pre-merge ?tab=tiendas deep link', async () => {
      // S1: `tiendas` stopped being a tab, so the deep link has to land on the tab that can act
      // instead of falling back to Detalle.
      setup({ id: 'emp-1', companyId: 'company-1', tab: 'tiendas' });
      await settle();

      expect(activeTabLabel()).toBe('Alcance');
    });

    it('should open the tab named by ?tab=', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', tab: 'contratos' });
      await settle();

      expect(activeTabLabel()).toBe('Contratos');
    });

    it('should fall back to Detalle for an unknown ?tab=', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', tab: 'no-existe' });
      await settle();

      expect(activeTabLabel()).toBe('Detalle');
    });

    it('should fall back to Detalle for ?tab=accesos without an access read role', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', roles: READ_ROLES, tab: 'accesos' });
      await settle();

      expect(activeTabLabel()).toBe('Detalle');
    });

    it('should write the selected tab into ?tab= and preserve the company', async () => {
      setup({ id: 'emp-1', companyId: 'company-1' });
      await settle();

      tabHeaders()[1]?.click();
      await settle();

      expect(router.navigate).toHaveBeenCalledWith([], {
        relativeTo: TestBed.inject(ActivatedRoute),
        queryParams: { tab: 'contratos' },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
    });
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
    await openTab('Contratos');
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
    expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(1);

    component.onNewContract();
    await settle();

    expect(contractService.getContracts).toHaveBeenCalledTimes(2);
    expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(2);
    await openTab('Contratos');
    expect(text()).toContain('El contrato quedó creado');
    expect(text()).toContain('Ya existe una asignación en esa tienda que se superpone');
    // S9: the retry path the message points at is the merged tab, not the retired `Tiendas` one.
    expect(text()).toContain('desde la pestaña «Alcance»');
    expect(text()).not.toContain('«Tiendas»');
  });

  it('should reload both sections when the act created the contract and the assignment (D8)', async () => {
    setup({
      id: 'emp-1',
      companyId: 'company-1',
      roles: WRITE_ROLES,
      dialogResult: {
        outcome: 'created',
        contract: contract({ id: 'contract-new' }),
        assignment: scopeAssignment({ id: 'scope-assignment-new', level: 'STORE' }),
      },
    });
    await settle();
    expect(contractService.getContracts).toHaveBeenCalledTimes(1);
    expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(1);

    component.onNewContract();
    await settle();

    expect(contractService.getContracts).toHaveBeenCalledTimes(2);
    expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(2);
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
    expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(1);

    component.onNewContract();
    await settle();

    expect(contractService.getContracts).toHaveBeenCalledTimes(2);
    expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(1);
    expect(text()).not.toContain('El contrato quedó creado');
  });

  it('should clear the partial message once the merged assign dialog writes the store', async () => {
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
    await openTab('Contratos');
    expect(text()).toContain('El contrato quedó creado');

    // The retry path S9 names: the assign dialog of the `Alcance` tab.
    scopeAssignmentService.getAssignments.mockImplementation(() => of([scopeAssignment()]));
    dialog.open.mockImplementation((opened: unknown) => ({
      afterClosed: () => of(opened === ConfirmDialog ? false : { outcome: 'created' }),
    }));
    component.onAssignScope();
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
    await openTab('Contratos');
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
    await openTab('Contratos');

    expect(text()).toContain('Cerrar contrato vigente');
    component.onCloseContract();

    expect(dialog.open).toHaveBeenCalledTimes(1);
    const [, config] = dialog.open.mock.calls[0] as [unknown, { data: unknown }];
    expect(config.data).toMatchObject({ mode: 'close', companyId: 'company-1' });
  });

  describe('scope assignments', () => {
    it('should render the merged section with the company root, the level, the node, the validity and the derived chain', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', scopeAssignments: [scopeAssignment()] });
      await settle();
      await openTab('Alcance');

      // S3: the company is the derived root of the tree, rendered and never assigned.
      expect(text()).toContain('Empresa');
      expect(text()).toContain('Zona');
      expect(text()).toContain('Zona Norte');
      expect(text()).toContain('2024-01-01 – Sin fecha de fin');
      expect(text()).toContain('Acme Corp / México / Centro / Zona Norte');
      expect(text()).toContain('Abierta');
    });

    it('should render a store row in the one table, because the store is a level of Alcance', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        scopeAssignments: [
          scopeAssignment({
            id: 'scope-assignment-store',
            level: 'STORE',
            companyStoreId: 'store-1',
            nodeName: 'Tienda Centro',
          }),
        ],
      });
      await settle();
      await openTab('Alcance');

      expect(text()).toContain('Tienda');
      expect(text()).toContain('Tienda Centro');
    });

    it('should say that no level is assigned yet instead of only that there are no rows', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', scopeAssignments: [] });
      await settle();
      await openTab('Alcance');

      expect(text()).toContain('ningún nivel de alcance asignado');
    });

    it('should keep the rest of the page standing when only the scope read fails', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        scopeAssignmentsError: new HttpErrorResponse({ status: 500 }),
      });
      await settle();

      // The other tabs still stand: the employee card and the contract history are untouched.
      expect(text()).toContain('Ana Gómez Ruiz');
      await openTab('Contratos');
      expect(text()).toContain('Analista Senior');

      // Alcance shows the failure and offers its own retry.
      await openTab('Alcance');
      expect(text()).toContain('Ocurrió un error en el servidor');
      expect(retryButton()).not.toBeNull();
    });

    it('should retry only the scope read from the section error', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        scopeAssignmentsError: new HttpErrorResponse({ status: 500 }),
      });
      await settle();

      expect(employeeService.getEmployee).toHaveBeenCalledTimes(1);
      expect(contractService.getContracts).toHaveBeenCalledTimes(1);
      expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(1);

      scopeAssignmentService.getAssignments.mockImplementation(() => of([scopeAssignment()]));
      await openTab('Alcance');
      retryButton()?.click();
      await settle();

      expect(employeeService.getEmployee).toHaveBeenCalledTimes(1);
      expect(contractService.getContracts).toHaveBeenCalledTimes(1);
      expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(2);
      expect(text()).toContain('Zona Norte');
    });

    it('should pass the selected company name to the section as the derived root', async () => {
      setup({ id: 'emp-1', companyId: 'company-1' });
      await settle();
      await openTab('Alcance');

      const section = fixture.debugElement.query(By.directive(ScopeAssignmentsSection))
        .componentInstance as ScopeAssignmentsSection;
      expect(section.companyName()).toBe('Acme Corp');
    });

    it('should offer no scope write control to a caller without a write role', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', roles: READ_ROLES });
      await settle();
      await openTab('Alcance');

      expect(text()).not.toContain('Asignar alcance');

      component.onAssignScope();
      component.onCloseScopeAssignment(scopeAssignment());
      expect(dialog.open).not.toHaveBeenCalled();
    });

    it('should open the assign dialog with the fixed company and the employee role hint', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: [...WRITE_ROLES, ...ACCESS_ROLES],
        accessOverview: accessOverview({
          requiredRoles: ['lc-employee', 'lc-company-region'],
          currentRoles: ['lc-employee'],
        }),
        scopeAssignmentDialogResult: null,
      });
      await settle();
      await openTab('Alcance');

      expect(text()).toContain('Asignar alcance');
      component.onAssignScope();

      expect(dialog.open).toHaveBeenCalledTimes(1);
      const [opened, config] = dialog.open.mock.calls[0] as [unknown, { data: unknown }];
      expect(opened).toBe(ScopeAssignmentDialog);
      expect(config.data).toEqual({
        companyId: 'company-1',
        companyName: 'Acme Corp',
        employeeId: 'emp-1',
        // The employee's own role names, used only to preselect the level: not a gate.
        employeeRoles: ['lc-employee', 'lc-company-region'],
      });
    });

    it('should pass no role hint when the caller cannot read the access overview', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: WRITE_ROLES,
        // The setup hands the page a successful access overview by default, so the condition this
        // case names needs the read to actually fail: without it the hint would be the fixture's
        // own `currentRoles` and the assertion would pass for the wrong reason.
        accessError: new HttpErrorResponse({ status: 500 }),
        scopeAssignmentDialogResult: null,
      });
      await settle();

      component.onAssignScope();

      const [, config] = dialog.open.mock.calls[0] as [
        unknown,
        { data: { employeeRoles: unknown } },
      ];
      // No access read means no role names: the dialog offers the four levels with no hint.
      expect(config.data.employeeRoles).toEqual([]);
    });

    it('should reload the scope assignments after a created assignment and nothing else', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: WRITE_ROLES,
        scopeAssignmentDialogResult: {
          outcome: 'created',
          assignment: scopeAssignment({ id: 'scope-assignment-new' }),
        },
      });
      await settle();
      expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(1);

      component.onAssignScope();
      await settle();

      expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(2);
      expect(scopeAssignmentService.closeAssignment).not.toHaveBeenCalled();
      expect(contractService.getContracts).toHaveBeenCalledTimes(1);
    });

    it('should treat an undefined assign dialog close as no write', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: WRITE_ROLES,
        scopeAssignmentDialogResult: undefined,
      });
      await settle();

      component.onAssignScope();
      await settle();

      expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(1);
    });

    it('should ask the shared ConfirmDialog before closing a scope assignment', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: WRITE_ROLES,
        dialogConfirmed: false,
      });
      await settle();

      component.onCloseScopeAssignment(scopeAssignment({ id: 'scope-assignment-9' }));
      await settle();

      expect(dialog.open).toHaveBeenCalledWith(
        ConfirmDialog,
        expect.objectContaining({
          data: expect.objectContaining({
            title: 'Cerrar alcance asignado',
            confirmLabel: 'Cerrar asignación',
            destructive: true,
          }),
        }),
      );
      // Dismissed: nothing is written and nothing is reloaded.
      expect(scopeAssignmentService.closeAssignment).not.toHaveBeenCalled();
      expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(1);
    });

    it('should close the scope assignment after the confirmation and reload the list', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', roles: WRITE_ROLES, dialogConfirmed: true });
      await settle();

      component.onCloseScopeAssignment(scopeAssignment({ id: 'scope-assignment-9' }));
      await settle();

      expect(scopeAssignmentService.closeAssignment).toHaveBeenCalledWith(
        'company-1',
        'emp-1',
        'scope-assignment-9',
      );
      expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(2);
    });

    it('should show the mapped service message when the scope close fails and not reload', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: WRITE_ROLES,
        dialogConfirmed: true,
        scopeCloseError: new HttpErrorResponse({ status: 409 }),
        scopeWriteError: 'La asignación ya está cerrada',
      });
      await settle();

      component.onCloseScopeAssignment(scopeAssignment());
      await settle();
      await openTab('Alcance');

      expect(text()).toContain('La asignación ya está cerrada');
      expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(1);
    });
  });

  describe('access provisioning', () => {
    it('should not read the overview nor render the section for a caller without ACCESS_READ_ROLES (T96)', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', roles: ['lc-employee'] });
      await settle();

      expect(accessService.getOverview).not.toHaveBeenCalled();
      expect(has('app-access-section')).toBe(false);
      // The rest of the page is untouched by the gate.
      expect(text()).toContain('Ana Gómez Ruiz');
    });

    it('should read the overview and render the section for ACCESS_READ_ROLES', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', roles: ACCESS_ROLES });
      await settle();

      expect(accessService.getOverview).toHaveBeenCalledWith('company-1', 'emp-1');
      await openTab('Accesos');
      expect(has('app-access-section')).toBe(true);
      expect(text()).toContain('Cuenta vinculada');
    });

    it('should keep the other sections standing and show the access error with its retry when only the access read fails', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        accessError: new HttpErrorResponse({ status: 500 }),
      });
      await settle();

      // The other tabs still stand: the employee card and the contracts are untouched.
      expect(text()).toContain('Ana Gómez Ruiz');
      await openTab('Contratos');
      expect(text()).toContain('Analista Senior');

      // Accesos shows the failure and offers its own retry.
      await openTab('Accesos');
      expect(text()).toContain('Ocurrió un error en el servidor');
      expect(retryButton()).not.toBeNull();
    });

    it('should retry only the access read from the section error', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        accessError: new HttpErrorResponse({ status: 500 }),
      });
      await settle();

      expect(employeeService.getEmployee).toHaveBeenCalledTimes(1);
      expect(contractService.getContracts).toHaveBeenCalledTimes(1);
      expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(1);
      expect(accessService.getOverview).toHaveBeenCalledTimes(1);

      accessService.getOverview.mockImplementation(() => of(accessOverview()));
      await openTab('Accesos');
      retryButton()?.click();
      await settle();

      expect(employeeService.getEmployee).toHaveBeenCalledTimes(1);
      expect(contractService.getContracts).toHaveBeenCalledTimes(1);
      expect(scopeAssignmentService.getAssignments).toHaveBeenCalledTimes(1);
      expect(accessService.getOverview).toHaveBeenCalledTimes(2);
      expect(text()).toContain('Cuenta vinculada');
    });

    it('should call apply and adopt the answered overview without a second read', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        accessWriteOverview: accessOverview({ requiredRoles: ['lc-employee', 'lc-salary'] }),
      });
      await settle();
      await openTab('Accesos');

      button('Solicitar acceso')?.click();
      await settle();

      expect(accessService.apply).toHaveBeenCalledWith('company-1', 'emp-1');
      expect(accessService.getOverview).toHaveBeenCalledTimes(1);
      expect(text()).toContain('lc-salary');
    });

    it('should confirm the revocation before revoking anything (destructive)', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', dialogConfirmed: false });
      await settle();
      await openTab('Accesos');

      button('Deshabilitar')?.click();
      await settle();

      expect(dialog.open).toHaveBeenCalledWith(
        ConfirmDialog,
        expect.objectContaining({
          data: expect.objectContaining({ destructive: true }),
        }),
      );
      expect(accessService.deactivate).not.toHaveBeenCalled();
    });

    it('should revoke only after the confirmation', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', dialogConfirmed: true });
      await settle();
      await openTab('Accesos');

      button('Deshabilitar')?.click();
      await settle();

      expect(accessService.deactivate).toHaveBeenCalledWith('company-1', 'emp-1');
    });

    it('should retry a failed open task', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        accessOverview: accessOverview({ openTask: accessTask({ status: 'FAILED' }) }),
      });
      await settle();
      await openTab('Accesos');

      button('Reintentar solicitud')?.click();
      await settle();

      expect(accessService.retry).toHaveBeenCalledWith('company-1', 'emp-1');
    });

    it('should re-send the invitation for a linked account', async () => {
      setup({ id: 'emp-1', companyId: 'company-1' });
      await settle();
      await openTab('Accesos');

      button('Reenviar invitación')?.click();
      await settle();

      expect(accessService.resend).toHaveBeenCalledWith('company-1', 'emp-1');
    });

    it('should pass the employee’s status and the selected company’s domain to the section', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: ACCESS_ROLES,
        employee: { statusName: 'Active' },
        company: { emailDomain: 'acme.example' },
      });
      await settle();
      await openTab('Accesos');

      const section = fixture.debugElement.query(By.directive(AccessSection))
        .componentInstance as AccessSection;
      expect(section.employeeStatusName()).toBe('Active');
      expect(section.companyEmailDomain()).toBe('acme.example');
    });

    it('should hide apply for a terminated employee and state the reason (T84)', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: ACCESS_ROLES,
        employee: { statusName: 'Terminated' },
      });
      await settle();
      await openTab('Accesos');

      expect(button('Solicitar acceso')).toBeUndefined();
      expect(button('Reenviar invitación')).toBeUndefined();
      expect(text()).toContain('dado de baja');
    });

    it('should hide apply and resend when the company has no email domain, and state the reason (T68/T85)', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: ACCESS_ROLES,
        company: { emailDomain: null as unknown as string },
      });
      await settle();
      await openTab('Accesos');

      expect(button('Solicitar acceso')).toBeUndefined();
      expect(button('Reenviar invitación')).toBeUndefined();
      expect(button('Deshabilitar')).toBeDefined();
      expect(text()).toContain('dominio de correo');
    });

    it('should read the selected company’s domain from the id-addressed company read', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', roles: ACCESS_ROLES });
      await settle();
      await openTab('Accesos');

      const section = fixture.debugElement.query(By.directive(AccessSection))
        .componentInstance as AccessSection;
      expect(companyService.getCompanyById).toHaveBeenCalledWith('company-1');
      expect(section.companyEmailDomain()).toBe('acme.example');
    });

    it('should keep apply and resend when the company read fails (domain unresolved)', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        roles: ACCESS_ROLES,
        companyError: new HttpErrorResponse({ status: 403 }),
      });
      await settle();
      await openTab('Accesos');

      // An unknown domain is not a missing one: the gate must not hide the controls.
      expect(button('Solicitar acceso')).toBeDefined();
      expect(button('Reenviar invitación')).toBeDefined();
    });

    it('should offer apply and resend when the employee is active and the company has a domain', async () => {
      setup({ id: 'emp-1', companyId: 'company-1', roles: ACCESS_ROLES });
      await settle();
      await openTab('Accesos');

      expect(button('Solicitar acceso')).toBeDefined();
      expect(button('Reenviar invitación')).toBeDefined();
    });

    it('should hide the decision control on the caller’s own request (O3/T95)', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        sub: CALLER_SUB,
        accessOverview: accessOverview({
          openTask: accessTask({ status: 'APPROVAL_PENDING', requestedBy: CALLER_SUB }),
        }),
      });
      await settle();
      await openTab('Accesos');

      expect(button('Revisar solicitud')).toBeUndefined();
    });

    it('should offer the review control for somebody else’s pending request', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        sub: CALLER_SUB,
        accessOverview: accessOverview({
          openTask: accessTask({
            id: 'task-9',
            status: 'APPROVAL_PENDING',
            requestedBy: 'requester-sub',
          }),
        }),
      });
      await settle();
      await openTab('Accesos');

      expect(button('Revisar solicitud')).toBeDefined();
    });

    it('should open the review dialog with the overview, the caller sub and the employee identity (T95)', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        sub: CALLER_SUB,
        accessDialogResult: { decision: 'approve' },
        accessOverview: accessOverview({
          openTask: accessTask({
            id: 'task-9',
            status: 'APPROVAL_PENDING',
            requestedBy: 'requester-sub',
          }),
        }),
      });
      await settle();
      await openTab('Accesos');

      button('Revisar solicitud')?.click();
      await settle();

      const [opened, config] = dialog.open.mock.calls.at(-1) as [unknown, { data: unknown }];
      expect(opened).toBe(AccessReviewDialog);
      expect(config.data).toMatchObject({
        currentUserId: CALLER_SUB,
        employeeNumber: 'EMP-001',
        employeeName: 'Ana Gómez Ruiz',
        overview: expect.objectContaining({
          openTask: expect.objectContaining({ id: 'task-9' }),
        }),
      });
    });

    it('should approve and adopt the answered overview on an approve result', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        sub: CALLER_SUB,
        accessDialogResult: { decision: 'approve' },
        accessWriteOverview: accessOverview({ requiredRoles: ['lc-employee', 'lc-salary'] }),
        accessOverview: accessOverview({
          openTask: accessTask({
            id: 'task-9',
            status: 'APPROVAL_PENDING',
            requestedBy: 'requester-sub',
          }),
        }),
      });
      await settle();
      await openTab('Accesos');

      button('Revisar solicitud')?.click();
      await settle();

      expect(accessService.approve).toHaveBeenCalledWith('company-1', 'emp-1', 'task-9');
      expect(accessService.reject).not.toHaveBeenCalled();
      // The answered overview is the new state, so no second read is issued.
      expect(accessService.getOverview).toHaveBeenCalledTimes(1);
      expect(text()).toContain('lc-salary');
    });

    it('should reject with the reason on a reject result', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        sub: CALLER_SUB,
        accessDialogResult: { decision: 'reject', reason: 'El puesto no corresponde' },
        accessOverview: accessOverview({
          openTask: accessTask({
            id: 'task-9',
            status: 'APPROVAL_PENDING',
            requestedBy: 'requester-sub',
          }),
        }),
      });
      await settle();
      await openTab('Accesos');

      button('Revisar solicitud')?.click();
      await settle();

      expect(accessService.reject).toHaveBeenCalledWith('company-1', 'emp-1', 'task-9', {
        reason: 'El puesto no corresponde',
      });
      expect(accessService.approve).not.toHaveBeenCalled();
    });

    it('should treat a bare review dismissal as no write', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        sub: CALLER_SUB,
        accessDialogResult: undefined,
        accessOverview: accessOverview({
          openTask: accessTask({
            id: 'task-9',
            status: 'APPROVAL_PENDING',
            requestedBy: 'requester-sub',
          }),
        }),
      });
      await settle();
      await openTab('Accesos');

      button('Revisar solicitud')?.click();
      await settle();

      expect(accessService.approve).not.toHaveBeenCalled();
      expect(accessService.reject).not.toHaveBeenCalled();
    });

    it('should surface the server’s named 409 message on a failed decision, not the generic copy', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        sub: CALLER_SUB,
        accessDialogResult: { decision: 'approve' },
        accessWriteError: new HttpErrorResponse({
          status: 409,
          error: { message: 'No podés aprobar tu propia solicitud' },
        }),
        accessOverview: accessOverview({
          openTask: accessTask({
            id: 'task-9',
            status: 'APPROVAL_PENDING',
            requestedBy: 'requester-sub',
          }),
        }),
      });
      await settle();
      await openTab('Accesos');

      button('Revisar solicitud')?.click();
      await settle();

      expect(text()).toContain('No podés aprobar tu propia solicitud');
      expect(text()).not.toContain('El dato cambió mientras lo editabas');
    });

    it('should fall back to the access service message when the failure carries no envelope', async () => {
      setup({
        id: 'emp-1',
        companyId: 'company-1',
        accessDialogResult: { decision: 'approve' },
        accessWriteError: new HttpErrorResponse({ status: 409 }),
        accessServiceError:
          'No podés aprobar tu propia solicitud o la solicitud no espera aprobación',
        accessOverview: accessOverview({
          openTask: accessTask({
            id: 'task-9',
            status: 'APPROVAL_PENDING',
            requestedBy: 'requester-sub',
          }),
        }),
      });
      await settle();
      await openTab('Accesos');

      button('Revisar solicitud')?.click();
      await settle();

      expect(text()).toContain(
        'No podés aprobar tu propia solicitud o la solicitud no espera aprobación',
      );
    });
  });
});

/**
 * The write-role pin: the scope write actions gate on `EMPLOYEE_WRITE_ROLES`, and that list must
 * stay equal to the `@PreAuthorize` pair of `EmployeeScopeAssignmentController`'s two write routes.
 *
 * The pin follows the write surface the app actually calls. It used to read
 * `EmployeeStoreAssignmentController`, whose route this unit retires from the frontend, so keeping it
 * there would have pinned a button nobody renders against an endpoint nobody calls.
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
describe('scope assignment write-role pin (S7)', () => {
  const CONTROLLER_SOURCE =
    '../life-control-api/src/main/java/com/lifecontrol/api/hr/controller/EmployeeScopeAssignmentController.java';
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
