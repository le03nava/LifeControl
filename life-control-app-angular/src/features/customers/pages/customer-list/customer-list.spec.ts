import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { HttpErrorResponse } from '@angular/common/http';
import { NEVER, of, throwError } from 'rxjs';
import { CustomerList } from './customer-list';
import { CustomerService } from '../../data/customer.service';
import { Customer, Page } from '../../models/customer.models';

interface CustomerServiceMock {
  getCustomers: ReturnType<typeof vi.fn>;
}

/**
 * `T3` of `odd/tasks/customers-screen.md`: the resource call with its page/size/search, the debounce, the
 * paginator gating, and the empty and error states.
 *
 * The cards carry no action in this unit (`S5`), so nothing here asserts a click: the edit page is the
 * sibling unit's, and a control that navigates nowhere is not built and must not be asserted.
 */
describe('CustomerList', () => {
  let customerService: CustomerServiceMock;

  const mockCustomers: Customer[] = [
    {
      id: 'c-1',
      name: 'Alpha Comercial',
      email: 'alpha@test.com',
      phone: '5551112222',
      rfc: 'RFC010101AB1',
      salesChannel: 'TODOS',
      enabled: true,
      legalName: null,
      regimenFiscal: null,
      fiscalStreet: null,
      fiscalZipCode: null,
      fiscalState: null,
      fiscalCountryId: null,
      defaultUsoCfdi: null,
      createdAt: '2026-11-17T10:00:00',
      updatedAt: '2026-11-17T10:00:00',
    },
    {
      id: 'c-2',
      name: 'Beta Distribuciones',
      email: 'beta@test.com',
      phone: '5553334444',
      rfc: null,
      salesChannel: 'MAYOREO',
      enabled: false,
      legalName: 'Beta Distribuciones SA de CV',
      regimenFiscal: '601',
      fiscalStreet: 'Av. Juárez 20',
      fiscalZipCode: '44100',
      fiscalState: 'Jalisco',
      fiscalCountryId: 'country-1',
      defaultUsoCfdi: 'G03',
      createdAt: '2026-11-16T10:00:00',
      updatedAt: '2026-11-16T10:00:00',
    },
  ];

  const pageOf = (
    content: Customer[],
    overrides: Partial<Page<Customer>> = {},
  ): Page<Customer> => ({
    content,
    totalElements: content.length,
    totalPages: content.length > 0 ? 1 : 0,
    size: 12,
    number: 0,
    first: true,
    last: true,
    empty: content.length === 0,
    ...overrides,
  });

  const render = async (page: Page<Customer>): Promise<ComponentFixture<CustomerList>> => {
    customerService.getCustomers = vi.fn().mockReturnValue(of(page));
    const fixture = TestBed.createComponent(CustomerList);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  };

  beforeEach(async () => {
    customerService = {
      getCustomers: vi.fn().mockReturnValue(of(pageOf(mockCustomers))),
    };

    await TestBed.configureTestingModule({
      imports: [CustomerList, MatIconModule, MatPaginatorModule],
      providers: [provideRouter([]), { provide: CustomerService, useValue: customerService }],
    }).compileComponents();
  });

  it('should create', () => {
    const fixture = TestBed.createComponent(CustomerList);
    expect(fixture.componentInstance).toBeTruthy();
  });

  // ─── the read itself ────────────────────────────────────────────

  it('should read the first page of 12 with no search term on creation', () => {
    const fixture = TestBed.createComponent(CustomerList);
    fixture.detectChanges();

    expect(customerService.getCustomers).toHaveBeenCalledWith(0, 12, undefined);
  });

  it('should debounce the search term and reset the page to the first one', () => {
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(CustomerList);
    fixture.detectChanges();
    expect(customerService.getCustomers).toHaveBeenCalledWith(0, 12, undefined);

    // Walk to the second page first, so the reset has something to undo.
    fixture.componentInstance.onPageChange({ pageIndex: 1, pageSize: 12 });
    fixture.detectChanges();
    expect(fixture.componentInstance.pageIndex()).toBe(1);

    fixture.componentInstance.searchQuery.set('alpha');
    fixture.detectChanges();

    // Nothing is requested mid-typing: the term is still inside the debounce window.
    expect(customerService.getCustomers).not.toHaveBeenCalledWith(1, 12, 'alpha');

    vi.advanceTimersByTime(300);
    fixture.detectChanges();

    expect(fixture.componentInstance.pageIndex()).toBe(0);
    expect(customerService.getCustomers).toHaveBeenCalledWith(0, 12, 'alpha');

    vi.useRealTimers();
  });

  it('should request the page and size the operator picked', () => {
    const fixture = TestBed.createComponent(CustomerList);
    fixture.detectChanges();

    fixture.componentInstance.onPageChange({ pageIndex: 2, pageSize: 24 });
    fixture.detectChanges();

    expect(fixture.componentInstance.pageIndex()).toBe(2);
    expect(fixture.componentInstance.pageSize()).toBe(24);
    expect(customerService.getCustomers).toHaveBeenCalledWith(2, 24, undefined);
  });

  it('should clear the search term', () => {
    const fixture = TestBed.createComponent(CustomerList);
    fixture.detectChanges();

    fixture.componentInstance.searchQuery.set('alpha');
    fixture.componentInstance.clearSearch();

    expect(fixture.componentInstance.searchQuery()).toBe('');
  });

  // ─── the cards ──────────────────────────────────────────────────

  it('should render one card per row, with the row`s own values', async () => {
    const fixture = await render(pageOf(mockCustomers));
    const cards = fixture.nativeElement.querySelectorAll(
      '.card-wrapper',
    ) as NodeListOf<HTMLElement>;

    expect(cards).toHaveLength(2);
    expect(cards[0].textContent).toContain('Alpha Comercial');
    expect(cards[0].textContent).toContain('alpha@test.com');
    expect(cards[0].textContent).toContain('5551112222');
    expect(cards[1].textContent).toContain('Beta Distribuciones');
    expect(cards[1].textContent).toContain('MAYOREO');
  });

  it('should render no action on the cards, because the edit page is the sibling unit`s', async () => {
    const fixture = await render(pageOf(mockCustomers));
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelectorAll('.card-wrapper')).toHaveLength(2);
    expect(el.querySelectorAll('.card-wrapper button')).toHaveLength(0);
    expect(el.querySelectorAll('mat-card-actions')).toHaveLength(0);
  });

  // ─── the three states ───────────────────────────────────────────

  it('should render the loading skeleton while the read is in flight', () => {
    // A stream that never emits keeps the resource in its loading state, so the skeleton is the only
    // thing the three states can be showing.
    customerService.getCustomers = vi.fn().mockReturnValue(NEVER);
    const fixture = TestBed.createComponent(CustomerList);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.loading-skeleton')).toBeTruthy();
  });

  it('should render the search-aware empty state', async () => {
    const fixture = await render(pageOf([]));
    expect(fixture.nativeElement.textContent).toContain('No hay clientes registrados');

    // The title follows what the operator typed, not the debounced term, so it answers immediately.
    fixture.componentInstance.searchQuery.set('zzz');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No se encontraron clientes');
    expect(fixture.nativeElement.textContent).not.toContain('No hay clientes registrados');
  });

  it('should render the error state with a retry action that reads again', async () => {
    customerService.getCustomers = vi
      .fn()
      .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));

    const fixture = TestBed.createComponent(CustomerList);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.error-state')).toBeTruthy();
    expect(el.querySelector('.loading-skeleton')).toBeNull();
    expect(el.textContent).toContain('Tu sesión expiró');

    const callsBeforeRetry = customerService.getCustomers.mock.calls.length;
    const retry = el.querySelector('.error-state button') as HTMLButtonElement;
    expect(retry).toBeTruthy();
    retry.click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(customerService.getCustomers.mock.calls.length).toBeGreaterThan(callsBeforeRetry);
  });

  // ─── the paginator ──────────────────────────────────────────────

  it('should render no paginator when the whole result fits in one page', async () => {
    const fixture = await render(pageOf(mockCustomers, { totalPages: 1 }));
    expect(fixture.nativeElement.querySelector('mat-paginator')).toBeNull();
  });

  it('should render the paginator when there is more than one page', async () => {
    const fixture = await render(
      pageOf(mockCustomers, { totalElements: 30, totalPages: 3, last: false }),
    );
    const paginator = fixture.nativeElement.querySelector('mat-paginator');

    expect(paginator).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.pagination-section')).toBeTruthy();
  });
});
