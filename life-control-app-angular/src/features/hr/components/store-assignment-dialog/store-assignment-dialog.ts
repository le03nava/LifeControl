import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialogActions,
  MatDialogContent,
  MatDialogRef,
  MatDialogTitle,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { catchError, of, switchMap, tap } from 'rxjs';
import { ErrorBanner } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { CompanyCountry } from '@features/companies/countries/models/country.models';
import { CompanyRegion } from '@features/companies/regions/models/region.models';
import { CompanyZone } from '@features/companies/zones/models/zone.models';
import { CompanyStore } from '@features/companies/stores/models/store.models';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import { StoreAssignmentService } from '../../data/store-assignment.service';
import { StoreAssignment, StoreAssignmentRequest } from '../../models/store-assignment.models';

const CASCADE_ERROR_MESSAGE = 'No se pudieron cargar los niveles de la tienda';

/**
 * Everything the assign dialog renders.
 *
 * The company comes in fixed: the employee detail already resolved it, and the employee is not
 * selectable either, so the dialog walks the store tree of exactly one company and offers no way to
 * change either.
 */
export interface StoreAssignmentDialogData {
  readonly companyId: string;
  readonly employeeId: string;
}

/**
 * What the opener must react to.
 *
 * `created` carries the written assignment and the page reloads the history on it; `null` means
 * closed without a write (cancel or a bare Material dismissal). A failure keeps the dialog open and
 * is reported in its own banner, so it never reaches the page as a result.
 */
export type StoreAssignmentDialogResult = {
  readonly outcome: 'created';
  readonly assignment: StoreAssignment;
} | null;

/**
 * Assign dialog of the employee detail: the country → region → zone → store cascade (`T16`) plus the
 * first day covered.
 *
 * The cascade is built from the four existing per-level services instead of the only other cascade in
 * the app (`purchase-orders/data/company-cascade.service.ts`), which is feature-local — provided by
 * the purchase-order editor, driving its header form, carrying a server-side company search — and
 * needs nothing this dialog has an input for. Generalising it would be a refactor of another feature
 * smuggled into a frontend unit; composing the four services it composes is the point of `T16`.
 *
 * There is deliberately **no end date** (`T13`): `POST` takes the store and the first day covered,
 * the only way to end an assignment is the close route, and a fixed-term store assignment is a
 * requirement nobody stated. Changing a parent level resets every level below it, so the payload can
 * never mix a store with a region it does not belong to. The submitted store is the leaf's id, and
 * the backend re-validates the whole chain against the company (`T8`) — this dialog mirrors nothing
 * and the server stays the authority on what it refuses.
 */
@Component({
  selector: 'app-store-assignment-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatDialogTitle,
    MatDialogContent,
    MatDialogActions,
    ErrorBanner,
  ],
  templateUrl: './store-assignment-dialog.html',
  styleUrl: './store-assignment-dialog.scss',
})
export class StoreAssignmentDialog {
  private readonly dialogRef =
    inject<MatDialogRef<StoreAssignmentDialog, StoreAssignmentDialogResult>>(MatDialogRef);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly assignmentService = inject(StoreAssignmentService);
  private readonly countryService = inject(CompanyCountryService);
  private readonly regionService = inject(CompanyRegionService);
  private readonly zoneService = inject(CompanyZoneService);
  private readonly storeService = inject(CompanyStoreService);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = inject<StoreAssignmentDialogData>(MAT_DIALOG_DATA);

  /**
   * The cascade and the first day covered.
   *
   * Every level is required because a store is only reachable through its ancestors: an empty select
   * above an empty store is always a half-typed cascade, never a valid payload.
   */
  readonly form = this.fb.group({
    companyCountryId: ['', Validators.required],
    regionId: ['', Validators.required],
    zoneId: ['', Validators.required],
    companyStoreId: ['', Validators.required],
    validFrom: ['', Validators.required],
  });

  /** The company's countries; empty until the read resolves. */
  readonly countries = signal<CompanyCountry[]>([]);
  readonly regions = signal<CompanyRegion[]>([]);
  readonly zones = signal<CompanyZone[]>([]);
  readonly stores = signal<CompanyStore[]>([]);

  /** A failed catalogue read: the form stays usable and this names the failure. */
  readonly catalogueError = signal<string | null>(null);

  private readonly savingState = signal(false);
  /** True while a write is in flight; the buttons disable on it. */
  readonly saving = this.savingState.asReadonly();

  /** A failed write, shown in the banner; the dialog stays open. */
  readonly actionError = signal<string | null>(null);

  constructor() {
    this.loadCountries();
    this.loadRegionsOnCountryChange();
    this.loadZonesOnRegionChange();
    this.loadStoresOnZoneChange();
  }

