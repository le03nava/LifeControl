import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { ProductVariantListHost } from './product-variant-list-host';
import { ProductVariantList } from '../product-variant-list/product-variant-list';
import { ProductService } from '../../data/product.service';
import { ProductVariantService } from '../../data/product-variant.service';
import { Page, Product } from '../../models/product.models';
import { ProductVariant } from '../../models/product-variant.models';
import { ProfileResponse } from '@features/user/profile/data/profile.models';
import { ProfileService } from '@features/user/profile/data/profile.service';

/**
 * The thin route host for `edit/:id/variants` (D17).
 *
 * It exists only because the variant list became tab content and no longer renders
 * its own page header, while `lc-sales` still needs a titled entry point. Its spec
 * therefore asserts only the host contract: the header text, the product id handed
 * down to the real container, and that neither primitive is duplicated. The
 * container's own spec owns the table behaviour.
 */
describe('ProductVariantListHost', () => {
  let fixture: ComponentFixture<ProductVariantListHost>;
  let productServiceMock: { getProductById: ReturnType<typeof vi.fn> };
  let variantServiceMock: { getVariants: ReturnType<typeof vi.fn> };
  let profileServiceMock: { getProfile: ReturnType<typeof vi.fn> };

  const mockProductId = 'prod-1';

  function createProduct(overrides: Partial<Product> = {}): Product {
    return {
      id: mockProductId,
      sku: 'ZAP-001',
      name: 'Zapatilla Runner',
      enabled: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      ...overrides,
    };
  }

  function createVariant(index: number): ProductVariant {
    return {
      id: `var-${index}`,
      productId: mockProductId,
      companyStoreId: null,
      barCode: `7790000000${index}`,
      sku: 'SKU-001',
      variantName: `Talla ${index}`,
      listPrice: null,
      costPrice: null,
      stock: null,
      enabled: true,
    };
  }

  function createPage(variants: ProductVariant[]): Page<ProductVariant> {
    return {
      content: variants,
      totalElements: variants.length,
      totalPages: 1,
      size: 12,
      number: 0,
      first: true,
      last: true,
      empty: variants.length === 0,
    };
  }

  function profileResponse(companyStoreId: string | null): ProfileResponse {
    return {
      keycloakUserId: 'user-1',
      username: 'operator',
      email: 'operator@lifecontrol.test',
      firstName: 'Oper',
      lastName: 'Ator',
      companyId: 'company-1',
      companyCountryId: 'cc-1',
      companyRegionId: 'region-1',
      companyZoneId: 'zone-1',
      companyStoreId,
    };
  }

  function setup(
    options: { productId?: string; product?: Product; productError?: boolean } = {},
  ): void {
    const productId = options.productId ?? mockProductId;
    const product = options.product ?? createProduct();

    productServiceMock = {
      getProductById: options.productError
        ? vi.fn().mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })))
        : vi.fn().mockReturnValue(of(product)),
    };
    variantServiceMock = {
      getVariants: vi.fn().mockReturnValue(of(createPage([createVariant(1)]))),
    };
    profileServiceMock = {
      getProfile: vi.fn().mockReturnValue(of(profileResponse(null))),
    };

    TestBed.configureTestingModule({
      imports: [ProductVariantListHost, NoopAnimationsModule],
      providers: [
        { provide: ProductService, useValue: productServiceMock },
        { provide: ProductVariantService, useValue: variantServiceMock },
        { provide: ProfileService, useValue: profileServiceMock },
        { provide: Router, useValue: { navigate: vi.fn() } },
        {
          provide: MatDialog,
          useValue: { open: vi.fn().mockReturnValue({ afterClosed: () => of(false) }) },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: { get: (key: string) => (key === 'id' ? productId : null) },
              queryParamMap: { get: () => null },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductVariantListHost);
  }

  /**
   * Settles the host and the real container it renders: the store resolution, the
   * read it gates and the render. Mirrors the container spec's `settle()` for the
   * same reason (a resource's load starts on the flush that follows a params change).
   */
  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function header(): HTMLElement {
    return fixture.debugElement.query(By.css('app-page-header')).nativeElement as HTMLElement;
  }

  function child(): ProductVariantList {
    return fixture.debugElement.query(By.directive(ProductVariantList))
      .componentInstance as ProductVariantList;
  }

  it('should render the loaded product name and SKU in the page header', async () => {
    setup({ product: createProduct({ name: 'Zapatilla Runner', sku: 'ZAP-001' }) });
    await settle();

    expect(header().querySelector('.page-title')?.textContent?.trim()).toBe('Zapatilla Runner');
    expect(header().querySelector('.page-subtitle')?.textContent?.trim()).toBe('SKU: ZAP-001');
  });

  it('should pass the product id read from the route param down to the variant list', async () => {
    // The route stub only answers `id`, so a read that reached any other source
    // could not produce this value.
    setup({ productId: 'prod-42' });
    await settle();

    expect(child().productId()).toBe('prod-42');
  });

  it('should load the header product exactly once', async () => {
    setup({ productId: 'prod-42' });
    await settle();

    expect(productServiceMock.getProductById).toHaveBeenCalledTimes(1);
    expect(productServiceMock.getProductById).toHaveBeenCalledWith('prod-42');
  });

  it('should render exactly one page header and one variant list', async () => {
    setup();
    await settle();

    expect(fixture.debugElement.queryAll(By.css('app-page-header')).length).toBe(1);
    expect(fixture.debugElement.queryAll(By.css('app-product-variant-list')).length).toBe(1);
  });

  it('should report no unsaved changes before the panel reports any', async () => {
    setup();
    await settle();

    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(false);
  });

  it('should report unsaved changes once the panel emits a dirty state', async () => {
    setup();
    await settle();

    // Drive the real container's output, so the assertion pins the template binding
    // itself instead of a method call the template could bypass.
    child().dirtyChange.emit(true);
    fixture.detectChanges();

    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(true);
  });

  it('should clear the unsaved-changes state when the panel reports clean again', async () => {
    setup();
    await settle();

    child().dirtyChange.emit(true);
    fixture.detectChanges();
    child().dirtyChange.emit(false);
    fixture.detectChanges();

    expect(fixture.componentInstance.hasUnsavedChanges()).toBe(false);
  });
});
