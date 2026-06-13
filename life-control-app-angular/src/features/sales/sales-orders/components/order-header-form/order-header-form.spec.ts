import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { OrderHeaderForm } from './order-header-form';
import { ConfigService } from '@app/services/config.service';
import type { SalesOrder, CustomerOption } from '../../models/sales-order.models';
import type { SalesOrderHeaderControl } from '../../models/sales-order-control.models';

const TEST_API = 'http://test/api';

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

function createHeaderForm(): FormGroup<SalesOrderHeaderControl> {
  return new FormGroup({
    customerId: new FormControl('', { validators: Validators.required, nonNullable: true }),
    companyStoreId: new FormControl('', { nonNullable: true }),
    shiftId: new FormControl('', { nonNullable: true }),
    comments: new FormControl<string | null>(null),
  }) as FormGroup<SalesOrderHeaderControl>;
}

const mockShifts = [
  { id: 'shift-1', companyStoreId: 'store-1', companyStoreName: 'Main Store',
    userId: 'user-1', openedAt: '2026-01-01T08:00:00Z', status: 'Open' },
];

describe('OrderHeaderForm', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        OrderHeaderForm,
        ReactiveFormsModule,
        NoopAnimationsModule,
        HttpClientTestingModule,
      ],
      providers: [
        { provide: ConfigService, useValue: { apiUrl: TEST_API } },
      ],
      schemas: [CUSTOM_ELEMENTS_SCHEMA],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  /** Creates fixture with required input and flushes init requests. */
  function createFixture(
    overrides?: { isEditMode?: boolean; isDraft?: boolean; loadedOrder?: SalesOrder | null; serverErrors?: Record<string, string>; storeName?: string | null },
  ): { fixture: ComponentFixture<OrderHeaderForm>; comp: OrderHeaderForm } {
    const fixture = TestBed.createComponent(OrderHeaderForm);
    const comp = fixture.componentInstance;

    fixture.componentRef.setInput('headerForm', createHeaderForm());
    if (overrides?.isEditMode !== undefined) {
      fixture.componentRef.setInput('isEditMode', overrides.isEditMode);
    }
    if (overrides?.isDraft !== undefined) {
      fixture.componentRef.setInput('isDraft', overrides.isDraft);
    }
    if (overrides?.loadedOrder !== undefined) {
      fixture.componentRef.setInput('loadedOrder', overrides.loadedOrder);
    }
    if (overrides?.serverErrors !== undefined) {
      fixture.componentRef.setInput('serverErrors', overrides.serverErrors);
    }
    if (overrides?.storeName !== undefined) {
      fixture.componentRef.setInput('storeName', overrides.storeName);
    }

    fixture.detectChanges();

    // Flush init: shifts
    httpMock.expectOne((r) => r.url === `${TEST_API}/shifts/open`).flush(mockShifts);
    fixture.detectChanges();

    return { fixture, comp };
  }

  describe('initial state', () => {
    it('should create the component', () => {
      const { comp } = createFixture();
      expect(comp).toBeTruthy();
    });

    it('should load open shifts on init', () => {
      const { comp } = createFixture();
      expect(comp.openShifts().length).toBe(1);
    });
  });

  describe('edit mode display', () => {
    it('should show order number and status when loaded', () => {
      const { fixture } = createFixture({
        isEditMode: true,
        loadedOrder: createSalesOrder({ statusName: 'Draft', orderNumber: 'SO-00001' }),
      });

      expect(fixture.nativeElement.textContent).toContain('SO-00001');
      expect(fixture.nativeElement.textContent).toContain('Draft');
    });
  });

  describe('store display', () => {
    it('should show store name when provided', () => {
      const { fixture } = createFixture({ storeName: 'Tienda Centro' });
      expect(fixture.nativeElement.textContent).toContain('Tienda Centro');
    });

    it('should show "Not assigned" when storeName is null', () => {
      const { fixture } = createFixture({ storeName: null });
      expect(fixture.nativeElement.textContent).toContain('Not assigned');
    });
  });

  describe('field errors', () => {
    it('should return error for touched required field', () => {
      const { comp } = createFixture();

      const form = comp.headerForm();
      form.controls.customerId.markAsTouched();
      form.controls.customerId.setErrors({ required: true });

      expect(comp.fieldError('customerId')).toBeTruthy();
    });

    it('should return server error string', () => {
      const { comp } = createFixture({ serverErrors: { customerId: 'Customer required' } });

      expect(comp.serverFieldError('customerId')).toBe('Customer required');
    });
  });

  describe('customer selector integration', () => {
    it('should update form customerId when onCustomerSelected is called', () => {
      const { comp } = createFixture();

      const customer: CustomerOption = {
        id: 'cust-abc',
        name: 'Acme Corp',
        email: 'acme@example.com',
      };
      comp.onCustomerSelected(customer);

      expect(comp.headerForm().controls.customerId.value).toBe('cust-abc');
    });

    it('should set selectedCustomerName when customer is selected', () => {
      const { comp } = createFixture();

      const customer: CustomerOption = {
        id: 'cust-xyz',
        name: 'Beta LLC',
      };
      comp.onCustomerSelected(customer);

      expect(comp.selectedCustomerName()).toBe('Beta LLC');
    });

    it('should update selectedCustomerName on subsequent selections', () => {
      const { comp } = createFixture();

      const first: CustomerOption = { id: 'c1', name: 'First' };
      comp.onCustomerSelected(first);
      expect(comp.selectedCustomerName()).toBe('First');
      expect(comp.headerForm().controls.customerId.value).toBe('c1');

      const second: CustomerOption = { id: 'c2', name: 'Second' };
      comp.onCustomerSelected(second);
      expect(comp.selectedCustomerName()).toBe('Second');
      expect(comp.headerForm().controls.customerId.value).toBe('c2');
    });
  });
});
