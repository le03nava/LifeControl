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
import {
  LC_COMPANY_COUNTRY,
  LC_COMPANY_REGION,
  LC_COMPANY_STORE,
  LC_COMPANY_ZONE,
} from '@core/security/roles';
import { CompanyCountry } from '@features/companies/countries/models/country.models';
import { CompanyRegion } from '@features/companies/regions/models/region.models';
import { CompanyZone } from '@features/companies/zones/models/zone.models';
import { CompanyStore } from '@features/companies/stores/models/store.models';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import { ScopeAssignmentService } from '../../data/scope-assignment.service';
import {
  SCOPE_ASSIGNMENT_LEVELS,
  ScopeAssignment,
  ScopeAssignmentLevel,
  ScopeAssignmentRequest,
  scopeAssignmentLevelLabel,
} from '../../models/scope-assignment.models';

const CASCADE_ERROR_MESSAGE = 'No se pudieron cargar los niveles del alcance';

/**
 * The level a set of role names suggests, or `null` when they suggest none.
 *
 * This is an **affordance, never a gate**: it decides which level the dialog preselects and lists
 * first, and nothing else. Which level an operator may write is whatever the API accepts, so the
 * dialog always offers all four and the server stays the only authority on whether the write is
 * allowed.
 *
 * The highest scoped role wins, because the scoped roles nest downwards: an employee holding
 * `lc-company-country` needs a country to be bounded by, one holding `lc-company-region` needs a
 * region, one holding `lc-company-zone` needs a zone, and one holding only `lc-company-store` needs a
 * store. `lc-company` (and `lc-admin`) suggest nothing: the `company_id` claim already bounds them,
 * so an assignment is not what they need and picking a level for them would be inventing a rule.
 */
export function suggestedLevelFromRoles(roles: readonly string[]): ScopeAssignmentLevel | null {
  if (roles.includes(LC_COMPANY_COUNTRY)) {
    return 'COUNTRY';
  }
  if (roles.includes(LC_COMPANY_REGION)) {
    return 'REGION';
  }
  if (roles.includes(LC_COMPANY_ZONE)) {
    return 'ZONE';
  }
  // Last on purpose: the store is the narrowest scoped role, so any broader role above wins. A
  // person the API bounds by a country is not narrowed to a store because they also hold the store
  // role.
  if (roles.includes(LC_COMPANY_STORE)) {
    return 'STORE';
  }
  return null;
}

/**
 * The four levels ordered so the suggested one comes first, the rest keeping the tree's order.
 *
 * Only the order changes: every level stays offered, because the order is a hint and not a filter.
 */
export function orderedLevels(
  suggested: ScopeAssignmentLevel | null,
): readonly ScopeAssignmentLevel[] {
  if (!suggested) {
    return SCOPE_ASSIGNMENT_LEVELS;
  }
  return [suggested, ...SCOPE_ASSIGNMENT_LEVELS.filter((level) => level !== suggested)];
}

/**
 * Everything the assign dialog renders.
 *
 * The company comes in **fixed** — the employee detail already resolved it, the employee is not
 * selectable, and the API takes the company from the employee anyway — so the dialog shows the
 * company's name and offers no way to change it. `employeeRoles` are the role names the employee
 * holds or is being granted; they only order and default the level choice (see
 * {@link suggestedLevelFromRoles}) and carry no authorization decision.
 */
export interface ScopeAssignmentDialogData {
  readonly companyId: string;
  readonly companyName: string;
  readonly employeeId: string;
  /** The employee's own role names, used as a hint for the level; empty when unknown. */
  readonly employeeRoles?: readonly string[];
}

/**
 * What the opener must react to.
 *
 * `created` carries the written assignment and the page reloads the history on it; `null` means
 * closed without a write (cancel or a bare Material dismissal). A failure keeps the dialog open and
 * is reported in its own banner, so it never reaches the page as a result.
 */
export type ScopeAssignmentDialogResult = {
  readonly outcome: 'created';
  readonly assignment: ScopeAssignment;
} | null;

/**
 * Assign dialog of the employee detail: one level (country, region, zone or store) plus the node it
 * names, walked through the country → region → zone → store cascade, and the first day covered.
 *
 * There is **one** dialog for the four levels because there is one table for them: a store is a
 * level of the scope, not a second kind of assignment, so a second dialog would have been a second
 * answer to one question.
 *
 * **The level is the shape of the payload, not a field of it**: exactly one node id is sent, which
 * is what the API validates and what the database enforces with its one-node check. That is why
 * choosing a level hides the levels below the one it names and clears their values — the form can
 * never submit a region id alongside a zone id, or a store alongside the zone it belongs to. The
 * store hop reuses the same four per-level catalogue services the retired store dialog walked, so
 * there is one cascade in the app rather than two.
 *
 * Each level is addressed by the **join id** the level above returns, not by the catalogue's own id.
 * Changing a parent level resets every level below it, so the payload can never mix a store with a
 * region it does not belong to; the backend re-validates the whole chain against the company and
 * stays the authority on what it refuses.
 *
 * There is deliberately **no end date**: the create payload takes the node and the first day
 * covered, the only way to end an assignment is the close route, and a fixed-term assignment is a
 * requirement nobody stated. A `validFrom` in the future is legal and simply derives nothing until
 * it covers today.
 */
