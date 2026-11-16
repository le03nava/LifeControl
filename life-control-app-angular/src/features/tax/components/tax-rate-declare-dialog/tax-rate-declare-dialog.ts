import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { catchError, forkJoin, map, of, switchMap } from 'rxjs';
import { ErrorBanner } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { TaxCodeService } from '../../data/tax-code.service';
import { COMPANY_WIDE_SCOPE, percentToFraction, TaxRate } from '../../data/tax-rate.models';
import { TaxRateService } from '../../data/tax-rate.service';

/** What the dialog needs from the page: the tenant, and nothing the page cannot state. */
export interface TaxRateDeclareDialogData {
  companyId: string;
}

/** One `<mat-option>` of the scope select: the empty value is the company-wide rate. */
export interface ScopeOption {
  id: string;
  label: string;
}

/** What one walk of the cascade answered: the options it could read, and the countries it could not. */
interface ScopeCascade {
  regions: ScopeOption[];
  failedCountries: string[];
}

/**
 * The sentence a failed scope read states: what is missing, and why the save is waiting on it.
 *
 * It does not offer the company-wide rate as an escape the way the old copy did. A select showing only
 * `Toda la compañía` reads as a complete list, so the escape it offered was the defect: the operator saved
 * the empty scope having never seen the regions it might belong to (`S2`).
 */
function scopeReadFailure(missing: string): string {
  return `No se pudieron leer ${missing}: el alcance quedaría incompleto, así que no se puede guardar hasta reintentar.`;
}

interface TaxRateDeclareControl {
  taxCode: FormControl<string>;
  regionId: FormControl<string>;
  ratePercent: FormControl<number | null>;
  effectiveFrom: FormControl<string>;
  enabled: FormControl<boolean>;
}

/**
 * The commitment declaring a regional rate makes, quoted from `S7` of the record where the region is chosen.
 *
 * It is not softened to "from now on" because the resolver's second step ignores `enabled` **and** the date:
 * once a region states a row for a code, a document of that region dated before it is refused rather than
 * answered with the company-wide rate. Saying anything weaker here would let an operator declare a regional
 * rate believing old documents still resolve.
 */
export const REGION_SCOPE_WARNING =
  'Declarar una tasa por región la vuelve autoritativa para ese código: desde ese día, un documento de esa región con una fecha sin tasa aplicable se rechaza (400) en lugar de usar la de compañía.';

