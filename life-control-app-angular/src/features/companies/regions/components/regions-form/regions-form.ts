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
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatIconModule } from '@angular/material/icon';
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
    MatSlideToggleModule,
    MatIconModule,
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
  initialCompanyId = input<string | null>(null);
  initialCountryId = input<string | null>(null);

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
    enabled: new FormControl(true, { nonNullable: true }),
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
        enabled: region.enabled,
      });
    });

    // --- Create mode: pre-select company from query params ---
    effect(() => {
      const companyId = this.initialCompanyId();
      const region = this.regionToEdit();

      if (region || !companyId) return;
      this.selectedCompanyId.set(companyId);
    });

    // --- Create mode: pre-select country when countries load ---
    effect(() => {
      const countryId = this.initialCountryId();
      const region = this.regionToEdit();
      const countries = this.companyCountries();

      if (region || !countryId || countries.length === 0) return;

      const cc = countries.find((c) => c.id === countryId);
      if (cc) {
        this.selectedCompanyCountryId.set(cc.id);
      }
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

  /** compareWith for mat-select: option value is CompanyCountry, selected value is string ID */
  protected compareCompanyCountryById = (option: CompanyCountry | null, selectedId: string | null): boolean => {
    return option?.id === selectedId;
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

    const { regionCode, regionName, enabled } = this.formGroup.getRawValue();

    this.saveRegion.emit({
      companyId,
      countryId,
      request: {
        regionCode: regionCode.trim(),
        regionName: regionName.trim(),
        enabled,
      },
    });
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