@Component({
  selector: 'app-scope-assignment-dialog',
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
  templateUrl: './scope-assignment-dialog.html',
  styleUrl: './scope-assignment-dialog.scss',
})
export class ScopeAssignmentDialog {
  private readonly dialogRef =
    inject<MatDialogRef<ScopeAssignmentDialog, ScopeAssignmentDialogResult>>(MatDialogRef);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly assignmentService = inject(ScopeAssignmentService);
  private readonly countryService = inject(CompanyCountryService);
  private readonly regionService = inject(CompanyRegionService);
  private readonly zoneService = inject(CompanyZoneService);
  private readonly storeService = inject(CompanyStoreService);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = inject<ScopeAssignmentDialogData>(MAT_DIALOG_DATA);

  /**
   * The cascade, the first day covered, and nothing else.
   *
   * Only the country is a required control from the start: the levels below exist only when the
   * chosen level needs them, and {@link onLevelChange} attaches their validators.
   */
  readonly form = this.fb.group({
    companyCountryId: ['', Validators.required],
    regionId: [''],
    zoneId: [''],
    companyStoreId: [''],
    validFrom: ['', Validators.required],
  });

  /** The levels in the order the select lists them; the suggested one comes first. */
  readonly levels = orderedLevels(suggestedLevelFromRoles(this.data.employeeRoles ?? []));

  readonly levelLabel = scopeAssignmentLevelLabel;

  /** The chosen level. A signal, not a control, because it is not part of the payload. */
  private readonly levelState = signal<ScopeAssignmentLevel>(this.levels[0] ?? 'COUNTRY');
  readonly level = this.levelState.asReadonly();

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
    this.syncLevelValidators(this.level());
    this.loadCountries();
    this.loadRegionsOnCountryChange();
    this.loadZonesOnRegionChange();
    this.loadStoresOnZoneChange();
  }

  /**
   * Switches the level the assignment names.
   *
   * Every level stays offered — this only changes which node the payload will carry — and the
   * values of the levels the new choice does not use are cleared, so the form cannot submit two node
   * ids or a stale deeper node.
   */
  onLevelChange(level: ScopeAssignmentLevel): void {
    if (level === this.level()) {
      return;
    }
    this.levelState.set(level);
    this.syncLevelValidators(level);
  }

  /** Issues the create write with exactly one node id and the first day covered. */
  onSubmit(): void {
    if (this.savingState()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const request = this.buildRequest(this.level());

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

  /**
   * The payload for `level`: the first day covered plus the **one** node id the level names.
   *
   * Built from the level rather than from the values present, so a value the form still holds from a
   * previously chosen level can never ride along.
   */
  private buildRequest(level: ScopeAssignmentLevel): ScopeAssignmentRequest {
    const value = this.form.getRawValue();
    const request: ScopeAssignmentRequest = { validFrom: value.validFrom };
    if (level === 'COUNTRY') {
      request.companyCountryId = value.companyCountryId;
    } else if (level === 'REGION') {
      request.companyRegionId = value.regionId;
    } else if (level === 'ZONE') {
      request.companyZoneId = value.zoneId;
    } else {
      request.companyStoreId = value.companyStoreId;
    }
    return request;
  }

  /**
   * Attaches the required validator to the levels this choice uses and removes it from the rest.
   *
   * The unused levels keep no value at all, so an `invalid` form is always a half-filled cascade of
   * the level actually chosen and never a leftover of another one. A store is only reachable through
   * its ancestors, so its level requires the whole four-rung chain.
   */
  private syncLevelValidators(level: ScopeAssignmentLevel): void {
    const { companyCountryId, regionId, zoneId, companyStoreId } = this.form.controls;
    companyCountryId.setValidators(Validators.required);
    regionId.setValidators(level === 'COUNTRY' ? [] : [Validators.required]);
    zoneId.setValidators(level === 'COUNTRY' || level === 'REGION' ? [] : [Validators.required]);
    companyStoreId.setValidators(level === 'STORE' ? [Validators.required] : []);

    if (level === 'COUNTRY') {
      // The region list is kept: it belongs to the country, which is still selected, so going back
      // to the region level does not force re-picking it. Only the values and the deeper lists go.
      this.form.patchValue({ regionId: '', zoneId: '', companyStoreId: '' }, { emitEvent: false });
      this.zones.set([]);
      this.stores.set([]);
    } else if (level === 'REGION') {
      this.form.patchValue({ zoneId: '', companyStoreId: '' }, { emitEvent: false });
      this.zones.set([]);
      this.stores.set([]);
    } else if (level === 'ZONE') {
      this.form.patchValue({ companyStoreId: '' }, { emitEvent: false });
      this.stores.set([]);
    }

    companyCountryId.updateValueAndValidity();
    regionId.updateValueAndValidity();
    zoneId.updateValueAndValidity();
    companyStoreId.updateValueAndValidity();
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

  /**
   * Reads the stores of the chosen zone, the fourth rung of the cascade.
   *
   * The store is the leaf of the tree, so its read needs the whole parent chain and gets it from the
   * form rather than from the selected level: a store list loaded for a zone the operator has since
   * changed would be a store from the wrong branch.
   */
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
