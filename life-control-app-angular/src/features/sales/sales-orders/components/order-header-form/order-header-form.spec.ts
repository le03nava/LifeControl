import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { OrderHeaderForm } from './order-header-form';
import type { SalesOrder } from '../../models/sales-order.models';

function createSalesOrder(overrides: Partial<SalesOrder> = {}): SalesOrder {
  return {
    id: 'so-1',
    orderNumber: 'SO-00001',
    customerId: 'cust-1',
    customerName: 'John Doe',
    companyStoreId: 'store-1',
    companyStoreName: 'Test Store',
    shiftId: 'shift-1',
    shiftName: 'Morning Shift',
    userId: 'user-1',
    orderDate: '2026-01-01T00:00:00Z',
    statusId: 'st-draft',
    statusName: 'Draft',
    totalAmount: 1200.0,
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    items: [],
    ...overrides,
  };
}

describe('OrderHeaderForm', () => {
  let fixture: ComponentFixture<OrderHeaderForm>;
  let component: OrderHeaderForm;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        OrderHeaderForm,
        NoopAnimationsModule,
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(OrderHeaderForm);
    component = fixture.componentInstance;
  });

  it('should create the component', () => {
    expect(component).toBeTruthy();
  });

  it('should have null currentStatusName when no order loaded', () => {
    fixture.detectChanges();
    expect(component.currentStatusName()).toBeNull();
  });

  it('should reflect loaded order status', () => {
    fixture.componentRef.setInput('loadedOrder', createSalesOrder({ statusName: 'Pending' }));
    fixture.detectChanges();

    expect(component.currentStatusName()).toBe('Pending');
  });

  it('should show order number in template when loaded', () => {
    fixture.componentRef.setInput('loadedOrder', createSalesOrder({ orderNumber: 'SO-00001' }));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('SO-00001');
  });

  it('should show status chip when loaded', () => {
    fixture.componentRef.setInput('loadedOrder', createSalesOrder({ statusName: 'Draft' }));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Draft');
  });
});
