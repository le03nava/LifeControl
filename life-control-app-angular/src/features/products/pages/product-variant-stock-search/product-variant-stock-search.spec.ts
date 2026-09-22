import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ProductVariantStockSearch } from './product-variant-stock-search';
import { ProductVariantService } from '../../data/product-variant.service';
import { ProductVariantSearchResult } from '../../models/product-variant.models';
import { ProfileResponse } from '@features/user/profile/data/profile.models';
import { ProfileService } from '@features/user/profile/data/profile.service';

describe('ProductVariantStockSearch', () => {
  let component: ProductVariantStockSearch;
  let fixture: ComponentFixture<ProductVariantStockSearch>;
  let variantServiceMock: { searchVariants: ReturnType<typeof vi.fn> };
  let profileServiceMock: { getProfile: ReturnType<typeof vi.fn> };
  let routerMock: { navigate: ReturnType<typeof vi.fn> };

  const row = (
    overrides: Partial<ProductVariantSearchResult> = {},
  ): ProductVariantSearchResult => ({
    id: 'var-1',
    productId: 'prod-1',
    companyStoreId: 'store-1',
    barCode: '7791234567890',
    variantName: 'Talla 38',
    productName: 'Camisa Oxford',
    listPrice: 250,
    costPrice: 150,
    stock: 12,
    ...overrides,
  });

  function searchPage(rows: ProductVariantSearchResult[], totalPages = 1) {
    return {
      content: rows,
      totalElements: rows.length,
      totalPages,
      size: 20,
      number: 0,
      first: true,
      last: true,
      empty: rows.length === 0,
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
      queryStoreId?: string | null;
      profileStoreId?: string | null;
      profileError?: boolean;
      rows?: ProductVariantSearchResult[];
      totalPages?: number;
      searchError?: boolean;
    } = {},
  ) {
    const queryStoreId = options.queryStoreId ?? null;
    const profileStoreId =
      options.profileStoreId !== undefined ? options.profileStoreId : 'store-1';

    variantServiceMock = {
      searchVariants: options.searchError
        ? vi.fn().mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })))
        : vi.fn().mockReturnValue(of(searchPage(options.rows ?? [row()], options.totalPages ?? 1))),
    };
    profileServiceMock = {
      getProfile: options.profileError
        ? vi.fn().mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })))
        : vi.fn().mockReturnValue(of(profileResponse(profileStoreId))),
    };
    routerMock = { navigate: vi.fn() };

    TestBed.configureTestingModule({
      imports: [ProductVariantStockSearch, NoopAnimationsModule],
      providers: [
        { provide: ProductVariantService, useValue: variantServiceMock },
        { provide: ProfileService, useValue: profileServiceMock },
        { provide: Router, useValue: routerMock },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: {
                get: (key: string) => (key === 'storeId' ? queryStoreId : null),
              },
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductVariantStockSearch);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  /**
   * Settles the store resolution and the render. The search itself is submit-driven, so
   * the extra flush only covers the resolution the read is gated on.
   */
  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /** Types into the search box without submitting. */
  function type(value: string): void {
    component.onQueryChange({ target: { value } } as unknown as Event);
  }

  it('should create', () => {
    setup();
    expect(component).toBeTruthy();
  });

  it('should let the link name the store without reading the profile', async () => {
    setup({ queryStoreId: 'store-from-link', profileStoreId: 'store-from-profile' });
    await settle();

    expect(component.storeId()).toBe('store-from-link');
    expect(component.storeSource()).toBe('query');
    expect(profileServiceMock.getProfile).not.toHaveBeenCalled();
  });

  it('should fall back to the profile store', async () => {
    setup({ profileStoreId: 'store-from-profile' });
    await settle();

    expect(component.storeId()).toBe('store-from-profile');
    expect(component.storeSource()).toBe('profile');
  });

  it('should not search while the operator is still typing', async () => {
    setup();
    await settle();

    type('camisa');
    fixture.detectChanges();
    await settle();

    // A page of results, not an autocomplete: typing is not a request.
    expect(variantServiceMock.searchVariants).not.toHaveBeenCalled();
  });

  it('should not search before two characters', async () => {
    setup();
    await settle();

    type('c');
    component.onSearch();
    fixture.detectChanges();
    await settle();

    expect(variantServiceMock.searchVariants).not.toHaveBeenCalled();
    expect(component.searched()).toBe(false);
  });

  it('should search the resolved store with the submitted query on submit', async () => {
    setup({ profileStoreId: 'store-from-profile' });
    await settle();

    type('7791234567890');
    component.onSearch();
    fixture.detectChanges();
    await settle();

    expect(variantServiceMock.searchVariants).toHaveBeenCalledWith(
      '7791234567890',
      'store-from-profile',
      0,
      20,
    );
  });

  it('should re-run the search when the same query is submitted again', async () => {
    setup();
    await settle();

    type('camisa');
    component.onSearch();
    fixture.detectChanges();
    await settle();
    expect(variantServiceMock.searchVariants).toHaveBeenCalledTimes(1);

    // The button must always do what it says, so a repeat is a refresh, not a no-op.
    component.onSearch();
    fixture.detectChanges();
    await settle();
    expect(variantServiceMock.searchVariants).toHaveBeenCalledTimes(2);
  });

  it('should render the store row of every match', async () => {
    setup({ rows: [row({ productName: 'Camisa Oxford', variantName: 'Talla 38', stock: 12 })] });
    await settle();

    type('camisa');
    component.onSearch();
    fixture.detectChanges();
    await settle();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('7791234567890');
    expect(el.textContent).toContain('Camisa Oxford');
    expect(el.textContent).toContain('Talla 38');

    const cells = fixture.debugElement
      .queryAll(By.css('td.mat-mdc-cell'))
      .map((cell) => (cell.nativeElement as HTMLElement).textContent?.trim() ?? '');
    expect(cells.some((cell) => cell.includes('250'))).toBe(true);
    expect(cells).toContain('12');
  });

  it('should say the search found nothing without claiming the catalogue is empty', async () => {
    setup({ rows: [] });
    await settle();

    type('inexistente');
    component.onSearch();
    fixture.detectChanges();
    await settle();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Sin resultados en tu tienda');
    expect(el.textContent).toContain('Sólo aparecen las variantes que ya tienen stock');
  });

  it('should prompt for a query before anything is searched', async () => {
    setup();
    await settle();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Buscá por código de barras');
    expect(el.querySelector('.table-card')).toBeNull();
  });

  it('should surface a failed search with a retry', async () => {
    setup({ searchError: true });
    await settle();

    type('camisa');
    component.onSearch();
    fixture.detectChanges();
    await settle();

    expect(component.error()).toBeTruthy();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.error-state')).toBeTruthy();

    const retrySpy = vi.spyOn(component, 'onRetry');
    const button = fixture.debugElement.query(By.css('.error-state button'));
    (button.nativeElement as HTMLButtonElement).click();

    expect(retrySpy).toHaveBeenCalled();
  });

  it('should issue no request when no store is configured, and point at the profile', async () => {
    setup({ profileStoreId: null });
    await settle();

    expect(component.storeUnconfigured()).toBe(true);

    type('camisa');
    component.onSearch();
    fixture.detectChanges();
    await settle();

    // Fail closed: without a store there is no store-scoped read to issue.
    expect(variantServiceMock.searchVariants).not.toHaveBeenCalled();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('No hay una tienda configurada');

    const button = fixture.debugElement.query(By.css('button'));
    (button.nativeElement as HTMLButtonElement).click();
    expect(routerMock.navigate).toHaveBeenCalledWith(['/profile']);
  });

  it('should surface a failed store resolution instead of calling it unconfigured', async () => {
    setup({ profileError: true });
    await settle();

    expect(component.storeError()).not.toBeNull();
    expect(component.storeUnconfigured()).toBe(false);

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('No pudimos determinar la tienda');
  });

  it('should re-search with the new page when the paginator moves', async () => {
    setup({ totalPages: 3 });
    await settle();

    type('camisa');
    component.onSearch();
    fixture.detectChanges();
    await settle();

    component.onPageChange({ pageIndex: 2, pageSize: 10 });
    fixture.detectChanges();
    await settle();

    expect(variantServiceMock.searchVariants).toHaveBeenLastCalledWith('camisa', 'store-1', 2, 10);
  });

  it('should open the per-store editor for a row', async () => {
    setup();
    await settle();

    type('camisa');
    component.onSearch();
    fixture.detectChanges();
    await settle();

    component.editVariant(row({ productId: 'prod-9', id: 'var-9' }));

    expect(routerMock.navigate).toHaveBeenCalledWith(
      ['/products/edit', 'prod-9', 'variants', 'edit', 'var-9'],
      { queryParams: undefined },
    );
  });

  it('should carry a store named by the link into the editor', async () => {
    setup({ queryStoreId: 'store-9' });
    await settle();

    component.editVariant(row());

    expect(routerMock.navigate).toHaveBeenCalledWith(
      ['/products/edit', 'prod-1', 'variants', 'edit', 'var-1'],
      { queryParams: { storeId: 'store-9' } },
    );
  });
});
