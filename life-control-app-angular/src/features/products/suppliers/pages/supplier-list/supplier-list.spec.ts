import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { SupplierList } from './supplier-list';
import { SupplierService } from '../../data/supplier.service';
import { Supplier, Page } from '../../models/supplier.models';

interface SupplierServiceMock {
  getSuppliers: ReturnType<typeof vi.fn>;
  deleteSupplier: ReturnType<typeof vi.fn>;
}

describe('SupplierList', () => {
  let component: SupplierList;
  let fixture: ComponentFixture<SupplierList>;
  let supplierService: SupplierServiceMock;

  const mockSuppliers: Supplier[] = [
    {
      id: '1',
      supplierName: 'Acme Supplies',
      razonSocial: 'Acme Supplies SA',
      rfc: 'RFC0000000001',
      email: 'acme@test.com',
      phoneNumber: '5551112222',
      enabled: true,
      createdAt: '',
      updatedAt: '',
    },
  ];

  const createMockPage = (suppliers: Supplier[], page = 0, size = 12): Page<Supplier> => ({
    content: suppliers,
    totalElements: suppliers.length,
    totalPages: Math.ceil(suppliers.length / size),
    size,
    number: page,
    first: page === 0,
    last: (page + 1) * size >= suppliers.length,
    empty: suppliers.length === 0,
  });

  beforeEach(async () => {
    supplierService = {
      getSuppliers: vi.fn().mockReturnValue(of(createMockPage(mockSuppliers))),
      deleteSupplier: vi.fn().mockReturnValue(of(void 0)),
    };

    await TestBed.configureTestingModule({
      imports: [SupplierList, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: SupplierService, useValue: supplierService },
        {
          provide: MatDialog,
          useValue: {
            open: vi.fn().mockReturnValue({ afterClosed: vi.fn().mockReturnValue(of(false)) }),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SupplierList);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('should load suppliers on creation via rxResource', () => {
    fixture.detectChanges();
    expect(supplierService.getSuppliers).toHaveBeenCalledWith(0, 12, undefined);
  });

  it('should render suppliers when data loads', async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Acme Supplies');
  });

  it('should not throw and render the error state when the API fails', async () => {
    supplierService.getSuppliers = vi
      .fn()
      .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(() => component.suppliers()).not.toThrow();
    expect(component.suppliers()).toBeUndefined();
    expect(component.error()).toBeTruthy();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.error-state')).toBeTruthy();
    expect(el.querySelector('.loading-skeleton')).toBeNull();
    expect(el.textContent).toContain('Tu sesión expiró');
  });
});
