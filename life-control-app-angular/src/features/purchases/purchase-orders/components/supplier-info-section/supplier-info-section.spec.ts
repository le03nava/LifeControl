import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { of } from 'rxjs';
import { SupplierInfoSection } from './supplier-info-section';
import { SupplierService } from '@features/products/suppliers/data/supplier.service';
import { ConfigService } from '@app/services/config.service';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import type { PurchaseOrderHeaderControl } from '../../models/purchase-order-control.models';

const TEST_API = 'http://test/api';

const mockSuppliersPage = {
  content: [
    {
      id: 'sup-1',
      supplierName: 'Proveedor Uno',
      razonSocial: 'Proveedor Uno S.A.',
      rfc: 'RFC-PROV-001',
      email: 'ventas@proveedoruno.com',
      phoneNumber: '555-2000',
      enabled: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      address: {
        street: 'Calle Comercio',
        streetNumber: '456',
        neighborhood: 'Industrial',
        zipCode: '20000',
        city: 'Monterrey',
        state: 'Nuevo León',
      },
    },
    {
      id: 'sup-2',
      supplierName: 'Proveedor Dos',
      razonSocial: 'Proveedor Dos S.A.',
      rfc: 'RFC-PROV-002',
      email: 'info@proveedordos.com',
      phoneNumber: '555-3000',
      enabled: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    },
  ],
  totalElements: 2,
  totalPages: 1,
  size: 1000,
  number: 0,
  first: true,
  last: true,
  empty: false,
};

const mockPaymentMethods = [
  { id: 'pm-1', name: 'Transferencia' },
  { id: 'pm-2', name: 'Cheque' },
  { id: 'pm-3', name: 'Efectivo' },
];

