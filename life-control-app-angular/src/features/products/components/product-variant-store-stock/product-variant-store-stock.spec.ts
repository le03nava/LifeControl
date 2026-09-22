import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ProductVariantStoreStock } from './product-variant-store-stock';
import { ProductVariantService } from '../../data/product-variant.service';
import {
  ProductVariantSearchResult,
  ProductVariantStoreStockRequest,
} from '../../models/product-variant.models';
import { ProfileResponse } from '@features/user/profile/data/profile.models';
import { ProfileService } from '@features/user/profile/data/profile.service';
import { NotificationService } from '@shared/data/notification';

describe('ProductVariantStoreStock', () => {
  let component: ProductVariantStoreStock;
  let fixture: ComponentFixture<ProductVariantStoreStock>;
  let variantServiceMock: {
    searchVariants: ReturnType<typeof vi.fn>;
    upsertStoreStock: ReturnType<typeof vi.fn>;
  };
  let notificationMock: { showSuccess: ReturnType<typeof vi.fn> };
  let routerMock: { navigate: ReturnType<typeof vi.fn> };

  const variantId = 'var-1';
  const barCode = '7791234567890';

  const storeRow = (
    overrides: Partial<ProductVariantSearchResult> = {},
  ): ProductVariantSearchResult => ({
    id: variantId,
    productId: 'prod-1',
    companyStoreId: 'store-1',
    barCode,
    variantName: 'Talla 38',
    listPrice: 150,
    costPrice: 90,
    stock: 8,
    ...overrides,
  });

  function searchPage(rows: ProductVariantSearchResult[]) {
    return {
      content: rows,
      totalElements: rows.length,
      totalPages: rows.length ? 1 : 0,
      size: 1,
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
      rowsError?: boolean;
      upsertError?: HttpErrorResponse;
    } = {},
  ) {
    const queryStoreId = options.queryStoreId ?? null;
    const profileStoreId =
      options.profileStoreId !== undefined ? options.profileStoreId : 'store-1';

    variantServiceMock = {
      searchVariants: options.rowsError
        ? vi.fn().mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })))
        : vi.fn().mockReturnValue(of(searchPage(options.rows ?? [storeRow()]))),
      upsertStoreStock: options.upsertError
        ? vi.fn().mockReturnValue(throwError(() => options.upsertError))
        : vi
            .fn()
            .mockReturnValue(
              of({ companyStoreId: 'store-1', listPrice: 1, costPrice: 1, stock: 1 }),
            ),
    };
    notificationMock = { showSuccess: vi.fn() };
    routerMock = { navigate: vi.fn() };

    TestBed.configureTestingModule({
      imports: [ProductVariantStoreStock, NoopAnimationsModule],
      providers: [
        { provide: ProductVariantService, useValue: variantServiceMock },
        {
          provide: ProfileService,
          useValue: {
            getProfile: options.profileError
              ? vi.fn().mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })))
              : vi.fn().mockReturnValue(of(profileResponse(profileStoreId))),
          },
        },
        { provide: NotificationService, useValue: notificationMock },
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

    fixture = TestBed.createComponent(ProductVariantStoreStock);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('variantId', variantId);
    fixture.componentRef.setInput('barCode', barCode);
    fixture.detectChanges();
  }

  /**
   * Settles the store resolution, the row read it gates and the render.
   *
   * More than one flush on purpose: the row read is withheld until the store
   * resolution settles, and a resource's load starts on the flush after the params
   * change, so a single `whenStable()` can return with the read still idle.
   */
  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
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
    // The URL answered the question: the profile read must not happen.
    expect(TestBed.inject(ProfileService).getProfile).not.toHaveBeenCalled();
  });

  it('should fall back to the profile store and read the row by stored barcode in it', async () => {
    setup({ profileStoreId: 'store-from-profile' });
    await settle();

    expect(component.storeId()).toBe('store-from-profile');
    expect(component.storeSource()).toBe('profile');
    expect(variantServiceMock.searchVariants).toHaveBeenCalledWith(
      barCode,
      'store-from-profile',
      0,
      1,
    );
  });

  it('should issue one row read, not one without the store and one with it', async () => {
    setup({ profileStoreId: 'store-from-profile' });
    await settle();

    expect(variantServiceMock.searchVariants).toHaveBeenCalledTimes(1);
  });

  it('should seed the fields from the row and keep the form pristine', async () => {
    setup({ rows: [storeRow({ listPrice: 150, costPrice: 90, stock: 8 })] });
    await settle();

    expect(component.storeForm().getRawValue()).toEqual({
      listPrice: 150,
      costPrice: 90,
      stock: 8,
    });
    // A programmatic load is not an operator edit.
    expect(component.storeFormDirty()).toBe(false);
    expect(component.storeForm().pristine).toBe(true);
  });

  it('should say the store has no row yet and start empty when the search finds none', async () => {
    setup({ rows: [] });
    await settle();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('todavía no tiene stock ni precios');
    expect(component.storeForm().getRawValue()).toEqual({
      listPrice: null,
      costPrice: null,
      stock: null,
    });
  });

  it('should report dirty and enable the save once a field is edited', async () => {
    setup();
    await settle();

    const saveButton = () =>
      fixture.debugElement.query(By.css('button[type="submit"]'))
        .nativeElement as HTMLButtonElement;

    expect(saveButton().disabled).toBe(true);

    component.storeForm().patchValue({ stock: 12 });
    fixture.detectChanges();

    expect(component.storeFormDirty()).toBe(true);
    expect(saveButton().disabled).toBe(false);
  });

  it('should emit its dirty state so the page guard can see edits made here', async () => {
    setup();
    await settle();

    const emitted: boolean[] = [];
    component.dirtyChange.subscribe((dirty) => emitted.push(dirty));

    component.storeForm().patchValue({ stock: 12 });
    fixture.detectChanges();

    expect(emitted).toEqual([true]);
  });

  it('should send only the fields the operator filled, leaving the rest absent', async () => {
    setup({ rows: [storeRow({ listPrice: 150, costPrice: null, stock: null })] });
    await settle();

    component.storeForm().patchValue({ stock: 8 });
    fixture.detectChanges();

    component.onSave();

    // `costPrice` is absent, not `null` and not `0`: absent is the only thing the
    // backend reads as "keep the stored value".
    const request: ProductVariantStoreStockRequest = { listPrice: 150, stock: 8 };
    expect(variantServiceMock.upsertStoreStock).toHaveBeenCalledWith(variantId, 'store-1', request);
    expect(
      Object.prototype.hasOwnProperty.call(
        variantServiceMock.upsertStoreStock.mock.calls[0][2],
        'costPrice',
      ),
    ).toBe(false);
  });

  it('should send a zero stock as a real value', async () => {
    setup({ rows: [storeRow({ listPrice: null, costPrice: null, stock: 8 })] });
    await settle();

    component.storeForm().patchValue({ stock: 0 });
    fixture.detectChanges();

    component.onSave();

    expect(variantServiceMock.upsertStoreStock).toHaveBeenCalledWith(variantId, 'store-1', {
      stock: 0,
    });
  });

  it('should stay pristine and not write while the form has no edits', async () => {
    setup();
    await settle();

    component.onSave();

    expect(variantServiceMock.upsertStoreStock).not.toHaveBeenCalled();
  });

  it('should block a negative value before it reaches the API', async () => {
    setup();
    await settle();

    component.storeForm().patchValue({ stock: -5 });
    fixture.detectChanges();

    component.onSave();

    expect(component.storeForm().controls.stock.invalid).toBe(true);
    expect(variantServiceMock.upsertStoreStock).not.toHaveBeenCalled();
  });

  it('should clear the dirty state, confirm and re-read the row after a save', async () => {
    setup({ rows: [storeRow({ stock: 8 })] });
    await settle();
    const readsBeforeSave = variantServiceMock.searchVariants.mock.calls.length;

    component.storeForm().patchValue({ stock: 12 });
    fixture.detectChanges();
    component.onSave();
    await settle();

    expect(notificationMock.showSuccess).toHaveBeenCalled();
    expect(component.storeFormDirty()).toBe(false);
    expect(component.storeForm().pristine).toBe(true);
    // The reload is what makes the displayed values the server's, not the form's.
    expect(variantServiceMock.searchVariants.mock.calls.length).toBe(readsBeforeSave + 1);
  });

  it('should surface a write failure without losing the value the operator typed', async () => {
    setup({
      upsertError: new HttpErrorResponse({ status: 409, statusText: 'Conflict' }),
    });
    await settle();

    component.storeForm().patchValue({ stock: 12 });
    fixture.detectChanges();
    component.onSave();
    await settle();

    expect(component.saveError()).not.toBeNull();
    expect(component.storeFormDirty()).toBe(true);
    expect(component.storeForm().getRawValue().stock).toBe(12);

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('app-error-banner')).toBeTruthy();
  });

  it('should surface a failed row read as a read failure, not as an unconfigured store', async () => {
    setup({ rowsError: true });
    await settle();

    expect(component.readError()).not.toBeNull();
    expect(component.storeUnconfigured()).toBe(false);

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('No pudimos leer el stock de esta tienda');
  });

  it('should point at the profile when no store is configured', async () => {
    setup({ profileStoreId: null });
    await settle();

    expect(component.storeUnconfigured()).toBe(true);

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('No hay una tienda configurada');

    const button = fixture.debugElement.query(By.css('button'));
    (button.nativeElement as HTMLButtonElement).click();

    expect(routerMock.navigate).toHaveBeenCalledWith(['/profile']);
  });

  it('should surface a failed store resolution with a retry', async () => {
    setup({ profileError: true });
    await settle();

    expect(component.storeError()).not.toBeNull();

    const retrySpy = vi.spyOn(component, 'retryStore');
    const button = fixture.debugElement.query(By.css('button'));
    (button.nativeElement as HTMLButtonElement).click();

    expect(retrySpy).toHaveBeenCalled();
  });
});