  /** Issues the create write with the two fields of `StoreAssignmentRequest` (`T13`). */
  onSubmit(): void {
    if (this.savingState()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const request: StoreAssignmentRequest = {
      companyStoreId: value.companyStoreId,
      validFrom: value.validFrom,
    };

    this.actionError.set(null);
    this.savingState.set(true);
    this.assignmentService
      .createAssignment(this.data.companyId, this.data.employeeId, request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (assignment) => {
          this.savingState.set(false);
          this.dialogRef.close({ outcome: 'created', assignment });
        },
        error: (err: HttpErrorResponse) => {
          this.savingState.set(false);
          this.actionError.set(this.serverMessage(err));
        },
      });
  }

  /** Closes without a write. */
  cancel(): void {
    this.dialogRef.close(null);
  }

  private loadCountries(): void {
    this.countryService
      .getCountries(this.data.companyId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (countries) => this.countries.set(countries),
        error: () => this.catalogueError.set(CASCADE_ERROR_MESSAGE),
      });
  }

  /**
   * Reads the regions of the chosen country.
   *
   * The country control holds the **company-country** join id, which is what the level below is
   * addressed by — not the country catalogue's own id. A stale response is dropped by `switchMap`,
   * and a failed read leaves an empty list and names the failure instead of emptying the form.
   */
  private loadRegionsOnCountryChange(): void {
    this.form.controls.companyCountryId.valueChanges
      .pipe(
        tap(() => this.resetBelowCountry()),
        switchMap((companyCountryId) =>
          companyCountryId
            ? this.regionService.getRegions(this.data.companyId, companyCountryId).pipe(
                catchError(() => {
                  this.catalogueError.set(CASCADE_ERROR_MESSAGE);
                  return of([] as CompanyRegion[]);
                }),
              )
            : of([] as CompanyRegion[]),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((regions) => this.regions.set(regions));
  }

  private loadZonesOnRegionChange(): void {
    this.form.controls.regionId.valueChanges
      .pipe(
        tap(() => this.resetBelowRegion()),
        switchMap((regionId) => {
          const companyCountryId = this.form.controls.companyCountryId.value;
          return regionId && companyCountryId
            ? this.zoneService.getZones(this.data.companyId, companyCountryId, regionId).pipe(
                catchError(() => {
                  this.catalogueError.set(CASCADE_ERROR_MESSAGE);
                  return of([] as CompanyZone[]);
                }),
              )
            : of([] as CompanyZone[]);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((zones) => this.zones.set(zones));
  }

  private loadStoresOnZoneChange(): void {
    this.form.controls.zoneId.valueChanges
      .pipe(
        tap(() => this.resetBelowZone()),
        switchMap((zoneId) => {
          const companyCountryId = this.form.controls.companyCountryId.value;
          const regionId = this.form.controls.regionId.value;
          return zoneId && companyCountryId && regionId
            ? this.storeService
                .getStores(this.data.companyId, companyCountryId, regionId, zoneId)
                .pipe(
                  catchError(() => {
                    this.catalogueError.set(CASCADE_ERROR_MESSAGE);
                    return of([] as CompanyStore[]);
                  }),
                )
            : of([] as CompanyStore[]);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((stores) => this.stores.set(stores));
  }

  /**
   * Clears every level below the country.
   *
   * The controls are patched with `emitEvent: false` so the reset does not itself trigger the
   * cascade: the levels below are cleared here in one place, not re-read on their way to empty.
   */
  private resetBelowCountry(): void {
    this.form.patchValue({ regionId: '', zoneId: '', companyStoreId: '' }, { emitEvent: false });
    this.regions.set([]);
    this.zones.set([]);
    this.stores.set([]);
  }

  private resetBelowRegion(): void {
    this.form.patchValue({ zoneId: '', companyStoreId: '' }, { emitEvent: false });
    this.zones.set([]);
    this.stores.set([]);
  }

  private resetBelowZone(): void {
    this.form.patchValue({ companyStoreId: '' }, { emitEvent: false });
    this.stores.set([]);
  }

  /**
   * The server's own message when the failure carried the API envelope, then the service's mapped
   * message for the status, and the shared copy otherwise. The server is the final authority on why
   * it rejected a write, so its message is preferred; the service's mapping is what the operator
   * sees when the API sent no envelope at all.
   */
  private serverMessage(error: HttpErrorResponse): string {
    const message = (error.error as { message?: unknown } | undefined)?.message;
    if (typeof message === 'string' && message.trim() !== '') {
      return message;
    }
    return this.assignmentService.error() ?? httpErrorMessage(error);
  }
}
