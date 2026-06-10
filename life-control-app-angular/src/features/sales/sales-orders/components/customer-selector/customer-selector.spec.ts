import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { CustomerSelector } from './customer-selector';
import { ConfigService } from '@app/services/config.service';
import type { CustomerOption } from '../../models/sales-order.models';

const TEST_API = 'http://test/api';

const mockCustomersPage = {
  content: [
    { id: 'c1', name: 'John Doe', email: 'john@example.com', rfc: 'RFC1' },
    { id: 'c2', name: 'Jane Smith', email: 'jane@example.com', rfc: 'RFC2' },
  ],
  totalElements: 2,
  totalPages: 1,
  size: 20,
  number: 0,
  first: true,
  last: true,
  empty: false,
};

describe('CustomerSelector', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        CustomerSelector,
        NoopAnimationsModule,
        HttpClientTestingModule,
      ],
      providers: [
        { provide: ConfigService, useValue: { apiUrl: TEST_API } },
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function createComponent(): ComponentFixture<CustomerSelector> {
    const fixture = TestBed.createComponent(CustomerSelector);
    fixture.detectChanges();
    return fixture;
  }

  describe('initial state', () => {
    it('should create the component', () => {
      const fixture = createComponent();
      expect(fixture.componentInstance).toBeTruthy();
    });

    it('should render a text input with placeholder', () => {
      const fixture = createComponent();
      const el: HTMLElement = fixture.nativeElement;
      // MatInput renders an <input> inside mat-form-field
      const input = el.querySelector('input');
      expect(input).toBeTruthy();
    });

    it('should have an empty customer list initially', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;
      expect(comp.filteredCustomers().length).toBe(0);
    });
  });

  describe('customer search', () => {
    it('should search customers when typing 2+ characters', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      comp.onSearchChange('jo');
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) =>
          r.url === `${TEST_API}/customers` &&
          r.params.get('search') === 'jo',
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockCustomersPage);
      fixture.detectChanges();

      const customers = comp.filteredCustomers();
      expect(customers.length).toBe(2);
      expect(customers[0].name).toBe('John Doe');
      expect(customers[1].name).toBe('Jane Smith');
    });

    it('should NOT search when fewer than 2 characters typed', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      comp.onSearchChange('j');
      fixture.detectChanges();

      // No HTTP request should be made
      httpMock.expectNone(`${TEST_API}/customers`);
      expect(comp.filteredCustomers().length).toBe(0);
    });

    it('should map API response to CustomerOption correctly', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      comp.onSearchChange('john');
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/customers`,
      );
      req.flush(mockCustomersPage);
      fixture.detectChanges();

      const customers = comp.filteredCustomers();
      expect(customers[0].id).toBe('c1');
      expect(customers[0].name).toBe('John Doe');
      expect(customers[0].email).toBe('john@example.com');
      expect(customers[0].rfc).toBe('RFC1');
    });
  });

  describe('customer selection', () => {
    it('should emit selected customer when option is chosen', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      let emitted: CustomerOption | null = null;
      comp.customerSelected.subscribe((c: CustomerOption) => {
        emitted = c;
      });

      const customer: CustomerOption = {
        id: 'c1',
        name: 'John Doe',
        email: 'john@example.com',
      };
      comp.onCustomerSelect(customer);

      expect(emitted).not.toBeNull();
      expect(emitted!.id).toBe('c1');
      expect(emitted!.name).toBe('John Doe');
    });

    it('should clear search after customer selection', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      comp.onSearchChange('jo');
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/customers`,
      );
      req.flush(mockCustomersPage);
      fixture.detectChanges();

      expect(comp.searchQuery()).toBe('jo');

      const customer: CustomerOption = {
        id: 'c1',
        name: 'John Doe',
      };
      comp.onCustomerSelect(customer);
      fixture.detectChanges();

      expect(comp.searchQuery()).toBe('John Doe');
      expect(comp.filteredCustomers().length).toBe(0);
    });
  });

  describe('error handling', () => {
    it('should clear results on API error', () => {
      const fixture = createComponent();
      const comp = fixture.componentInstance;

      comp.onSearchChange('error');
      fixture.detectChanges();

      const req = httpMock.expectOne(
        (r) => r.url === `${TEST_API}/customers`,
      );
      req.flush('Server error', {
        status: 500,
        statusText: 'Internal Server Error',
      });
      fixture.detectChanges();

      expect(comp.filteredCustomers().length).toBe(0);
    });
  });
});
