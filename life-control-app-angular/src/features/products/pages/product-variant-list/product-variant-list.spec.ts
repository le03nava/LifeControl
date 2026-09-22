import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { Subject, of, throwError } from 'rxjs';
import { ProductVariantList } from './product-variant-list';
import { ProductService } from '../../data/product.service';
import { ProductVariantService } from '../../data/product-variant.service';
import { Product, Page } from '../../models/product.models';
import { ProductVariant } from '../../models/product-variant.models';
import { ProfileResponse } from '@features/user/profile/data/profile.models';
import { ProfileService } from '@features/user/profile/data/profile.service';

interface VariantServiceMock {
  getVariants: ReturnType<typeof vi.fn>;
  deleteVariant: ReturnType<typeof vi.fn>;
  enableVariant: ReturnType<typeof vi.fn>;
}

describe('ProductVariantList', () => {
  let component: ProductVariantList;
  let fixture: ComponentFixture<ProductVariantList>;
  let variantServiceMock: VariantServiceMock;
  let profileServiceMock: { getProfile: ReturnType<typeof vi.fn> };
  let routerMock: { navigate: ReturnType<typeof vi.fn> };
  let dialogMock: { open: ReturnType<typeof vi.fn> };

  const mockProductId = 'prod-1';

  const mockProduct: Product = {
    id: mockProductId,
    sku: 'SKU-001',
    name: 'Producto de prueba',
    enabled: true,
    createdAt: '',
    updatedAt: '',
  };

  function createVariant(index: number, overrides: Partial<ProductVariant> = {}): ProductVariant {
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
      ...overrides,
    };
  }

  function createPage(variants: ProductVariant[], totalPages = 1): Page<ProductVariant> {
    return {
      content: variants,
      totalElements: variants.length,
      totalPages,
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
    options: {
      productId?: string | null;
      variants?: ProductVariant[];
      totalPages?: number;
      variantsError?: boolean;
      variantsPending?: boolean;
      queryStoreId?: string | null;
      profileStoreId?: string | null;
      profileError?: boolean;
    } = {},
  ) {
    const productId = options.productId !== undefined ? options.productId : mockProductId;
    const variants = options.variants ?? [createVariant(1), createVariant(2)];
    const queryStoreId = options.queryStoreId ?? null;

    variantServiceMock = {
      getVariants: options.variantsError
        ? vi.fn().mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })))
        : options.variantsPending
          ? vi.fn().mockReturnValue(new Subject<Page<ProductVariant>>())
          : vi.fn(
              (
                _productId: string,
                storeId?: string,
                _page = 0,
                _size = 12,
                includeDisabled = false,
              ) => {
                // Mirrors the real backend. The store-scoped branch joins the store
                // row into every result and always filters `enabled = true`,
                // ignoring the `includeDisabled` opt-in; the global branch leaves
                // the store fields `null` on every row.
                const visible = storeId
                  ? variants.filter((v) => v.enabled)
                  : includeDisabled
                    ? variants
                    : variants.filter((v) => v.enabled);
                const content = storeId
                  ? visible.map((v) => ({
                      ...v,
                      companyStoreId: storeId,
                      listPrice: 150,
                      costPrice: 90,
                      stock: 8,
                    }))
                  : visible.map((v) => ({
                      ...v,
                      companyStoreId: null,
                      listPrice: null,
                      costPrice: null,
                      stock: null,
                    }));
                return of(createPage(content, options.totalPages ?? 1));
              },
            ),
      deleteVariant: vi.fn().mockReturnValue(of(void 0)),
      enableVariant: vi.fn().mockReturnValue(of(createVariant(1))),
    };

    profileServiceMock = {
      getProfile: options.profileError
        ? vi.fn().mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })))
        : vi.fn().mockReturnValue(of(profileResponse(options.profileStoreId ?? null))),
    };

    routerMock = { navigate: vi.fn() };
    dialogMock = { open: vi.fn().mockReturnValue({ afterClosed: () => of(false) }) };

    TestBed.configureTestingModule({
      imports: [ProductVariantList, NoopAnimationsModule],
      providers: [
        { provide: ProductVariantService, useValue: variantServiceMock },
        { provide: ProfileService, useValue: profileServiceMock },
        {
          provide: ProductService,
          useValue: { getProductById: vi.fn().mockReturnValue(of(mockProduct)) },
        },
        { provide: Router, useValue: routerMock },
        { provide: MatDialog, useValue: dialogMock },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: {
                get: (key: string) => (key === 'id' ? productId : null),
              },
              queryParamMap: {
                get: (key: string) => (key === 'storeId' ? queryStoreId : null),
              },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductVariantList);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  /**
   * Settles the page: the store resolution, the read it gates and the render.
   *
   * More than one flush on purpose. The list read is withheld until the store
   * resolution settles, and a resource's load starts on the flush that follows the
   * params change, so a single `whenStable()` can return with the read still idle
   * (observed: `isLoading: true`, zero calls). Ending on a `detectChanges()` leaves
   * the DOM reflecting the settled state for the assertions.
   */
  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /** Labels of the rendered slide toggles, in DOM order. */
  function toggleLabels(): string[] {
    return fixture.debugElement
      .queryAll(By.css('mat-slide-toggle'))
      .map((toggle) => (toggle.nativeElement as HTMLElement).textContent?.trim() ?? '');
  }

  describe('Component creation', () => {
    it('should create', () => {
      setup();
      expect(component).toBeTruthy();
    });

    it('should read productId from the route', () => {
      setup();
      expect(component.productId()).toBe(mockProductId);
    });

    it('should fetch the variants without a storeId on init and without disabled ones', async () => {
      setup();
      await settle();

      expect(variantServiceMock.getVariants).toHaveBeenCalledWith(
        mockProductId,
        undefined,
        0,
        12,
        false,
      );
    });

    it('should load the product for the header', () => {
      setup();
      const productService = TestBed.inject(ProductService);
      expect(productService.getProductById).toHaveBeenCalledWith(mockProductId);
    });
  });

  describe('Display states', () => {
    it('should render the product name and SKU in the header', async () => {
      setup();
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.textContent).toContain('Producto de prueba');
      expect(el.textContent).toContain('SKU-001');
    });

    it('should render the enabled state without offering Habilitar by default', async () => {
      setup({ variants: [createVariant(1), createVariant(2)] });
      await settle();

      const rows = fixture.debugElement.queryAll(By.css('tr.mat-mdc-row'));
      expect(rows.length).toBe(2);

      const el = fixture.nativeElement as HTMLElement;
      expect(el.textContent).toContain('Talla 1');
      expect(el.textContent).toContain('Talla 2');
      expect(el.textContent).toContain('77900000001');
      expect(el.textContent).toContain('Habilitada');
      expect(el.textContent).not.toContain('Deshabilitada');
      expect(
        fixture.debugElement.query(By.css('button[aria-label="Habilitar variante"]')),
      ).toBeNull();
    });

    it('should show the loading skeleton while the request is in flight', () => {
      setup({ variantsPending: true });

      const el = fixture.nativeElement as HTMLElement;
      expect(component.loading()).toBe(true);
      expect(el.querySelector('.loading-skeleton')).toBeTruthy();
      expect(el.querySelector('.table-card')).toBeNull();
    });

    it('should show the empty state when there are no variants', async () => {
      setup({ variants: [] });
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('.empty-state')).toBeTruthy();
      expect(el.textContent).toContain('No hay variantes registradas');
    });
  });

  describe('Pagination', () => {
    it('should render the paginator only when there is more than one page', async () => {
      setup({ totalPages: 2 });
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      expect(component.hasMultiplePages()).toBe(true);
      expect(el.querySelector('.pagination-section')).toBeTruthy();
    });

    it('should update the page signals and refetch on page change', async () => {
      setup({ totalPages: 2 });
      await settle();

      variantServiceMock.getVariants.mockClear();
      component.onPageChange({ pageIndex: 1, pageSize: 24 });
      fixture.detectChanges();
      await settle();

      expect(component.pageIndex()).toBe(1);
      expect(component.pageSize()).toBe(24);
      expect(variantServiceMock.getVariants).toHaveBeenCalledWith(
        mockProductId,
        undefined,
        1,
        24,
        false,
      );
    });
  });

  describe('Disabled filter', () => {
    it('should not request disabled variants while the toggle is off', async () => {
      setup();
      await settle();

      expect(component.includeDisabled()).toBe(false);
      expect(variantServiceMock.getVariants).toHaveBeenCalledWith(
        mockProductId,
        undefined,
        0,
        12,
        false,
      );
    });

    it('should request disabled variants and reload when the toggle turns on', async () => {
      setup();
      await settle();
      variantServiceMock.getVariants.mockClear();

      component.onIncludeDisabledChange(true);
      fixture.detectChanges();
      await settle();

      expect(component.includeDisabled()).toBe(true);
      expect(variantServiceMock.getVariants).toHaveBeenCalledWith(
        mockProductId,
        undefined,
        0,
        12,
        true,
      );
    });
  });

  describe('Store scope', () => {
    it('should let the link name the store over the profile', async () => {
      setup({ queryStoreId: 'store-from-link', profileStoreId: 'store-from-profile' });
      await settle();

      expect(component.storeId()).toBe('store-from-link');
      expect(component.storeSource()).toBe('query');
      // The link already answers the question, so the profile is never read.
      expect(profileServiceMock.getProfile).not.toHaveBeenCalled();
    });

    it('should scope the read to the resolved store and drop the disabled opt-in', async () => {
      setup({ profileStoreId: 'store-from-profile' });
      await settle();

      expect(profileServiceMock.getProfile).toHaveBeenCalled();
      expect(variantServiceMock.getVariants).toHaveBeenCalledWith(
        mockProductId,
        'store-from-profile',
        0,
        12,
        false,
      );
    });

    it('should issue one read, not a global one followed by a store one', async () => {
      setup({ profileStoreId: 'store-from-profile' });
      await settle();

      expect(variantServiceMock.getVariants).toHaveBeenCalledTimes(1);
    });

    it('should show the per-store columns and drop the always-true enabled column', async () => {
      setup({ profileStoreId: 'store-1' });
      await settle();

      const headers = fixture.debugElement
        .queryAll(By.css('th.mat-mdc-header-cell'))
        .map((header) => (header.nativeElement as HTMLElement).textContent?.trim() ?? '');

      expect(headers).toContain('Precio de venta');
      expect(headers).toContain('Costo');
      expect(headers).toContain('Stock');
      // The store-scoped branch filters `enabled = true` server-side.
      expect(headers).not.toContain('Estado');

      // Asserted per cell, not over the whole page: the values also appear inside
      // the barcodes, where a page-wide `toContain` would pass vacuously.
      const cells = fixture.debugElement
        .queryAll(By.css('td.mat-mdc-cell'))
        .map((cell) => (cell.nativeElement as HTMLElement).textContent?.trim() ?? '');
      expect(cells.some((cell) => cell.includes('150'))).toBe(true);
      expect(cells.some((cell) => cell.includes('90'))).toBe(true);
      expect(cells).toContain('8');
    });

    it('should offer the store scope and hide the disabled filter while it is on', async () => {
      setup({ profileStoreId: 'store-1' });
      await settle();

      expect(component.storeScoped()).toBe(true);
      expect(toggleLabels()).toContain('Ver sólo la tienda');
      expect(toggleLabels()).not.toContain('Mostrar deshabilitadas');
    });

    it('should keep the global view reachable and refetch without a store when widened', async () => {
      setup({ profileStoreId: 'store-1' });
      await settle();
      variantServiceMock.getVariants.mockClear();

      component.onStoreScopeChange(false);
      fixture.detectChanges();
      await settle();

      expect(component.storeScoped()).toBe(false);
      expect(variantServiceMock.getVariants).toHaveBeenCalledWith(
        mockProductId,
        undefined,
        0,
        12,
        false,
      );

      fixture.detectChanges();
      // The disabled opt-in is only offered on the global read.
      expect(toggleLabels()).toContain('Mostrar deshabilitadas');
    });

    it('should tell the operator when a store view has no rows for that store', async () => {
      setup({ profileStoreId: 'store-1', variants: [createVariant(1, { enabled: false })] });
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.textContent).toContain('Esta tienda no tiene stock ni precios cargados');
      expect(el.textContent).not.toContain('No hay variantes registradas');
    });

    it('should fall back to the global definitions when the profile read fails', async () => {
      setup({ profileError: true });
      await settle();

      // A failed read is not the same fact as a store that was never configured.
      expect(component.storeError()).not.toBeNull();
      expect(component.storeUnconfigured()).toBe(false);
      expect(component.storeScoped()).toBe(false);
      expect(variantServiceMock.getVariants).toHaveBeenCalledWith(
        mockProductId,
        undefined,
        0,
        12,
        false,
      );
    });

    it('should re-run a failed store resolution from the rendered retry', async () => {
      setup({ profileError: true });
      await settle();

      const retrySpy = vi.spyOn(component, 'retryStore');
      const button = fixture.debugElement.query(By.css('.store-scope--error button'));
      expect(button).toBeTruthy();
      (button.nativeElement as HTMLButtonElement).click();

      expect(retrySpy).toHaveBeenCalled();
    });

    it('should point at the profile when no store is configured', async () => {
      setup({ profileStoreId: null });
      await settle();

      expect(component.storeUnconfigured()).toBe(true);

      const el = fixture.nativeElement as HTMLElement;
      expect(el.textContent).toContain('No hay una tienda configurada');

      const button = fixture.debugElement.query(By.css('.store-scope button'));
      expect(button).toBeTruthy();
      (button.nativeElement as HTMLButtonElement).click();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/profile']);
    });

    it('should carry a store named by the link into the sibling screens', async () => {
      setup({ queryStoreId: 'store-from-link' });
      await settle();

      component.editVariant('var-1');

      expect(routerMock.navigate).toHaveBeenCalledWith(
        ['/products/edit', mockProductId, 'variants', 'edit', 'var-1'],
        { queryParams: { storeId: 'store-from-link' } },
      );
    });

    it('should not write a profile-resolved store into the link', async () => {
      setup({ profileStoreId: 'store-from-profile' });
      await settle();

      component.editVariant('var-1');

      // The next screen re-resolves it from the profile; the link stays honest.
      expect(routerMock.navigate).toHaveBeenCalledWith(
        ['/products/edit', mockProductId, 'variants', 'edit', 'var-1'],
        { queryParams: undefined },
      );
    });
  });

  describe('Navigation', () => {
    it('should navigate to the variants edit target', () => {
      setup();
      component.editVariant('var-1');
      expect(routerMock.navigate).toHaveBeenCalledWith(
        ['/products/edit', mockProductId, 'variants', 'edit', 'var-1'],
        { queryParams: undefined },
      );
    });

    it('should navigate to the variants create target', () => {
      setup();
      component.addVariant();
      expect(routerMock.navigate).toHaveBeenCalledWith(
        ['/products/edit', mockProductId, 'variants', 'create'],
        { queryParams: undefined },
      );
    });
  });

  describe('Disable flow', () => {
    it('should open the dialog and not disable when cancelled', () => {
      setup();
      component.confirmDisable(createVariant(1));

      expect(dialogMock.open).toHaveBeenCalled();
      expect(variantServiceMock.deleteVariant).not.toHaveBeenCalled();
    });

    it('should soft-delete and reload when the dialog confirms', () => {
      setup();
      dialogMock.open = vi.fn().mockReturnValue({ afterClosed: () => of(true) });
      const reloadSpy = vi.spyOn(component.variantsResource, 'reload');

      component.confirmDisable(createVariant(1));

      expect(variantServiceMock.deleteVariant).toHaveBeenCalledWith(mockProductId, 'var-1');
      expect(reloadSpy).toHaveBeenCalled();
    });
  });

  describe('Enable flow', () => {
    it('should reveal the disabled row and re-enable it from the rendered button', async () => {
      setup({ variants: [createVariant(1), createVariant(2, { enabled: false })] });
      await settle();

      // Toggle off: the backend would not return the disabled row.
      expect(
        fixture.debugElement.query(By.css('button[aria-label="Habilitar variante"]')),
      ).toBeNull();

      // Toggle on: the disabled row now renders with the Habilitar affordance.
      component.onIncludeDisabledChange(true);
      fixture.detectChanges();
      await settle();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.textContent).toContain('Deshabilitada');

      const reloadSpy = vi.spyOn(component.variantsResource, 'reload');
      const enableButton = fixture.debugElement.query(
        By.css('button[aria-label="Habilitar variante"]'),
      );
      expect(enableButton).toBeTruthy();
      (enableButton.nativeElement as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(variantServiceMock.enableVariant).toHaveBeenCalledWith(mockProductId, 'var-2');
      expect(reloadSpy).toHaveBeenCalled();
    });
  });

  describe('Missing productId', () => {
    it('should redirect to the product list when the route has no id', async () => {
      setup({ productId: null });

      fixture.detectChanges();
      await settle();
      await settle();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/products/list']);
    });
  });

  describe('Error state', () => {
    it('should render the error state when the variants fail', async () => {
      setup({ variantsError: true });
      await settle();

      expect(component.variants()).toBeUndefined();
      expect(component.hasMultiplePages()).toBe(false);
      expect(component.error()).toBeTruthy();

      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('.error-state')).toBeTruthy();
      expect(el.querySelector('tr.mat-mdc-row')).toBeNull();
      expect(el.querySelector('.loading-skeleton')).toBeNull();
      expect(el.textContent).toContain('Tu sesión expiró');
    });

    it('should reload the resource on retry', () => {
      setup();
      const reloadSpy = vi.spyOn(component.variantsResource, 'reload');
      component.onRetry();
      expect(reloadSpy).toHaveBeenCalled();
    });
  });
});
