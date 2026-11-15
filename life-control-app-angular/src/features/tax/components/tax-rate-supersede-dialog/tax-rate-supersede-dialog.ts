import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ErrorBanner } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import {
  COMPANY_WIDE_SCOPE,
  formatDay,
  percentToFraction,
  TaxRate,
} from '../../data/tax-rate.models';
import { TaxRateService } from '../../data/tax-rate.service';
import { REGION_SCOPE_WARNING } from '../tax-rate-declare-dialog/tax-rate-declare-dialog';

/** What the dialog needs: the tenant, and the row whose successor it collects. */
export interface TaxRateSupersedeDialogData {
  companyId: string;
  rate: TaxRate;
}

interface TaxRateSupersedeControl {
  ratePercent: FormControl<number | null>;
  effectiveFrom: FormControl<string>;
  enabled: FormControl<boolean>;
}

/**
 * The later-day rule as one validator, so the field and its error text cannot disagree.
 *
 * `YYYY-MM-DD` compares correctly as a string, which is why no `Date` is built here: `new Date('2026-11-10')`
 * is UTC midnight and would move the boundary by a day for every operator west of Greenwich.
 */
export function strictlyAfter(day: string): ValidatorFn {
  return (control: AbstractControl<string>): ValidationErrors | null => {
    const value = control.value;
    // An empty field is `required`'s refusal, not this one's: two messages for one gap is noise.
    if (!value) {
      return null;
    }
    return value > day ? null : { notAfter: true };
  };
}

/**
 * Appends the successor of one rate: `PUT …/{id}` (`S5`).
 *
 * **The superseded row is read-only on screen.** The row is not edited: it keeps answering every document
 * dated between its own day and the successor's, and the server does not touch it at all. An operator who
 * believes he is editing it is the failure this dialog exists to prevent, so its code, scope, rate and day
 * are rendered as text and not as disabled controls.
 *
 * **The form asks only for what the server cannot inherit**: the new rate as a percentage (the scale trap is
 * the declare dialog's — `CHECK (rate >= 0 AND rate <= 1)` makes a bare `0.16` a legal 0,16 %), a day
 * **strictly after** the superseded one, and the flag. `taxCode` and `regionId` are absent from the payload
 * on purpose, because the successor's scope is the scope of the row it succeeds.
 */
@Component({
  selector: 'app-tax-rate-supersede-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DecimalPipe,
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    ErrorBanner,
  ],
  templateUrl: './tax-rate-supersede-dialog.html',
  styleUrl: './tax-rate-supersede-dialog.scss',
})
export class TaxRateSupersedeDialog {
  private readonly dialogRef =
    inject<MatDialogRef<TaxRateSupersedeDialog, TaxRate | null>>(MatDialogRef);
  private readonly taxRateService = inject(TaxRateService);

  readonly data = inject<TaxRateSupersedeDialogData>(MAT_DIALOG_DATA);

  /** The `S7` sentence, imported so the two dialogs cannot state two versions of the same commitment. */
  readonly regionScopeWarning = REGION_SCOPE_WARNING;

  readonly form = signal<FormGroup<TaxRateSupersedeControl>>(
    new FormGroup<TaxRateSupersedeControl>({
      ratePercent: new FormControl<number | null>(null, {
        validators: [
          Validators.required,
          Validators.min(0),
          Validators.max(100),
          // Four decimals of a percentage is six of the fraction, which is the column's scale.
          Validators.pattern(/^\d{1,3}(\.\d{1,4})?$/),
        ],
      }),
      // No default day: today is earlier than the row itself for a rate declared ahead, so a pre-filled
      // date would be one the operator has to correct before the form can be saved at all.
      effectiveFrom: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, strictlyAfter(this.data.rate.effectiveFrom)],
      }),
      enabled: new FormControl(true, { nonNullable: true }),
    }),
  );

  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  /** The scope in words: the region's name, or the sentence that says there is none. */
  readonly scopeLabel = (rate: TaxRate): string => rate.regionName ?? COMPANY_WIDE_SCOPE;

  /** The stored fraction rendered as the percentage the operator entered it as. */
  readonly dayLabel = (isoDate: string): string => formatDay(isoDate);

  /** True when the scope being succeeded is a region, which is when the `S7` commitment belongs on screen. */
  readonly isRegional = (): boolean => this.data.rate.regionId !== null;

  onSave(): void {
    const form = this.form();
    if (form.invalid) {
      form.markAllAsTouched();
      return;
    }

    const value = form.getRawValue();
    this.saving.set(true);
    this.formError.set(null);

    this.taxRateService
      .supersedeTaxRate(this.data.companyId, this.data.rate.id, {
        // The form holds the percentage the operator typed; the endpoint takes the fraction (`S5`).
        rate: percentToFraction(value.ratePercent as number),
        effectiveFrom: value.effectiveFrom,
        // Always stated: the server refuses to invent whether the successor applies from that day.
        enabled: value.enabled,
      })
      .subscribe({
        next: (created) => this.dialogRef.close(created),
        error: (error: unknown) => {
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
   * 400 here is the day rule and nothing else — the code and the scope cannot be mistyped because nobody
   * types them — so a generic "invalid request" would hide the one thing the operator has to change. A 404
   * names the row: the row that was on screen when the dialog opened is what vanished.
   */
  private writeErrorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      if (error.status === 400) {
        return `La nueva tasa tiene que regir desde un día posterior al ${this.dayLabel(
          this.data.rate.effectiveFrom,
        )}: la fila sucedida sigue respondiendo los documentos anteriores.`;
      }
      if (error.status === 404) {
        return 'La tasa que querés suceder ya no existe para esta empresa. Recargá la lista.';
      }
    }
    return httpErrorMessage(error);
  }
}
