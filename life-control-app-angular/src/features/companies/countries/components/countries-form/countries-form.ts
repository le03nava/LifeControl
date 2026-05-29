import {
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Subscription } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { Company } from '../../../companies/models/company.models';
import {
  CompanyCountry,
  Country,
  CountryControl,
  CountrySaveEvent,
} from '../../models/country.models';
import { CountryService } from '@features/countries/data/country.service';

@Component({
  selector: 'app-countries-form',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatSelectModule,
    MatIconModule,
  ],
  templateUrl: './countries-form.html',
  styleUrl: './countries-form.scss',
})
export class CountriesForm {
  // ─── Inputs ─────────────────────────────────────────────────
  companies = input.required<Company[]>();
  ccToEdit = input<CompanyCountry | null>(null);
  serverErrors = input<Record<string, string>>({});
  initialCompanyId = input<string | null>(null);

  // ─── Outputs ────────────────────────────────────────────────
  saveCountry = output<CountrySaveEvent>();
  cancelForm = output<void>();

  // ─── Injected services ──────────────────────────────────────
  private countryService = inject(CountryService);

  // ─── Signals ────────────────────────────────────────────────
  readonly catalogCountries = this.countryService.countries;
  readonly selectedCompanyId = signal<string | null>(null);
  readonly selectedCatalogCountry = signal<Country | null>(null);

  // ─── Self-contained FormGroup ───────────────────────────────
  formGroup = new FormGroup<CountryControl>({
    localAlias: new FormControl('', {
      validators: [Validators.maxLength(100)],
    }),
  });

  // ─── Error messages ─────────────────────────────────────────
  private readonly defaultErrorMessages: Record<
    string,
    (error: any) => string
  > = {
    maxlength: (err) =>
      `No puede superar los ${err.requiredLength} caracteres.`,
    serverError: (err) => err,
  };

  protected getErrorMessage(
    control: AbstractControl | null,
    customMessages?: Record<string, (error: any) => string>,
  ): string | null {
    if (!control || !control.errors || !control.touched) {
      return null;
    }

    const primerErrorKey = Object.keys(control.errors)[0];
    const errorDetalle = control.errors[primerErrorKey];

    const allMessages = { ...this.defaultErrorMessages, ...customMessages };
    if (allMessages[primerErrorKey]) {
      return allMessages[primerErrorKey](errorDetalle);
    }

    return 'Campo inválido.';
  }

  // ─── Computed ───────────────────────────────────────────────
  readonly isEditMode = computed(() => !!this.ccToEdit());

  // ─── Constructor ────────────────────────────────────────────
  constructor() {
    // Load catalog countries on creation
    this.countryService.getCountries().subscribe();

    // --- Edit mode: pre-fill form and selectors ---
    effect(() => {
      const cc = this.ccToEdit();
      if (!cc) return;

      this.selectedCompanyId.set(cc.companyId);

      const catCountry = this.catalogCountries().find(
        (c) => c.countryCode === cc.countryCode,
      );
      if (catCountry) {
        this.selectedCatalogCountry.set(catCountry);
      }

      this.formGroup.patchValue({
        localAlias: cc.localAlias,
      });
    });

    // --- Create mode: pre-select company from query params ---
    effect(() => {
      const companyId = this.initialCompanyId();
      const cc = this.ccToEdit();

      if (cc || !companyId) return;
      this.selectedCompanyId.set(companyId);
    });

    // --- Server errors: map to controls, clear on value change ---
    effect((onCleanup) => {
      const errors = this.serverErrors();
      const fg = this.formGroup;

      if (!fg || Object.keys(errors).length === 0) return;

      const subscriptions: Subscription[] = [];

      Object.entries(errors).forEach(([key, message]) => {
        const control = fg.get(key);
        if (control) {
          const currentErrors = control.errors || {};
          control.setErrors(
            { ...currentErrors, serverError: message },
            { emitEvent: false },
          );

          const sub = control.valueChanges.subscribe(() => {
            if (control.errors && 'serverError' in control.errors) {
              const { serverError: _, ...otherErrors } = control.errors;
              const remainingKeys = Object.keys(otherErrors);
              control.setErrors(
                remainingKeys.length > 0 ? otherErrors : null,
                { emitEvent: true },
              );
            }
          });
          subscriptions.push(sub);
        } else {
          console.warn(
            `[CountriesForm] No control found for server error key: "${key}"`,
          );
        }
      });

      onCleanup(() => {
        subscriptions.forEach((sub) => sub.unsubscribe());
      });
    });
  }

  // ─── Methods ────────────────────────────────────────────────
  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId);
  }

  onCountryChange(country: Country | null): void {
    this.selectedCatalogCountry.set(country);
  }

  /** compareWith for mat-select: compare Country objects by countryCode */
  protected compareCountryByCode = (
    a: Country | null,
    b: Country | null,
  ): boolean => {
    return a?.countryCode === b?.countryCode;
  };

  onSave(): void {
    this.formGroup.markAllAsTouched();

    if (this.formGroup.invalid) return;

    const companyId = this.selectedCompanyId();
    const country = this.selectedCatalogCountry();

    if (!companyId || !country) return;

    const { localAlias } = this.formGroup.getRawValue();

    this.saveCountry.emit({
      companyId,
      request: {
        countryCode: country.countryCode,
        localAlias: localAlias?.trim() || undefined,
      },
      countryId: this.ccToEdit()?.id,
    });
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
