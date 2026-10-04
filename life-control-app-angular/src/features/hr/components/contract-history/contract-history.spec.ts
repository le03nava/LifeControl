/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ContractHistory } from './contract-history';
import { Contract } from '../../models/contract.models';

/** Formats a `Date` as the wire's local `YYYY-MM-DD`, independently of the component. */
function isoLocal(date: Date): string {
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/** An ISO date `days` away from today, in local time (month/year rollover included). */
function isoDaysFromToday(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return isoLocal(date);
}

describe('ContractHistory', () => {
  let fixture: ComponentFixture<ContractHistory>;
  let component: ContractHistory;

  const contract = (overrides: Partial<Contract> = {}): Contract => ({
    id: 'contract-1',
    employeeId: 'emp-1',
    positionId: 'pos-1',
    positionName: 'Analista',
    seniorityLevelId: 'level-1',
    seniorityLevelName: 'Junior',
    contractType: 'PERMANENT',
    monthlySalary: 25000,
    startDate: '2024-01-01',
    endDate: null,
    enabled: true,
    ...overrides,
  });

  /**
   * Mounts the component with **no** `HttpClient` and **no** `MatDialog` provider:
   * the presentational component must inject neither, so a hidden dependency
   * would fail here with a `NullInjectorError` instead of passing silently.
   */
  async function setup(contracts: Contract[]): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [ContractHistory] });

    fixture = TestBed.createComponent(ContractHistory);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('contracts', contracts);
    fixture.detectChanges();
    await fixture.whenStable();
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

  function cellsOf(columnIndex: number): string[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll(
        `td.mat-mdc-cell:nth-child(${columnIndex + 1})`,
      ),
    ).map((td) => td.textContent?.trim() ?? '');
  }

  it('should create and render without an HTTP client or a dialog provider', async () => {
    await setup([contract()]);

    expect(component).toBeTruthy();
  });

  it('should render the history columns in the record order', async () => {
    await setup([contract()]);

    expect(headers()).toEqual([
      'Puesto',
      'Nivel',
      'Tipo',
      'Salario mensual',
      'Inicio',
      'Fin',
      'Vigencia',
    ]);
  });

  it('should render the contract data with the Spanish type label', async () => {
    await setup([
      contract({
        positionName: 'Analista',
        seniorityLevelName: 'Junior',
        contractType: 'FIXED_TERM',
        startDate: '2024-01-01',
        endDate: '2025-06-30',
      }),
    ]);

    expect(text()).toContain('Analista');
    expect(text()).toContain('Junior');
    expect(text()).toContain('Plazo fijo');
    expect(text()).not.toContain('FIXED_TERM');
    expect(text()).toContain('2024-01-01');
    expect(text()).toContain('2025-06-30');
  });

  it('should fall back to the raw type when the map does not know it', async () => {
    await setup([contract({ contractType: 'PART_TIME' as Contract['contractType'] })]);

    expect(text()).toContain('PART_TIME');
  });

  it('should format the monthly salary', async () => {
    await setup([contract({ monthlySalary: 25000 })]);

    // The app locale groups thousands; the assertion tolerates either separator.
    expect(cellsOf(3)[0]).toMatch(/25[.,]000/);
  });

  it('should render an em dash for an open-ended contract', async () => {
    await setup([contract({ endDate: null })]);

    expect(cellsOf(5)[0]).toBe('—');
  });

  it('should render the end date when the contract carries one', async () => {
    await setup([contract({ endDate: '2025-06-30' })]);

    expect(cellsOf(5)[0]).toBe('2025-06-30');
  });

  it('should mark an enabled open-ended contract as Vigente', async () => {
    await setup([contract({ enabled: true, endDate: null })]);

    expect(cellsOf(6)[0]).toBe('Vigente');
  });

  it('should mark an enabled contract ending today as Vigente', async () => {
    await setup([contract({ enabled: true, endDate: isoDaysFromToday(0) })]);

    expect(cellsOf(6)[0]).toBe('Vigente');
  });

  it('should mark an enabled contract starting today as Vigente', async () => {
    await setup([contract({ enabled: true, startDate: isoDaysFromToday(0), endDate: null })]);

    expect(cellsOf(6)[0]).toBe('Vigente');
  });

  it('should mark an enabled contract starting tomorrow as Programado', async () => {
    await setup([contract({ enabled: true, startDate: isoDaysFromToday(1), endDate: null })]);

    expect(cellsOf(6)[0]).toBe('Programado');
  });

  it('should give a scheduled contract its own chip class, not the current or closed one', async () => {
    await setup([contract({ enabled: true, startDate: isoDaysFromToday(1), endDate: null })]);

    const badge = (fixture.nativeElement as HTMLElement).querySelector('.badge.validity');
    expect(badge?.classList.contains('scheduled')).toBe(true);
    expect(badge?.classList.contains('current')).toBe(false);
    expect(badge?.classList.contains('closed')).toBe(false);
  });

  it('should mark an enabled contract ending in the future as Vigente', async () => {
    await setup([contract({ enabled: true, endDate: isoDaysFromToday(7) })]);

    expect(cellsOf(6)[0]).toBe('Vigente');
  });

  it('should mark an enabled contract that ended yesterday as Cerrado', async () => {
    await setup([contract({ enabled: true, endDate: isoDaysFromToday(-1) })]);

    expect(cellsOf(6)[0]).toBe('Cerrado');
  });

  it('should mark a disabled contract as Cerrado even when its end date is null', async () => {
    await setup([contract({ enabled: false, endDate: null })]);

    expect(cellsOf(6)[0]).toBe('Cerrado');
  });

  it('should render one row per contract', async () => {
    await setup([
      contract({ id: 'c-2', positionName: 'Gerente' }),
      contract({ id: 'c-1', positionName: 'Analista' }),
    ]);

    expect(cellsOf(0)).toEqual(['Gerente', 'Analista']);
  });

  it('should render the Spanish empty state and no table when there are no contracts', async () => {
    await setup([]);

    expect(text()).toContain('No hay contratos registrados para este empleado');
    expect((fixture.nativeElement as HTMLElement).querySelector('table')).toBeNull();
  });

  it('should render no action button: it neither writes nor opens a dialog', async () => {
    await setup([contract()]);

    expect((fixture.nativeElement as HTMLElement).querySelector('button')).toBeNull();
  });
});
