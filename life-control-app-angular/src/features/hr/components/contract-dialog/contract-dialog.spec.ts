/// <reference types="vitest/globals" />
import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Subject, of, throwError } from 'rxjs';
import { CompanyCountry } from '@features/companies/countries/models/country.models';
import { CompanyRegion } from '@features/companies/regions/models/region.models';
import { CompanyZone } from '@features/companies/zones/models/zone.models';
import { CompanyStore } from '@features/companies/stores/models/store.models';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import {
  ContractDialog,
  ContractDialogData,
  closingDateFor,
  enabledBandFor,
  isSalaryOutsideBand,
} from './contract-dialog';
import { ContractService } from '../../data/contract.service';
import { StoreAssignmentService } from '../../data/store-assignment.service';
import {
  Contract,
  Position,
  PositionSalaryBand,
  SeniorityLevel,
} from '../../models/contract.models';
import { StoreAssignment } from '../../models/store-assignment.models';
import { Employee } from '../../models/employee.models';

/** An ISO date `days` away from today, in local time (month/year rollover included). */
function isoDaysFromToday(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

describe('contract-dialog pure helpers', () => {
  describe('closingDateFor', () => {
    it('returns the day before the start date (T13, the API endDate is inclusive — D14)', () => {
      expect(closingDateFor('2026-03-15')).toBe('2026-03-14');
    });

    it('crosses a month boundary (1st of a month)', () => {
      expect(closingDateFor('2026-03-01')).toBe('2026-02-28');
    });

    it('crosses a year boundary (1 January)', () => {
      expect(closingDateFor('2026-01-01')).toBe('2025-12-31');
    });

    it('crosses a leap-day boundary', () => {
      expect(closingDateFor('2024-03-01')).toBe('2024-02-29');
    });
  });

  describe('enabledBandFor', () => {
    const band = (overrides: Partial<PositionSalaryBand> = {}): PositionSalaryBand => ({
      id: 'band-1',
      positionId: 'pos-1',
      seniorityLevelId: 'level-1',
      minimumSalary: 20000,
      maximumSalary: 30000,
      enabled: true,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
      ...overrides,
    });

    it('returns the enabled band of the pair', () => {
      expect(enabledBandFor([band()], 'level-1')?.id).toBe('band-1');
    });

    it('ignores a disabled band', () => {
      expect(enabledBandFor([band({ enabled: false })], 'level-1')).toBeNull();
    });

    it('returns null for an unmatched level', () => {
      expect(enabledBandFor([band()], 'level-2')).toBeNull();
    });
  });

  describe('isSalaryOutsideBand', () => {
    const band: PositionSalaryBand = {
      id: 'band-1',
      positionId: 'pos-1',
      seniorityLevelId: 'level-1',
      minimumSalary: 20000,
      maximumSalary: 30000,
      enabled: true,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    };

    it('is inside at the minimum and the maximum', () => {
      expect(isSalaryOutsideBand(20000, band)).toBe(false);
      expect(isSalaryOutsideBand(30000, band)).toBe(false);
    });

    it('is outside below the minimum and above the maximum', () => {
      expect(isSalaryOutsideBand(19999, band)).toBe(true);
      expect(isSalaryOutsideBand(30001, band)).toBe(true);
    });
  });
});

describe('ContractDialog', () => {
  let fixture: ComponentFixture<ContractDialog>;
  let component: ContractDialog;
  let dialogRef: { close: ReturnType<typeof vi.fn> };
  let contractService: {
    getPositions: ReturnType<typeof vi.fn>;
    getSeniorityLevels: ReturnType<typeof vi.fn>;
    getSalaryBands: ReturnType<typeof vi.fn>;
    addContract: ReturnType<typeof vi.fn>;
    closeContract: ReturnType<typeof vi.fn>;
  };
  let assignmentService: {
    createAssignment: ReturnType<typeof vi.fn>;
    error: ReturnType<typeof vi.fn>;
  };
  let countryService: { getCountries: ReturnType<typeof vi.fn> };
  let regionService: { getRegions: ReturnType<typeof vi.fn> };
  let zoneService: { getZones: ReturnType<typeof vi.fn> };
  let storeService: { getStores: ReturnType<typeof vi.fn> };

  const companyId = 'company-1';

  const country: CompanyCountry = {
    id: 'company-country-1',
    companyId,
    countryId: 'country-1',
    countryCode: 'MX',
    countryName: 'México',
    localAlias: null,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  /** A second country, so a change of country is a real value change that emits. */
  const otherCountry: CompanyCountry = {
    ...country,
    id: 'company-country-2',
    countryId: 'country-2',
    countryCode: 'ES',
    countryName: 'España',
  };

  const region: CompanyRegion = {
    id: 'region-1',
    companyCountryId: 'company-country-1',
    companyId,
    countryId: 'country-1',
    regionCode: 'CEN',
    regionName: 'Centro',
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  /** A second region, so a change of region is a real value change that emits. */
  const otherRegion: CompanyRegion = {
    ...region,
    id: 'region-2',
    regionCode: 'SUR',
    regionName: 'Sur',
  };

  const zone: CompanyZone = {
    id: 'zone-1',
    companyRegionId: 'region-1',
    companyCountryId: 'company-country-1',
    companyId,
    countryId: 'country-1',
    zoneCode: 'ZN',
    zoneName: 'Zona Norte',
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  /** A second zone, belonging to the second region, so a zone reload is a real value change too. */
  const otherZone: CompanyZone = {
    ...zone,
    id: 'zone-2',
    companyRegionId: 'region-2',
    zoneCode: 'ZS',
    zoneName: 'Zona Sur',
  };

  const store: CompanyStore = {
    id: 'store-1',
    companyId,
    companyCountryId: 'company-country-1',
    regionId: 'region-1',
    zoneId: 'zone-1',
    storeName: 'Tienda Centro',
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    version: 0,
  };

  /** A second store, so a store reload is a real value change. */
  const otherStore: CompanyStore = { ...store, id: 'store-2', storeName: 'Tienda Sur' };

  const createdAssignment: StoreAssignment = {
    id: 'assignment-1',
    companyStoreId: 'store-1',
    companyStoreName: 'Tienda Centro',
    validFrom: '2026-03-01',
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
  };

  const employee: Employee = {
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
  };

  const contract = (overrides: Partial<Contract> = {}): Contract => ({
    id: 'contract-1',
    employeeId: 'emp-1',
    positionId: 'pos-1',
    positionName: 'Analista Senior',
    seniorityLevelId: 'level-1',
    seniorityLevelName: 'Senior',
    contractType: 'PERMANENT',
    monthlySalary: 25000,
    // Long before every entered date in these specs: the predictable current contract
    // whose coverage window contains them (T13 predecessor window).
    startDate: '2025-01-01',
    endDate: null,
    enabled: true,
    ...overrides,
  });

  const position: Position = {
    id: 'pos-1',
    companyId: 'company-1',
    departmentId: 'dept-1',
    positionCode: 'P-001',
    positionName: 'Analista Senior',
    description: null,
    reportsToPositionId: null,
    displayOrder: null,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  const level: SeniorityLevel = {
    id: 'level-1',
    levelCode: 'SR',
    levelName: 'Senior',
    rank: 3,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  const band = (overrides: Partial<PositionSalaryBand> = {}): PositionSalaryBand => ({
    id: 'band-1',
    positionId: 'pos-1',
    seniorityLevelId: 'level-1',
    minimumSalary: 20000,
    maximumSalary: 30000,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  });

  interface SetupOptions {
    data?: Partial<ContractDialogData>;
    positions?: Position[];
    levels?: SeniorityLevel[];
    bands?: PositionSalaryBand[];
    bandsError?: HttpErrorResponse;
    countries?: CompanyCountry[];
    countriesError?: HttpErrorResponse;
    regions?: CompanyRegion[];
    zones?: CompanyZone[];
    stores?: CompanyStore[];
    storesError?: HttpErrorResponse;
    /** What the assignment service's own error signal already holds after a failed write. */
    assignmentServiceError?: string | null;
  }

  function setup(options: SetupOptions = {}): void {
    dialogRef = { close: vi.fn() };
    contractService = {
      getPositions: vi.fn().mockReturnValue(of(options.positions ?? [position])),
      getSeniorityLevels: vi.fn().mockReturnValue(of(options.levels ?? [level])),
      getSalaryBands: vi.fn(() =>
        options.bandsError ? throwError(() => options.bandsError) : of(options.bands ?? [band()]),
      ),
      addContract: vi.fn().mockReturnValue(of(contract({ id: 'contract-new' }))),
      closeContract: vi.fn().mockReturnValue(of(contract({ endDate: '2026-06-30' }))),
    };
    assignmentService = {
      createAssignment: vi.fn().mockReturnValue(of(createdAssignment)),
      error: vi.fn(() => options.assignmentServiceError ?? null),
    };
    countryService = {
      getCountries: vi.fn(() =>
        options.countriesError
          ? throwError(() => options.countriesError)
          : of(options.countries ?? [country, otherCountry]),
      ),
    };
    regionService = {
      getRegions: vi.fn().mockReturnValue(of(options.regions ?? [region, otherRegion])),
    };
    zoneService = {
      // Keyed by region, so a reload for the second region is distinguishable from the first
      // region's list surviving in place.
      getZones: vi.fn((_companyId: string, _companyCountryId: string, regionId: string) =>
        of(options.zones ?? (regionId === 'region-2' ? [otherZone] : [zone])),
      ),
    };
    storeService = {
      getStores: vi.fn(() =>
        options.storesError
          ? throwError(() => options.storesError)
          : of(options.stores ?? [store, otherStore]),
      ),
    };

    const data: ContractDialogData = {
      mode: 'create',
      companyId: 'company-1',
      employee,
      contracts: [contract()],
      ...options.data,
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ContractDialog, NoopAnimationsModule],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: ContractService, useValue: contractService },
        { provide: StoreAssignmentService, useValue: assignmentService },
        { provide: CompanyCountryService, useValue: countryService },
        { provide: CompanyRegionService, useValue: regionService },
        { provide: CompanyZoneService, useValue: zoneService },
        { provide: CompanyStoreService, useValue: storeService },
      ],
    });

    fixture = TestBed.createComponent(ContractDialog);
    component = fixture.componentInstance;
  }

  function settle(): void {
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function has(selector: string): boolean {
    return (fixture.nativeElement as HTMLElement).querySelector(selector) !== null;
  }

  function fillCreate(
    overrides: {
      positionId?: string;
      seniorityLevelId?: string;
      contractType?: string;
      monthlySalary?: number | null;
      startDate?: string;
      endDate?: string;
    } = {},
  ): void {
    component.createForm.patchValue({
      positionId: 'pos-1',
      seniorityLevelId: 'level-1',
      contractType: 'PERMANENT',
      monthlySalary: 25000,
      startDate: '2026-03-01',
      endDate: '',
      ...overrides,
    });
    settle();
  }

  /** Walks the whole optional store cascade the way the operator does, one level at a time. */
  function chooseCascade(): void {
    component.createForm.controls.companyCountryId.setValue('company-country-1');
    component.createForm.controls.regionId.setValue('region-1');
    component.createForm.controls.zoneId.setValue('zone-1');
    component.createForm.controls.companyStoreId.setValue('store-1');
  }

  it('should create', () => {
    setup();
    expect(component).toBeTruthy();
  });

  it('should load the position and seniority-level catalogues on open', () => {
    setup();
    settle();

    expect(contractService.getPositions).toHaveBeenCalledWith('company-1');
    expect(contractService.getSeniorityLevels).toHaveBeenCalled();
  });

  it('should submit the exact create payload shape', () => {
    setup();
    settle();
    fillCreate({ startDate: '2026-03-01', monthlySalary: 25000 });

    component.onCreate();
    settle();

    expect(contractService.addContract).toHaveBeenCalledWith('company-1', 'emp-1', {
      positionId: 'pos-1',
      seniorityLevelId: 'level-1',
      contractType: 'PERMANENT',
      monthlySalary: 25000,
      startDate: '2026-03-01',
      endDate: null,
    });
  });

  it('should carry an optional inclusive end date in the create payload', () => {
    setup();
    settle();
    fillCreate({ startDate: '2026-03-01', endDate: '2026-06-30' });

    component.onCreate();

    expect(contractService.addContract).toHaveBeenCalledWith(
      'company-1',
      'emp-1',
      expect.objectContaining({ startDate: '2026-03-01', endDate: '2026-06-30' }),
    );
  });

  it('should close with the created contract', () => {
    setup();
    settle();
    fillCreate();

    component.onCreate();

    expect(dialogRef.close).toHaveBeenCalledWith({
      outcome: 'created',
      contract: expect.objectContaining({ id: 'contract-new' }),
    });
  });

  it('should mirror the required fields and not submit an incomplete form', () => {
    setup();
    settle();

    component.onCreate();

    expect(contractService.addContract).not.toHaveBeenCalled();
    expect(component.createForm.controls.positionId.touched).toBe(true);
  });

  it('should mirror the server rule end >= start and not submit an inverted range', () => {
    setup();
    settle();
    fillCreate({ startDate: '2026-03-10', endDate: '2026-03-01' });

    expect(component.createForm.hasError('endBeforeStart')).toBe(true);
    component.onCreate();

    expect(contractService.addContract).not.toHaveBeenCalled();
  });

  it('should surface the server message when the write is still rejected', () => {
    setup();
    settle();
    fillCreate();
    contractService.addContract.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { message: 'A Terminated employee cannot open a contract' },
          }),
      ),
    );

    component.onCreate();
    settle();

    expect(text()).toContain('A Terminated employee cannot open a contract');
    expect(dialogRef.close).not.toHaveBeenCalled();
  });

  describe('the effect-before-saving notice (T13, D14)', () => {
    /** A current contract that starts well before every entered date and never ends. */
    const openCurrent = contract({ startDate: '2025-01-01', endDate: null });

    it('names the day before a start date inside a month', () => {
      setup({ data: { contracts: [openCurrent] } });
      settle();
      fillCreate({ startDate: '2026-03-15' });

      expect(component.closesCurrentNotice()).toContain('Esto cierra el contrato vigente');
      expect(component.closesCurrentNotice()).toContain('2026-03-14');
    });

    it('crosses a month boundary (1st of a month)', () => {
      setup({ data: { contracts: [openCurrent] } });
      settle();
      fillCreate({ startDate: '2026-03-01' });

      expect(component.closesCurrentNotice()).toContain('2026-02-28');
    });

    it('crosses a year boundary (1 January)', () => {
      setup({ data: { contracts: [openCurrent] } });
      settle();
      fillCreate({ startDate: '2026-01-01' });

      expect(component.closesCurrentNotice()).toContain('2025-12-31');
    });

    it('shows no notice when there is no current contract', () => {
      setup({ data: { contracts: [contract({ endDate: isoDaysFromToday(-1) })] } });
      settle();
      fillCreate({ startDate: '2026-03-01' });

      expect(component.closesCurrentNotice()).toBeNull();
      expect(text()).not.toContain('Esto cierra el contrato vigente');
    });

    it('shows no closure notice when the start date precedes the current contract start', () => {
      setup({
        data: { contracts: [contract({ startDate: isoDaysFromToday(-1), endDate: null })] },
      });
      settle();
      fillCreate({ startDate: isoDaysFromToday(-5) });

      expect(component.closesCurrentNotice()).toBeNull();
      expect(text()).not.toContain('Esto cierra el contrato vigente');
    });

    it('states the start date is outside the current contract when it precedes its start', () => {
      setup({
        data: { contracts: [contract({ startDate: isoDaysFromToday(-1), endDate: null })] },
      });
      settle();
      fillCreate({ startDate: isoDaysFromToday(-5) });

      expect(component.outsideCurrentNotice()).toContain('fuera del contrato vigente');
      expect(text()).toContain('fuera del contrato vigente');
      // No local mirror of T13: the predecessor the server closes depends on rows this
      // form never loads, so the form stays valid and the write reaches the server.
      expect(component.createForm.controls.startDate.hasError('startsOnOrBeforeCurrent')).toBe(
        false,
      );
      expect(component.createForm.valid).toBe(true);
      component.onCreate();
      expect(contractService.addContract).toHaveBeenCalledTimes(1);
    });

    it('states the start date is outside the current contract when it equals its start', () => {
      setup({
        data: { contracts: [contract({ startDate: isoDaysFromToday(-1), endDate: null })] },
      });
      settle();
      fillCreate({ startDate: isoDaysFromToday(-1) });

      expect(component.outsideCurrentNotice()).toContain('fuera del contrato vigente');
      expect(component.closesCurrentNotice()).toBeNull();
      expect(component.createForm.valid).toBe(true);
    });

    it('shows no closure notice when the start date is after the current contract end', () => {
      setup({
        data: {
          contracts: [contract({ startDate: isoDaysFromToday(-30), endDate: isoDaysFromToday(5) })],
        },
      });
      settle();
      fillCreate({ startDate: isoDaysFromToday(10) });

      expect(component.closesCurrentNotice()).toBeNull();
      expect(text()).not.toContain('Esto cierra el contrato vigente');
    });

    it('states the start date is outside the current contract when it is after its end', () => {
      setup({
        data: {
          contracts: [contract({ startDate: isoDaysFromToday(-30), endDate: isoDaysFromToday(5) })],
        },
      });
      settle();
      fillCreate({ startDate: isoDaysFromToday(10) });

      expect(component.outsideCurrentNotice()).toContain('fuera del contrato vigente');
      expect(text()).toContain('fuera del contrato vigente');
      expect(component.createForm.controls.startDate.hasError('startsOnOrBeforeCurrent')).toBe(
        false,
      );
      expect(component.createForm.valid).toBe(true);
    });

    it('still shows the closure notice for a later start under a null end date', () => {
      setup({ data: { contracts: [openCurrent] } });
      settle();
      fillCreate({ startDate: '2026-03-15' });

      expect(component.closesCurrentNotice()).toContain('2026-03-14');
    });
  });

  describe('the position x level band (T14, warned never blocked)', () => {
    it('reads the position bands once a position is chosen', () => {
      setup();
      settle();
      component.createForm.patchValue({ positionId: 'pos-1' });
      settle();

      expect(contractService.getSalaryBands).toHaveBeenCalledWith('company-1', 'pos-1');
    });

    it('shows the band next to the salary field for the chosen pair', () => {
      setup();
      settle();
      fillCreate();

      expect(has('.band-hint')).toBe(true);
      const hint = (fixture.nativeElement as HTMLElement).querySelector('.band-hint');
      expect(hint?.textContent).toContain('20000');
      expect(hint?.textContent).toContain('30000');
    });

    it('warns when the typed salary is below the band', () => {
      setup();
      settle();
      fillCreate({ monthlySalary: 15000 });

      expect(component.salaryOutOfBand()).toBe(true);
      expect(has('.band-warning')).toBe(true);
      expect(text()).toContain('fuera de la banda');
    });

    it('warns when the typed salary is above the band', () => {
      setup();
      settle();
      fillCreate({ monthlySalary: 45000 });

      expect(component.salaryOutOfBand()).toBe(true);
      expect(text()).toContain('fuera de la banda');
    });

    it('keeps the submit enabled for a salary outside a present band', () => {
      setup();
      settle();
      fillCreate({ monthlySalary: 15000 });

      expect(component.salaryOutOfBand()).toBe(true);
      expect(component.createForm.valid).toBe(true);
      const submit = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
        'button[type="submit"]',
      );
      expect(submit).not.toBeNull();
      expect(submit?.disabled).toBe(false);
    });

    it('does not warn for a salary inside the band', () => {
      setup();
      settle();
      fillCreate({ monthlySalary: 25000 });

      expect(component.salaryOutOfBand()).toBe(false);
      expect(has('.band-warning')).toBe(false);
    });

    it('shows no band and no warning when the pair has no enabled band', () => {
      setup({ bands: [band({ enabled: false })] });
      settle();
      fillCreate({ monthlySalary: 15000 });

      expect(component.selectedBand()).toBeNull();
      expect(component.salaryOutOfBand()).toBe(false);
      expect(has('.band-warning')).toBe(false);
    });

    it('keeps the form usable and invents no warning when the band read fails', () => {
      setup({ bandsError: new HttpErrorResponse({ status: 500 }) });
      settle();
      fillCreate({ monthlySalary: 15000 });

      expect(component.salaryOutOfBand()).toBe(false);
      expect(has('.band-warning')).toBe(false);

      component.onCreate();

      expect(contractService.addContract).toHaveBeenCalledTimes(1);
    });
  });

  describe('close mode', () => {
    const closeData = {
      mode: 'close' as const,
      contracts: [contract({ startDate: '2026-01-01' })],
    };

    it('sends the typed inclusive end date', () => {
      setup({ data: closeData });
      settle();
      component.closeForm.patchValue({ endDate: '2026-05-31' });

      component.onClose();

      expect(contractService.closeContract).toHaveBeenCalledWith(
        'company-1',
        'emp-1',
        'contract-1',
        '2026-05-31',
      );
    });

    it('omits the end date so the server closes today when left empty', () => {
      setup({ data: closeData });
      settle();

      component.onClose();

      expect(contractService.closeContract).toHaveBeenCalledWith(
        'company-1',
        'emp-1',
        'contract-1',
        undefined,
      );
    });

    it('closes with the closed contract', () => {
      setup({ data: closeData });
      settle();

      component.onClose();

      expect(dialogRef.close).toHaveBeenCalledWith({
        outcome: 'closed',
        contract: expect.objectContaining({ id: 'contract-1' }),
      });
    });

    it('mirrors the close-date rule and does not submit a date before the start', () => {
      setup({ data: closeData });
      settle();
      component.closeForm.patchValue({ endDate: '2025-12-31' });

      expect(component.closeForm.hasError('endBeforeStart')).toBe(true);
      component.onClose();

      expect(contractService.closeContract).not.toHaveBeenCalled();
    });
  });

  it('closes without a result when cancelled', () => {
    setup();
    settle();

    component.cancel();

    expect(dialogRef.close).toHaveBeenCalledWith(null);
  });

  describe('the optional store picker (D8, T19, T21)', () => {
    it('loads the company countries on open, with the company already fixed', () => {
      setup();
      settle();

      expect(countryService.getCountries).toHaveBeenCalledWith('company-1');
      expect(component.countries()).toEqual([country, otherCountry]);
    });

    it('walks country -> region -> zone -> store through the four per-level services (T19)', () => {
      setup();
      settle();

      component.createForm.controls.companyCountryId.setValue('company-country-1');
      expect(regionService.getRegions).toHaveBeenCalledWith('company-1', 'company-country-1');
      expect(component.regions()).toEqual([region, otherRegion]);

      component.createForm.controls.regionId.setValue('region-1');
      expect(zoneService.getZones).toHaveBeenCalledWith(
        'company-1',
        'company-country-1',
        'region-1',
      );
      expect(component.zones()).toEqual([zone]);

      component.createForm.controls.zoneId.setValue('zone-1');
      expect(storeService.getStores).toHaveBeenCalledWith(
        'company-1',
        'company-country-1',
        'region-1',
        'zone-1',
      );
      expect(component.stores()).toEqual([store, otherStore]);
    });

    it('clears every level below and the store when the country changes', () => {
      setup();
      settle();
      chooseCascade();
      expect(component.stores()).toEqual([store, otherStore]);

      component.createForm.controls.companyCountryId.setValue('company-country-2');

      expect(component.createForm.controls.regionId.value).toBe('');
      expect(component.createForm.controls.zoneId.value).toBe('');
      expect(component.createForm.controls.companyStoreId.value).toBe('');
      expect(component.zones()).toEqual([]);
      expect(component.stores()).toEqual([]);
      // The new country's own regions replace the previous ones.
      expect(regionService.getRegions).toHaveBeenLastCalledWith('company-1', 'company-country-2');
    });

    it('clears the zone and the store when the region changes', () => {
      setup();
      settle();
      chooseCascade();
      expect(component.zones()).toEqual([zone]);

      component.createForm.controls.regionId.setValue('region-2');

      // The new region's own zones replace the previous ones, so a zone list that survived in place
      // cannot satisfy this.
      expect(component.zones()).toEqual([otherZone]);
      expect(component.stores()).toEqual([]);
      expect(component.createForm.controls.zoneId.value).toBe('');
      expect(component.createForm.controls.companyStoreId.value).toBe('');
    });

    it('clears the store when the zone changes', () => {
      setup();
      settle();
      chooseCascade();

      component.createForm.controls.zoneId.setValue('zone-2');

      expect(component.createForm.controls.companyStoreId.value).toBe('');
      expect(storeService.getStores).toHaveBeenLastCalledWith(
        'company-1',
        'company-country-1',
        'region-1',
        'zone-2',
      );
    });

    it('drops an in-flight regions read when the country is cleared before it resolves (T21)', () => {
      setup();
      settle();
      const pendingRegions = new Subject<CompanyRegion[]>();
      regionService.getRegions.mockReturnValue(pendingRegions.asObservable());

      component.createForm.controls.companyCountryId.setValue('company-country-1');
      settle();
      expect(has('.store-clear')).toBe(true);

      const clear = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
        '.store-clear',
      );
      clear?.click();
      settle();
      expect(component.createForm.controls.companyCountryId.value).toBe('');

      // The read the now-empty parent still owns resolves late; it must not repopulate the level the
      // clear just emptied, or options would show for a level the form no longer walks.
      pendingRegions.next([region]);
      pendingRegions.complete();
      settle();

      expect(component.regions()).toEqual([]);
    });

    it('drops an in-flight zones read when its parent chain is cleared before it resolves (T21)', () => {
      setup();
      settle();
      component.createForm.controls.companyCountryId.setValue('company-country-1');
      settle();

      const pendingZones = new Subject<CompanyZone[]>();
      zoneService.getZones.mockReturnValue(pendingZones.asObservable());
      component.createForm.controls.regionId.setValue('region-1');
      settle();

      const clear = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
        '.store-clear',
      );
      clear?.click();
      settle();
      expect(component.createForm.controls.regionId.value).toBe('');

      pendingZones.next([zone]);
      pendingZones.complete();
      settle();

      expect(component.zones()).toEqual([]);
    });

    it('drops an in-flight stores read when its parent chain is cleared before it resolves (T21)', () => {
      setup();
      settle();
      component.createForm.controls.companyCountryId.setValue('company-country-1');
      settle();
      component.createForm.controls.regionId.setValue('region-1');
      settle();

      const pendingStores = new Subject<CompanyStore[]>();
      storeService.getStores.mockReturnValue(pendingStores.asObservable());
      component.createForm.controls.zoneId.setValue('zone-1');
      settle();

      const clear = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
        '.store-clear',
      );
      clear?.click();
      settle();
      expect(component.createForm.controls.zoneId.value).toBe('');

      pendingStores.next([store]);
      pendingStores.complete();
      settle();

      expect(component.stores()).toEqual([]);
    });

    it('refuses a partly walked cascade as a form error (T21)', () => {
      setup();
      settle();
      fillCreate();
      component.createForm.controls.companyCountryId.setValue('company-country-1');
      settle();

      expect(component.createForm.hasError('storeRequired')).toBe(true);
      expect(component.createForm.valid).toBe(false);
      expect(text()).toContain('Si elegís un nivel de la tienda');

      component.onCreate();

      expect(contractService.addContract).not.toHaveBeenCalled();
      expect(assignmentService.createAssignment).not.toHaveBeenCalled();
    });

    it('refuses a store chosen without its ancestors as the same partly walked cascade (T21)', () => {
      setup();
      settle();
      fillCreate();
      // The other end of the same partial chain: a store with no country/region/zone. The form never
      // showed a chain for it, so the payload must not carry the store (T21's "never a partial chain").
      component.createForm.controls.companyStoreId.setValue('store-1');
      settle();

      expect(component.createForm.hasError('storeRequired')).toBe(true);
      expect(component.createForm.valid).toBe(false);

      component.onCreate();

      expect(contractService.addContract).not.toHaveBeenCalled();
      expect(assignmentService.createAssignment).not.toHaveBeenCalled();
    });

    it('keeps a completely empty cascade a valid contract-only act (D8, T21)', () => {
      setup();
      settle();
      fillCreate();

      expect(component.createForm.hasError('storeRequired')).toBe(false);
      expect(component.createForm.valid).toBe(true);

      component.onCreate();

      expect(contractService.addContract).toHaveBeenCalledTimes(1);
    });

    it('names the failure and still submits the otherwise valid contract when the countries level cannot be read', () => {
      setup({ countriesError: new HttpErrorResponse({ status: 500 }) });
      settle();

      expect(component.countries()).toEqual([]);
      expect(component.catalogueError()).toContain(
        'No se pudieron cargar los niveles de la tienda',
      );
      expect(text()).toContain('No se pudieron cargar los niveles de la tienda');

      // The failed read is the difference: with the cascade unavailable the complete contract form
      // is still valid, so the failure names itself instead of blocking the contract-only act (D8).
      fillCreate();
      expect(component.createForm.valid).toBe(true);
      component.onCreate();
      expect(contractService.addContract).toHaveBeenCalledTimes(1);
    });

    it('clears the store and leaves the cascade partly walked when the stores level cannot be read', () => {
      setup();
      settle();
      fillCreate();
      chooseCascade();
      settle();
      // A complete cascade, so the form is valid before the stores read fails.
      expect(component.createForm.valid).toBe(true);
      expect(component.stores()).toEqual([store, otherStore]);

      storeService.getStores.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );
      component.createForm.controls.zoneId.setValue('zone-2');
      settle();

      // The failed read is the difference: the store list is empty and the cleared store leaves the
      // cascade partly walked, which T21 refuses instead of dropping silently.
      expect(component.stores()).toEqual([]);
      expect(component.createForm.controls.companyStoreId.value).toBe('');
      expect(component.createForm.hasError('storeRequired')).toBe(true);
      expect(component.createForm.valid).toBe(false);
      expect(text()).toContain('No se pudieron cargar los niveles de la tienda');
    });

    it('returns a walked cascade to completely empty through the same reset the level changes run (D8, T21)', () => {
      setup();
      settle();
      fillCreate();
      chooseCascade();
      settle();

      expect(component.stores()).toEqual([store, otherStore]);
      expect(component.createForm.valid).toBe(true);

      const clear = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
        '.store-clear',
      );
      expect(clear).not.toBeNull();
      clear?.click();
      settle();

      expect(component.createForm.controls.companyCountryId.value).toBe('');
      expect(component.createForm.controls.regionId.value).toBe('');
      expect(component.createForm.controls.zoneId.value).toBe('');
      expect(component.createForm.controls.companyStoreId.value).toBe('');
      // The same reset the level changes run: every dependent list is emptied with the controls.
      expect(component.regions()).toEqual([]);
      expect(component.zones()).toEqual([]);
      expect(component.stores()).toEqual([]);
      // Fully empty is the legal contract-only act (D8); it is not a partly walked cascade (T21).
      expect(component.createForm.hasError('storeRequired')).toBe(false);
      expect(component.createForm.valid).toBe(true);
      // Clearing is an in-dialog edit, never a submit.
      expect(contractService.addContract).not.toHaveBeenCalled();
      expect(dialogRef.close).not.toHaveBeenCalled();
    });

    it('offers the clear control only while at least one level is set', () => {
      setup();
      settle();
      expect(has('.store-clear')).toBe(false);

      component.createForm.controls.companyCountryId.setValue('company-country-1');
      settle();
      expect(has('.store-clear')).toBe(true);

      component.createForm.controls.companyCountryId.setValue('');
      settle();
      expect(has('.store-clear')).toBe(false);
    });

    it('hides the clear control after the emitEvent:false reset empties the cascade', () => {
      setup();
      settle();
      fillCreate();
      chooseCascade();
      settle();

      expect(has('.store-clear')).toBe(true);

      const clear = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
        '.store-clear',
      );
      clear?.click();
      settle();

      // This is exactly the path a valueChanges-derived signal would miss: the clear patches with
      // emitEvent:false, so the live form value is empty while the signal would still hold the walk
      // that was just undone. The control must be gone.
      expect(component.createForm.getRawValue()).toMatchObject({
        companyCountryId: '',
        regionId: '',
        zoneId: '',
        companyStoreId: '',
      });
      expect(has('.store-clear')).toBe(false);
    });
  });

  describe('the two-call activation act (T18, T20, D8, D9)', () => {
    it('creates the contract first and then the assignment with the contract startDate (T18, T20)', () => {
      setup();
      settle();
      fillCreate({ startDate: '2026-03-01' });
      const order: string[] = [];
      contractService.addContract.mockImplementation(() => {
        order.push('contract');
        return of(contract({ id: 'contract-new', startDate: '2026-03-01' }));
      });
      assignmentService.createAssignment.mockImplementation(() => {
        order.push('assignment');
        return of(createdAssignment);
      });
      chooseCascade();

      component.onCreate();

      expect(contractService.addContract).toHaveBeenCalledTimes(1);
      expect(assignmentService.createAssignment).toHaveBeenCalledWith('company-1', 'emp-1', {
        companyStoreId: 'store-1',
        validFrom: '2026-03-01',
      });
      // The sequence, not just the presence: the assignment must run after the contract resolves.
      expect(order).toEqual(['contract', 'assignment']);
    });

    it('sends the created contract startDate as the assignment validFrom, verbatim (T20)', () => {
      setup();
      settle();
      fillCreate({ startDate: '2026-03-01' });
      // The server's row is the date's source, so the assignment reads the created contract and not
      // the form: one act, one date, one control.
      contractService.addContract.mockReturnValue(
        of(contract({ id: 'contract-new', startDate: '2026-07-01' })),
      );
      chooseCascade();

      component.onCreate();

      expect(assignmentService.createAssignment).toHaveBeenCalledWith('company-1', 'emp-1', {
        companyStoreId: 'store-1',
        validFrom: '2026-07-01',
      });
    });

    it('creates only the contract and calls no assignment when the picker is empty (D8)', () => {
      setup();
      settle();
      fillCreate();

      component.onCreate();

      expect(contractService.addContract).toHaveBeenCalledTimes(1);
      expect(assignmentService.createAssignment).not.toHaveBeenCalled();
      // Exactly today's result shape: the contract and nothing else.
      expect(dialogRef.close).toHaveBeenCalledWith({
        outcome: 'created',
        contract: expect.objectContaining({ id: 'contract-new' }),
      });
    });

    it('closes with both the contract and the created assignment', () => {
      setup();
      settle();
      fillCreate();
      chooseCascade();

      component.onCreate();

      expect(dialogRef.close).toHaveBeenCalledWith({
        outcome: 'created',
        contract: expect.objectContaining({ id: 'contract-new' }),
        assignment: createdAssignment,
      });
    });

    it('keeps the dialog open, shows its banner and never calls the assignment when the contract fails (T18)', () => {
      setup();
      settle();
      fillCreate();
      chooseCascade();
      contractService.addContract.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: { message: 'A Terminated employee cannot open a contract' },
            }),
        ),
      );

      component.onCreate();
      settle();

      expect(assignmentService.createAssignment).not.toHaveBeenCalled();
      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(text()).toContain('A Terminated employee cannot open a contract');
    });

    it('closes carrying the contract and the assignment error when the assignment write fails (D9)', () => {
      setup();
      settle();
      fillCreate();
      chooseCascade();
      assignmentService.createAssignment.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: { message: 'Ya existe una asignación en esa tienda que se superpone' },
            }),
        ),
      );

      component.onCreate();

      expect(assignmentService.createAssignment).toHaveBeenCalledTimes(1);
      expect(dialogRef.close).toHaveBeenCalledWith(
        expect.objectContaining({
          outcome: 'created',
          assignmentError: 'Ya existe una asignación en esa tienda que se superpone',
        }),
      );
      // Two separate facts: the contract is created, and no assignment is claimed.
      const payload = dialogRef.close.mock.calls[0][0] as Record<string, unknown>;
      expect(payload).not.toHaveProperty('assignment');
      expect(payload['contract']).toMatchObject({ id: 'contract-new' });
    });

    it('falls back to the service message when the assignment failure carries no envelope', () => {
      setup({ assignmentServiceError: 'Ya existe una asignación en esa tienda que se superpone' });
      settle();
      fillCreate();
      chooseCascade();
      assignmentService.createAssignment.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 409 })),
      );

      component.onCreate();

      expect(dialogRef.close).toHaveBeenCalledWith(
        expect.objectContaining({
          outcome: 'created',
          assignmentError: 'Ya existe una asignación en esa tienda que se superpone',
        }),
      );
    });
  });
});