describe('SupplierInfoSection', () => {
  let headerForm: FormGroup<PurchaseOrderHeaderControl>;
  let supplierServiceMock: {
    getAllSuppliers: ReturnType<typeof vi.fn>;
    getSupplierById: ReturnType<typeof vi.fn>;
  };
  let httpMock: HttpTestingController;

  function createForm(): FormGroup<PurchaseOrderHeaderControl> {
    return new FormGroup<PurchaseOrderHeaderControl>({
      supplierId: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
      companyStoreId: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
      paymentMethodId: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
      comments: new FormControl<string | null>(null),
    });
  }

  beforeEach(async () => {
    supplierServiceMock = {
      getAllSuppliers: vi.fn().mockReturnValue(of(mockSuppliersPage)),
      getSupplierById: vi.fn(),
    };

    await TestBed.configureTestingModule({
      imports: [SupplierInfoSection, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: ConfigService, useValue: { apiUrl: TEST_API } },
        { provide: SupplierService, useValue: supplierServiceMock },
      ],
    }).compileComponents();

    headerForm = createForm();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  describe('initialisation', () => {
    it('should create', () => {
      const fixture = TestBed.createComponent(SupplierInfoSection);
      expect(fixture.componentInstance).toBeTruthy();
    });

    it('should load suppliers on init', () => {
      const fixture = TestBed.createComponent(SupplierInfoSection);
      const comp = fixture.componentInstance;
      fixture.componentRef.setInput('headerForm', headerForm);
      fixture.detectChanges();

      // Flush payment methods HTTP call (made in ngOnInit)
      httpMock.expectOne(`${TEST_API}/payment-methods`).flush(mockPaymentMethods);

      expect(supplierServiceMock.getAllSuppliers).toHaveBeenCalledWith(0, 1000);
      expect(comp.suppliers().length).toBe(2);
    });

    it('should load payment methods on init', () => {
      const fixture = TestBed.createComponent(SupplierInfoSection);
      const comp = fixture.componentInstance;
      fixture.componentRef.setInput('headerForm', headerForm);
      fixture.detectChanges();

      // Flush payment methods HTTP call
      httpMock.expectOne(`${TEST_API}/payment-methods`).flush(mockPaymentMethods);

      expect(comp.paymentMethods().length).toBe(3);
    });
  });

  function createFixture(overrides?: { headerForm?: FormGroup<PurchaseOrderHeaderControl>; serverErrors?: Record<string, string> }) {
    const fixture = TestBed.createComponent(SupplierInfoSection);
    fixture.componentRef.setInput('headerForm', overrides?.headerForm ?? headerForm);
    if (overrides?.serverErrors) {
      fixture.componentRef.setInput('serverErrors', overrides.serverErrors);
    }
    fixture.detectChanges();
    // Flush payment methods HTTP call made in ngOnInit
    httpMock.expectOne(`${TEST_API}/payment-methods`).flush(mockPaymentMethods);
    return fixture;
  }

  describe('supplier selection', () => {
    it('should load supplier details when supplier is selected', () => {
      const supplierDetail = {
        id: 'sup-1',
        supplierName: 'Proveedor Uno',
        razonSocial: 'Proveedor Uno S.A.',
        rfc: 'RFC-PROV-001',
        email: 'ventas@proveedoruno.com',
        phoneNumber: '555-2000',
        enabled: true,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
        address: {
          street: 'Calle Comercio',
          streetNumber: '456',
          neighborhood: 'Industrial',
          zipCode: '20000',
          city: 'Monterrey',
          state: 'Nuevo León',
        },
      };

      supplierServiceMock.getSupplierById = vi.fn().mockReturnValue(of(supplierDetail));

      const { componentInstance: comp } = createFixture();

      comp.onSupplierChange('sup-1');

      expect(supplierServiceMock.getSupplierById).toHaveBeenCalledWith('sup-1');
      expect(comp.supplierDetail()).not.toBeNull();
      expect(comp.supplierDetail()!.rfc).toBe('RFC-PROV-001');
      expect(comp.supplierDetail()!.email).toBe('ventas@proveedoruno.com');
      expect(comp.supplierDetail()!.phone).toBe('555-2000');
      expect(comp.supplierDetail()!.address).toContain('Calle Comercio');
    });

    it('should format address with available fields only', () => {
      const partialSupplier = {
        id: 'sup-2',
        supplierName: 'Proveedor Dos',
        razonSocial: 'Proveedor Dos S.A.',
        rfc: 'RFC-PROV-002',
        email: 'info@proveedordos.com',
        phoneNumber: '555-3000',
        enabled: true,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
        // No address field
      };

      supplierServiceMock.getSupplierById = vi.fn().mockReturnValue(of(partialSupplier));

      const { componentInstance: comp } = createFixture();

      comp.onSupplierChange('sup-2');

      expect(comp.supplierDetail()!.address).toBe('—');
    });

    it('should hide supplier details when selection is cleared', () => {
      supplierServiceMock.getSupplierById = vi.fn().mockReturnValue(of(mockSuppliersPage.content[0]));

      const { componentInstance: comp } = createFixture();

      comp.onSupplierChange('sup-1');
      expect(comp.supplierDetail()).not.toBeNull();

      comp.onSupplierChange('');
      expect(comp.supplierDetail()).toBeNull();
      expect(comp.selectedSupplierId()).toBeNull();
    });
  });

  describe('form helpers', () => {
    it('fieldError should return required message when invalid and touched', () => {
      const { componentInstance: comp } = createFixture();

      headerForm.controls.supplierId.markAsTouched();
      expect(comp.fieldError('supplierId')).toBe('Este campo es requerido.');
    });

    it('fieldError should return null when control is valid', () => {
      const { componentInstance: comp } = createFixture();

      headerForm.controls.supplierId.setValue('sup-1');
      headerForm.controls.supplierId.markAsTouched();
      expect(comp.fieldError('supplierId')).toBeNull();
    });

    it('serverFieldError should return server error for a field', () => {
      const { componentInstance: comp } = createFixture({ serverErrors: { supplierId: 'Proveedor no disponible' } });

      expect(comp.serverFieldError('supplierId')).toBe('Proveedor no disponible');
    });

    it('serverFieldError should return null when no error', () => {
      const { componentInstance: comp } = createFixture();

      expect(comp.serverFieldError('supplierId')).toBeNull();
    });
  });
});
