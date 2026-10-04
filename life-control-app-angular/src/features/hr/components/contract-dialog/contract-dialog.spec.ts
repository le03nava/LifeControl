/// <reference types="vitest/globals" />
import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import {
  ContractDialog,
  ContractDialogData,
  closingDateFor,
  enabledBandFor,
  isSalaryOutsideBand,
} from './contract-dialog';
import { ContractService } from '../../data/contract.service';
import {
  Contract,
  Position,
  PositionSalaryBand,
  SeniorityLevel,
} from '../../models/contract.models';
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
});
