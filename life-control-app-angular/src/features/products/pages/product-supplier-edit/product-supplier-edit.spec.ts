import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ProductSupplierEdit } from './product-supplier-edit';
import { ProductSupplierService } from '../../data/product-supplier.service';
import { ProductService } from '../../data/product.service';
import { SupplierService } from '../../suppliers/data/supplier.service';

describe('ProductSupplierEdit', () => {
  let component: ProductSupplierEdit;
  let fixture: ComponentFixture<ProductSupplierEdit>;
  let productService: { getProductById: ReturnType<typeof vi.fn> };
  let supplierService: { getAllSuppliers: ReturnType<typeof vi.fn> };
  let routerMock: { navigate: ReturnType<typeof vi.fn> };

  const emptySupplierPage = {
    content: [],
    totalElements: 0,
    totalPages: 0,
    size: 1000,
    number: 0,
    first: true,
    last: true,
    empty: true,
  };

  function setup(): void {
    productService = {
      getProductById: vi
        .fn()
        .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 }))),
    };
    supplierService = {
      getAllSuppliers: vi
        .fn()
        .mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 }))),
    };
    const productSupplierService = {
      getSuppliers: vi.fn().mockReturnValue(of([])),
      addSupplier: vi.fn(),
      updateSupplier: vi.fn(),
    };
    routerMock = { navigate: vi.fn() };

    TestBed.configureTestingModule({
      imports: [ProductSupplierEdit, NoopAnimationsModule],
      providers: [
        provideHttpClient(),
        { provide: ProductService, useValue: productService },
        { provide: ProductSupplierService, useValue: productSupplierService },
        { provide: SupplierService, useValue: supplierService },
        { provide: Router, useValue: routerMock },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: {
                get: (key: string) => (key === 'id' ? 'prod-1' : null),
              },
            },
          },
        },
      ],
    });

    fixture = TestBed.createComponent(ProductSupplierEdit);
    component = fixture.componentInstance;
  }

  it('should create', () => {
    setup();
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('should guard product() and render an error banner when the product request fails', async () => {
    setup();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(() => component.product()).not.toThrow();
    expect(component.product()).toBeUndefined();
    expect(component.productResource.error()).toBeTruthy();
    expect((fixture.nativeElement as HTMLElement).querySelector('app-error-banner')).toBeTruthy();
  });

  it('should guard allSuppliers() when the supplier request fails', async () => {
    setup();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(() => component.allSuppliers()).not.toThrow();
    expect(component.allSuppliers()).toBeUndefined();
    expect(component.availableSuppliers()).toEqual([]);
  });

  it('should read the supplier page when the suppliers request succeeds', async () => {
    setup();
    supplierService.getAllSuppliers = vi.fn().mockReturnValue(of(emptySupplierPage));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.allSuppliers()).toEqual([]);
  });
});
