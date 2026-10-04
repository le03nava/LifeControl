/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { Company, Page } from '@features/companies/companies/models/company.models';
import { EmployeeDetail } from './employee-detail';
import { EmployeeService } from '../../data/employee.service';
import { ContractService } from '../../data/contract.service';
import { Contract } from '../../models/contract.models';
import { Employee } from '../../models/employee.models';

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

  interface SetupOptions {
    id?: string | null;
    companyId?: string | null;
    employee?: Partial<Employee>;
    employeeError?: HttpErrorResponse;
    contracts?: Contract[];
    contractsError?: HttpErrorResponse;
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

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [EmployeeDetail, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: EmployeeService, useValue: employeeService },
        { provide: ContractService, useValue: contractService },
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
    expect(text()).toContain('Seleccioná una empresa');
  });

  it('should fetch the employee and its contracts for the route id', async () => {
    setup({ id: 'emp-9', companyId: 'company-1' });
    await settle();

    expect(employeeService.getEmployee).toHaveBeenCalledWith('company-1', 'emp-9');
    expect(contractService.getContracts).toHaveBeenCalledWith('company-1', 'emp-9');
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

  it('should offer no write control and no dialog: those are W4b', async () => {
    setup({ id: 'emp-1', companyId: 'company-1' });
    await settle();

    expect(text()).not.toContain('Nuevo contrato');
    expect(text()).not.toContain('Cerrar contrato');
    expect(
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).map((button) =>
        button.textContent?.trim(),
      ),
    ).not.toContain('Nuevo contrato');
  });
});
