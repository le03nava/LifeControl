import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize } from 'rxjs/operators';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { PageHeader, ErrorBanner } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { httpErrorMessage, unwrapHttpError } from '@shared/data/http-error-message';
import { ApiError } from '@shared/models';
import type { UnsavedChangesAware } from '@core/guards/unsaved-changes.guard';
import { hasAnyClientRole, STORE_WRITE_ROLES } from '@core/security/roles';
import { StoreInventorySettingsService } from '@features/inventory/data/store-inventory-settings.service';
import { StoreLocationLookupService } from '@features/inventory/data/store-location-lookup.service';
import type {
  StoreChain,
  StoreLocationSummary,
} from '@features/inventory/models/store-location-summary.models';

/**
 * Per-store inventory settings: the receiving and sales location the receipt
 * flow defaults to.
 *
 * The page is a leaf under the `stores` list: the whole chain arrives through
 * the five query params (`companyId`, `countryId`, `regionId`, `zoneId`,
 * `storeId`) and the page never cascades. When any of the five is missing it
 * fails closed — no request is issued and the operator is sent back to the store
 * list — instead of guessing a store.
 *
 * A `getSettings` 404 is a normal state (the store was never configured): the
 * read maps it to `null` and the page renders an empty form ready to create.
 */
@Component({
  selector: 'app-store-inventory-settings',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    PageHeader,
    ErrorBanner,
    RouterLink,
  ],
  templateUrl: './store-inventory-settings.html',
  styleUrl: './store-inventory-settings.scss',
})
export class StoreInventorySettings implements UnsavedChangesAware {
  private readonly route = inject(ActivatedRoute);
  private readonly settingsService = inject(StoreInventorySettingsService);
  private readonly locationLookup = inject(StoreLocationLookupService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * The five-level store chain, read once from the query params. `null` when any
   * level is missing; every read and write is gated on it.
   */
  readonly chain: StoreChain | null = this.buildChain();

  /** The store's persisted settings, or `null` when it was never configured (404). */
  readonly settingsResource = rxResource({
    params: () => this.chain ?? undefined,
    stream: ({ params }) => this.settingsService.getSettings(params),
  });

  /** The enabled locations of the store, the data source of both pickers. */
  readonly locationsResource = rxResource({
    params: () => this.chain ?? undefined,
    stream: ({ params }) => this.locationLookup.getStoreLocations(params),
  });

  // ─── Selection state ───────────────────────────────────
  /** Selected receiving location **id**; `null` until chosen (or until a reload). */
  readonly receivingLocationId = signal<string | null>(null);
  /** Selected sales location **id**; may equal the receiving one by design. */
  readonly salesLocationId = signal<string | null>(null);
  /** True once the operator changed either select; the programmatic load never sets it. */
  private readonly dirty = signal(false);

  // ─── Write state ───────────────────────────────────────
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  /** The backend's raw `ApiError.message`, rendered so no server detail is lost. */
  readonly saveErrorDetail = signal<string | null>(null);

  constructor() {
    // Seed the form from the loaded settings. This is the only writer that does
    // not mark the form dirty: a programmatic load is not an operator edit.
    effect(() => {
      // Seeding overwrites both selections and resets the guard to `false`, so a
      // reload must never win over an edit the operator already made. The template's
      // skeleton branch happens to cover the post-save reload today (`isLoading()` is
      // true while reloading), but this effect is the only programmatic writer of
      // both selections: the guard is defence in depth, not a window-closer.
      if (this.dirty()) return;
      if (!this.settingsResource.hasValue()) return;
      const settings = this.settingsResource.value();
      this.receivingLocationId.set(settings?.receivingLocationId ?? null);
      this.salesLocationId.set(settings?.salesLocationId ?? null);
      this.dirty.set(false);
    });
  }

  // ─── Derived state ─────────────────────────────────────
  /**
   * `lc-company-store-read` reaches this page — its route accepts the read role — but the backend
   * rejects the write. A read-only principal may view the configuration; it must never be offered
   * the save control.
   */
  readonly canWrite = hasAnyClientRole(STORE_WRITE_ROLES);

  /** Enabled locations, guarded so an error state never throws on read. */
  readonly locations = computed<StoreLocationSummary[]>(() =>
    this.locationsResource.hasValue() ? this.locationsResource.value() : [],
  );

  readonly hasLocations = computed(() => this.locations().length > 0);

  private readonly locationIds = computed(() => new Set(this.locations().map((l) => l.id)));

  /** The configured receiving location is no longer in the enabled list. */
  readonly receivingIsStale = computed(() => {
    const id = this.receivingLocationId();
    if (!id || !this.locationsResource.hasValue()) return false;
    return !this.locationIds().has(id);
  });

  /** The configured sales location is no longer in the enabled list. */
  readonly salesIsStale = computed(() => {
    const id = this.salesLocationId();
    if (!id || !this.locationsResource.hasValue()) return false;
    return !this.locationIds().has(id);
  });

  readonly isLoading = computed(
    () => this.settingsResource.isLoading() || this.locationsResource.isLoading(),
  );

  readonly settingsError = computed<string | null>(() => {
    const error = this.settingsResource.error();
    return error ? httpErrorMessage(error) : null;
  });

  readonly locationsError = computed<string | null>(() => {
    const error = this.locationsResource.error();
    return error ? httpErrorMessage(error) : null;
  });

  /** One primary error for the combined read; either failure blocks the form. */
  readonly loadError = computed(() => this.settingsError() ?? this.locationsError());

  /** Both locations are required, and a stale side must be re-picked. */
  readonly canSubmit = computed(() => {
    if (!this.canWrite || this.saving() || !this.hasLocations()) return false;
    if (!this.receivingLocationId() || !this.salesLocationId()) return false;
    return !this.receivingIsStale() && !this.salesIsStale();
  });

  /** compareWith for both selects: option values are plain location ids. */
  protected compareById = (a: string | null, b: string | null): boolean => a === b;

  // ─── Event handlers ────────────────────────────────────
  onSelectReceiving(locationId: string | null): void {
    this.receivingLocationId.set(locationId);
    this.dirty.set(true);
  }

  onSelectSales(locationId: string | null): void {
    this.salesLocationId.set(locationId);
    this.dirty.set(true);
  }

  onRetry(): void {
    this.settingsResource.reload();
    this.locationsResource.reload();
  }

  onSave(): void {
    const chain = this.chain;
    const receivingLocationId = this.receivingLocationId();
    const salesLocationId = this.salesLocationId();
    if (!chain || !this.canSubmit() || !receivingLocationId || !salesLocationId) return;

    this.saveError.set(null);
    this.saveErrorDetail.set(null);
    this.saving.set(true);

    this.settingsService
      .upsertSettings(chain, { receivingLocationId, salesLocationId })
      .pipe(
        finalize(() => this.saving.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.notifications.showSuccess('Configuración de inventario guardada.');
          this.dirty.set(false);
          this.settingsResource.reload();
        },
        error: (error: unknown) => this.handleSaveError(error),
      });
  }

