import {
  Component,
  computed,
  effect,
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
import { Company } from '../../../companies/models/company.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import {
  CompanyRegion,
  RegionControl,
  RegionSaveEvent,
} from '../../models/region.models';

const REGION_CODE_PATTERN = /^[a-zA-Z0-9-]+$/;

@Component({
  selector: 'app-regions-form',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatSelectModule,
  ],
  templateUrl: './regions-form.html',
  styleUrl: './regions-form.scss',
})
export class RegionsForm {
  // ─── Inputs ─────────────────────────────────────────────────
  companies = input.required<Company[]>();
  companyCountries = input<CompanyCountry[]>([]);
  regionToEdit = input<CompanyRegion | null>(null);
  serverErrors = input<Record<string, string>>({});

  // ─── Outputs ────────────────────────────────────────────────
  saveRegion = output<RegionSaveEvent>();
  cancelForm = output<void>();
  selectedCompanyChange = output<string>();
  selectedCountryChange = output<CompanyCountry>();

  // ─── Internal selector state ────────────────────────────────
  selectedCompanyId = signal<string | null>(null);
  selectedCompanyCountryId = signal<string | null>(null);

  // ─── Self-contained FormGroup ───────────────────────────────
  formGroup = new FormGroup<RegionControl>({
    regionCode: new FormControl('', {
      nonNullable: true,
      validators: [
        Validators.required,
        Validators.maxLength(10),
        Validators.pattern(REGION_CODE_PATTERN),
      ],
    }),
    regionName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(100)],
    }),
  });

  // ─── Error messages ─────────────────────────────────────────
  private readonly defaultErrorMessages: Record<string, (error: any) => string> = {
    required: () => 'Este campo es obligatorio.',
    maxlength: (err) =>
      `No puede superar los ${err.requiredLength} caracteres.`,
    pattern: () => 'Solo letras, números y guiones',
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
  readonly isEditMode = computed(() => !!this.regionToEdit());
  readonly isFormEnabled = computed(() => !!this.selectedCompanyCountryId());

  // ─── Constructor ────────────────────────────────────────────
  constructor() {
    // --- Edit mode: patch form and pre-select company/country ---
    effect(() => {
      const region = this.regionToEdit();
      if (!region) return;

      this.selectedCompanyId.set(region.companyId);
      this.selectedCompanyCountryId.set(region.companyCountryId);

      this.formGroup.patchValue({
        regionCode: region.regionCode,
        regionName: region.regionName,
      });
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
          control.setErrors({ ...currentErrors, serverError: message }, { emitEvent: false });

          const sub = control.valueChanges.subscribe(() => {
            if (control.errors && 'serverError' in control.errors) {
              const { serverError: _, ...otherErrors } = control.errors;
              const remainingKeys = Object.keys(otherErrors);
              control.setErrors(remainingKeys.length > 0 ? otherErrors : null, { emitEvent: true });
            }
          });
          subscriptions.push(sub);
        } else {
          console.warn(`[RegionsForm] No control found for server error key: "${key}"`);
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
    this.selectedCompanyCountryId.set(null);
    this.selectedCompanyChange.emit(companyId);
  }

  protected compareCompanyCountryById = (a: string | null, b: CompanyCountry | null): boolean => {
    return a === b?.id;
  };

  onCountryChange(cc: CompanyCountry): void {
    this.selectedCompanyCountryId.set(cc.id);
    this.selectedCountryChange.emit(cc);
  }

  onSave(): void {
    this.formGroup.markAllAsTouched();

    if (this.formGroup.invalid) return;

    const companyId = this.selectedCompanyId();
    const countryId = this.selectedCompanyCountryId();

    if (!companyId || !countryId) return;

    const { regionCode, regionName } = this.formGroup.getRawValue();

    this.saveRegion.emit({
      companyId,
      countryId,
      request: {
        regionCode: regionCode.trim(),
        regionName: regionName.trim(),
      },
    });
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