/** Today in the operator's own calendar — the initial `effectiveFrom`, and never a UTC midnight day. */
function todayIso(now: Date = new Date()): string {
  const month = `${now.getMonth() + 1}`.padStart(2, '0');
  const day = `${now.getDate()}`.padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * Declares one rate: `POST …/tax-rates` (`S4`).
 *
 * **The rate is typed as a percentage and sent as a fraction.** `CHECK (rate >= 0 AND rate <= 1)` makes a
 * bare `0.16` a legal **0,16 %**, so a number field without the `%` suffix and the conversion lets an operator
 * declare a rate a hundred times smaller with no error at all. The conversion is `percentToFraction`, one
 * exported function, and the field is bounded to 0–100 with at most four decimals (six on the fraction, the
 * column's scale).
 *
 * **The code comes from the catalog, not from typing.** An unknown code is a 404 on a form that looks fine,
 * so the select offers the enabled `tax_code` rows the platform already serves.
 *
 * **The scope is one control with two kinds of value**, `''` for the company-wide rate and a region id
 * otherwise, because the server's field is one nullable id and the screen must not pretend they are two.
 */
@Component({
  selector: 'app-tax-rate-declare-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    ErrorBanner,
  ],
  templateUrl: './tax-rate-declare-dialog.html',
  styleUrl: './tax-rate-declare-dialog.scss',
})
export class TaxRateDeclareDialog {
  private readonly dialogRef =
    inject<MatDialogRef<TaxRateDeclareDialog, TaxRate | null>>(MatDialogRef);
  private readonly taxRateService = inject(TaxRateService);
  private readonly taxCodeService = inject(TaxCodeService);
  private readonly countryService = inject(CompanyCountryService);
  private readonly regionService = inject(CompanyRegionService);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = inject<TaxRateDeclareDialogData>(MAT_DIALOG_DATA);

  /** The `S7` commitment, on the template as one string so the sentence cannot drift between two copies. */
  readonly regionScopeWarning = REGION_SCOPE_WARNING;

  /** `rate` is a fraction on the wire and a percentage here; nothing else in the form is converted. */
  readonly form = signal<FormGroup<TaxRateDeclareControl>>(
    new FormGroup<TaxRateDeclareControl>({
      taxCode: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
      regionId: new FormControl('', { nonNullable: true }),
      ratePercent: new FormControl<number | null>(null, {
        validators: [
          Validators.required,
          Validators.min(0),
          Validators.max(100),
          // Four decimals of a percentage is six of the fraction, which is the column's scale.
          Validators.pattern(/^\d{1,3}(\.\d{1,4})?$/),
        ],
      }),
      effectiveFrom: new FormControl(todayIso(), {
        nonNullable: true,
        validators: [Validators.required],
      }),
      enabled: new FormControl(true, { nonNullable: true }),
    }),
  );

  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);
  readonly taxCodes = signal<{ code: string; label: string }[]>([]);
  readonly taxCodesLoading = signal(true);
  /** The catalog answered: the code half of the form has something to offer. */
  private readonly taxCodesReady = signal(false);
  /** `''` is the company-wide rate; every other entry is one region of the company. */
  readonly scopeOptions = signal<ScopeOption[]>([{ id: '', label: COMPANY_WIDE_SCOPE }]);
  readonly regionsLoading = signal(true);
  readonly regionsError = signal<string | null>(null);
  /** The cascade finished at least once, whether or not it finished cleanly. */
  private readonly scopeSettled = signal(false);

  /**
   * Whether the save may be offered.
   *
   * The scope is not a hint beside the form: a company-wide rate and a regional one are different keys, and
   * the regional one becomes authoritative for the code (`S7`). So the save waits until both reads that decide
   * what it could write have **settled**, and refuses while any of them failed — a select holding only
   * `Toda la compañía` because a country's regions never arrived is not a complete list (`S2`).
   */
  readonly scopeReady = computed(
    () => this.taxCodesReady() && this.scopeSettled() && this.regionsError() === null,
  );

  constructor() {
    this.taxCodeService
      .getTaxCodes()
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: (codes) => {
          this.taxCodes.set(
            codes
              .filter((code) => code.enabled)
              .map((code) => ({ code: code.code, label: code.label })),
          );
          this.taxCodesLoading.set(false);
          this.taxCodesReady.set(true);
        },
        // A failed catalog leaves `taxCodesReady` false on purpose: recovering the error into an empty
        // list made "the read failed" and "the company has no codes" the same state, and only one of
        // them may be saved from.
        error: () => {
          this.taxCodesLoading.set(false);
          this.formError.set('No se pudo cargar el catálogo de códigos de impuesto');
        },
      });

    this.loadScope();
  }

  /**
   * Reads the scope cascade: the company's countries, then each country's regions, flattened into the one list
   * the scope select offers.
   *
   * It is a method and not constructor body because the block needs a way out: it runs from the constructor and
   * again from `Reintentar`, which is only offered when the previous walk failed. Each country's failure is
   * **counted, not swallowed** — the options that did load are kept, the countries that did not are named, and
   * `scopeReady` stays false until a walk is clean, because a region quietly missing from the select is the
   * shape that let a company-wide rate be declared by an operator who never saw it.
   */
  loadScope(): void {
    this.regionsLoading.set(true);
    this.regionsError.set(null);
    this.scopeSettled.set(false);
    this.scopeOptions.set([{ id: '', label: COMPANY_WIDE_SCOPE }]);

    this.countryService
      .getCountries(this.data.companyId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        switchMap((countries) =>
          countries.length === 0
            ? of<ScopeCascade>({ regions: [], failedCountries: [] })
            : forkJoin(
                countries.map((country) =>
                  this.regionService.getRegions(this.data.companyId, country.id).pipe(
                    map((regions) => ({ country, regions, failed: false })),
                    catchError(() => of({ country, regions: [], failed: true })),
                  ),
                ),
              ).pipe(
                map((perCountry) => ({
                  regions: perCountry.flatMap(({ country, regions }) =>
                    regions
                      .filter((region) => region.enabled)
                      .map((region) => ({
                        id: region.id,
                        // With more than one country the label carries the name, so two "Norte" are not
                        // the same option.
                        label:
                          countries.length > 1
                            ? `${region.regionName} (${country.countryName})`
                            : region.regionName,
                      })),
                  ),
                  failedCountries: perCountry
                    .filter((perCountryResult) => perCountryResult.failed)
                    .map((perCountryResult) => perCountryResult.country.countryName),
                })),
              ),
        ),
        catchError(() => of<ScopeCascade | null>(null)),
      )
      .subscribe((cascade) => {
        this.regionsLoading.set(false);
        this.scopeSettled.set(true);

        // The country list itself is missing, so nothing was read: there is no partial list to keep.
        if (cascade === null) {
          this.regionsError.set(scopeReadFailure('los países y las regiones de la empresa'));
          return;
        }

        this.scopeOptions.set([{ id: '', label: COMPANY_WIDE_SCOPE }, ...cascade.regions]);
        if (cascade.failedCountries.length > 0) {
          this.regionsError.set(
            scopeReadFailure(`las regiones de ${cascade.failedCountries.join(', ')}`),
          );
        }
      });
  }

  /** True when the operator chose a region, which is when the `S7` commitment has to be on screen. */
  readonly declaresRegionalRate = (): boolean => this.form().controls.regionId.value !== '';

  /** The percentage typed, as the fraction the endpoint takes. Exposed so the template can echo it back. */
  readonly fractionPreview = (): number | null => {
    const percent = this.form().controls.ratePercent.value;
    return typeof percent === 'number' ? percentToFraction(percent) : null;
  };

  onSave(): void {
    const form = this.form();
    if (form.invalid) {
      form.markAllAsTouched();
      return;
    }

    // The button states this too, and this covers every other caller (`ngSubmit`, a future one): a rate
    // declared before the scope options exist is a rate whose scope the operator never saw (`S2`).
    if (!this.scopeReady()) {
      return;
    }

    const value = form.getRawValue();
    this.saving.set(true);
    this.formError.set(null);
    // Esc and a backdrop click are refused while the write is in flight. Material closes the ref itself on
    // both, and that close answers `undefined` — which the page reads as "no write", while the row it refused
    // to wait for was appended. `MatDialogRef`'s own ESC/backdrop subscription reads `disableClose` on the
    // ref, so flipping it here is what refuses them; the error branch restores it so the block always has an
    // exit.
    this.dialogRef.disableClose = true;

    this.taxRateService
      .declareTaxRate(this.data.companyId, {
        taxCode: value.taxCode,
        regionId: value.regionId === '' ? null : value.regionId,
        // The form holds the percentage the operator typed; the endpoint takes the fraction (`S4`).
        rate: percentToFraction(value.ratePercent as number),
        effectiveFrom: value.effectiveFrom,
        enabled: value.enabled,
      })
      .subscribe({
        next: (created) => this.dialogRef.close(created),
        error: (error: unknown) => {
          this.dialogRef.disableClose = false;
          this.saving.set(false);
          this.formError.set(this.writeErrorMessage(error));
        },
      });
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  /**
   * The server's refusal, in the operator's words.
   *
   * 409 is the duplicate `(code, scope, day)`, and it is the one refusal a generic sentence actively hides: the
   * operator has to know it is the day that collides, not the code. A 404 names the two things that can be
   * missing here — a code the catalog does not have, and a region that is not this company's.
   */
  private writeErrorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      if (error.status === 409) {
        return 'Ya hay una tasa para ese código y alcance con esa fecha de vigencia. Cambiá la fecha o sucedé la tasa existente.';
      }
      if (error.status === 404) {
        return 'El código de impuesto o la región ya no existen para esta empresa.';
      }
    }
    return httpErrorMessage(error);
  }
}