  /**
   * Exposed to `unsavedChangesGuard`: true as soon as the operator changed either
   * select, false after a programmatic load or a successful save.
   */
  hasUnsavedChanges(): boolean {
    return this.dirty();
  }

  // ─── Internals ─────────────────────────────────────────
  private buildChain(): StoreChain | null {
    const companyId = this.route.snapshot.queryParamMap.get('companyId');
    const countryId = this.route.snapshot.queryParamMap.get('countryId');
    const regionId = this.route.snapshot.queryParamMap.get('regionId');
    const zoneId = this.route.snapshot.queryParamMap.get('zoneId');
    const storeId = this.route.snapshot.queryParamMap.get('storeId');
    if (!companyId || !countryId || !regionId || !zoneId || !storeId) return null;
    return {
      companyId,
      companyCountryId: countryId,
      regionId,
      zoneId,
      storeId,
    };
  }

  private handleSaveError(error: unknown): void {
    const httpError = unwrapHttpError(error);
    const apiError = httpError?.error as ApiError | undefined;
    this.saveErrorDetail.set(apiError?.message ?? null);

    switch (httpError?.status) {
      case 400:
        this.saveError.set('Revisá los datos: el servidor rechazó la configuración.');
        break;
      case 404:
        this.saveError.set('La tienda o la ubicación elegida no es válida para esta tienda.');
        break;
      case 403:
        this.saveError.set('No tenés permisos para configurar el inventario de esta tienda.');
        break;
      default:
        this.saveError.set(httpErrorMessage(error));
    }
  }
}
