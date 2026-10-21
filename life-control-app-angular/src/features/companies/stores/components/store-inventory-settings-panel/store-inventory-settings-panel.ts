import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs/operators';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { ErrorBanner } from '@shared/ui';
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
 * The panel is the `Configuración` tab of `/companies/stores/edit/:id`, so it is
 * **presentational about identity**: the whole five-level chain arrives through the
 * required {@link chain} input, derived by the page from the store it is editing, and
 * this component never reads a route nor cascades. It therefore cannot guess a store:
 * the page renders it only once it holds one.
 *
 * A `getSettings` 404 is a normal state (the store was never configured): the
 * read maps it to `null` and the page renders an empty form ready to create.
 */
@Component({
  selector: 'app-store-inventory-settings-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule, ErrorBanner],
  templateUrl: './store-inventory-settings-panel.html',
  styleUrl: './store-inventory-settings-panel.scss',
})
export class StoreInventorySettingsPanel implements UnsavedChangesAware {
  private readonly settingsService = inject(StoreInventorySettingsService);
  private readonly locationLookup = inject(StoreLocationLookupService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * The five-level store chain of the store being edited. Required: both resources are
   * keyed on it, so setting it is what triggers the first read and changing it reloads.
   */
  readonly chain = input.required<StoreChain>();

  /** The store's persisted settings, or `null` when it was never configured (404). */
  readonly settingsResource = rxResource({
    params: () => this.chain(),
    stream: ({ params }) => this.settingsService.getSettings(params),
  });

  /** The enabled locations of the store, the data source of both pickers. */
  readonly locationsResource = rxResource({
    params: () => this.chain(),
    stream: ({ params }) => this.locationLookup.getStoreLocations(params),
  });

  // ─── Selection state ───────────────────────────────────
  /** Selected receiving location **id**; `null` until chosen (or until a reload). */
  readonly receivingLocationId = signal<string | null>(null);
  /** Selected sales location **id**; may equal the receiving one by design. */
  readonly salesLocationId = signal<string | null>(null);
  /** True once the operator changed either select; the programmatic load never sets it. */
  private readonly dirty = signal(false);
  /**
   * Optimistic-lock version of the loaded settings, echoed back on the next write. `null` when no
   * row was loaded, which is exactly the create case (the read maps a never-configured store's 404
   * to `null`).
   */
  private readonly version = signal<number | null>(null);

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
      this.version.set(settings?.version ?? null);
      this.dirty.set(false);
    });
  }

  // ─── Derived state ─────────────────────────────────────
  /**
   * Only a store writer may save: the backend rejects the write from a principal without
   * `STORE_WRITE_ROLES`, so the control is never offered to a read-only one. The page that hosts
   * this panel is already behind the write roles, which makes this defence in depth rather than
   * the only gate.
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
    const receivingLocationId = this.receivingLocationId();
    const salesLocationId = this.salesLocationId();
    if (!this.canSubmit() || !receivingLocationId || !salesLocationId) return;
    const chain = this.chain();

    this.saveError.set(null);
    this.saveErrorDetail.set(null);
    this.saving.set(true);

    const version = this.version();

    this.settingsService
      .upsertSettings(chain, {
        receivingLocationId,
        salesLocationId,
        // Spread the version only when there is one: a create must serialize no `version` key at all,
        // because the backend rejects any version sent for a store that has no row yet (409).
        ...(version !== null ? { version } : {}),
      })
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
      case 409:
        this.saveError.set(
          'Otra sesión modificó esta configuración mientras la editabas. Se recargaron los valores actuales: revisalos y volvé a guardar.',
        );
        // Clear the guard *before* reloading so the seeding effect re-seeds both selects and the
        // version from the server's current state. Without that the operator would keep resending
        // the stale version and stay stuck in a 409.
        this.dirty.set(false);
        this.settingsResource.reload();
        break;
      default:
        this.saveError.set(httpErrorMessage(error));
    }
  }
}
