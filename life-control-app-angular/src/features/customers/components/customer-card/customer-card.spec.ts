import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CustomerCard } from './customer-card';
import { Customer } from '../../models/customer.models';

/**
 * The card's one new obligation (`T2` of `odd/tasks/customers-fiscal-form.md`): it carries the action that
 * finally goes somewhere, and it names the row it is about.
 *
 * The card stays presentational — it emits an id and does not inject `Router` — so the navigation itself is
 * asserted by the list's spec, which is the component that wires the event.
 */
describe('CustomerCard', () => {
  let fixture: ComponentFixture<CustomerCard>;

  const customer: Customer = {
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
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [CustomerCard] }).compileComponents();

    fixture = TestBed.createComponent(CustomerCard);
    fixture.componentRef.setInput('customer', customer);
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders the action that opens the row for editing', () => {
    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll(
      'mat-card-actions button',
    );

    expect(buttons).toHaveLength(1);
    expect(buttons[0].textContent).toContain('Editar');
  });

  it('emits the id of the row it is about, never a navigation', () => {
    const emitted: string[] = [];
    fixture.componentInstance.editCustomer.subscribe((id) => emitted.push(id));

    const button = (fixture.nativeElement as HTMLElement).querySelector(
      'mat-card-actions button',
    ) as HTMLButtonElement;
    button.click();

    expect(emitted).toEqual(['c-1']);
  });
});
