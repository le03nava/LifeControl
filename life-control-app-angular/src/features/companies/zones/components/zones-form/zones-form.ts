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
import { CompanyRegion } from '../../../regions/models/region.models';
import {
  CompanyZone,
  ZoneControl,
  ZoneSaveEvent,
} from '../../models/zone.models';

const ZONE_CODE_PATTERN = /^[a-zA-Z0-9-]+$/;

@Component({
  selector: 'app-zones-form',
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
  templateUrl: './zones-form.html',
  styleUrl: './zones-form.scss',
})
export class ZonesForm {
  // ─── Inputs ─────────────────────────────────────────────────
  companies = input.required<Company[]>();
  companyCountries = input<CompanyCountry[]>([]);
  regions = input<CompanyRegion[]>([]);
  zoneToEdit = input<CompanyZone | null>(null);
  serverErrors = input<Record<string, string>>({});
  initialCompanyId = input<string | null>(null);
  initialCountryId = input<string | null>(null);
  initialRegionId = input<string | null>(null);

  // ─── Outputs ────────────────────────────────────────────────
  save = output<ZoneSaveEvent>();
  cancel = output<void>();
  selectedCompanyChange = output<string>();
  selectedCountryChange = output<CompanyCountry>();
  selectedRegionChange = output<CompanyRegion>();

  // ─── Internal selector state ────────────────────────────────
  selectedCompanyId = signal<string | null>(null);
  selectedCompanyCountryId = signal<string | null>(null);
  selectedRegionId = signal<string | null>(null);

  // ─── Self-contained FormGroup ───────────────────────────────
  formGroup = new FormGroup<ZoneControl>({
    zoneCode: new FormControl('', {
      nonNullable: true,
      validators: [
        Validators.required,
        Validators.maxLength(10),
        Validators.pattern(ZONE_CODE_PATTERN),
      ],
    }),
    zoneName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(100)],
    }),
    description: new FormControl('', {
      nonNullable: false,
      validators: [Validators.maxLength(255)],
    }),
    displayOrder: new FormControl<number | null>(null, {
      nonNullable: false,
      validators: [Validators.min(1)],
    }),
    enabled: new FormControl(true, { nonNullable: true }),
  });

  // ─── Error messages ─────────────────────────────────────────
  private readonly defaultErrorMessages: Record<string, (error: any) => string> = {
    required: () => 'Este campo es obligatorio.',
    maxlength: (err) =>
      `No puede superar los ${err.requiredLength} caracteres.`,
    pattern: () => 'Solo letras, números y guiones',
    min: () => 'El valor debe ser mayor o igual a 1.',
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
  readonly isEditMode = computed(() => !!this.zoneToEdit());

  // ─── Constructor ────────────────────────────────────────────
  constructor() {
    // --- Edit mode: patch form and pre-select company/country/region ---
    effect(() => {
      const zone = this.zoneToEdit();
      if (!zone) return;

      this.selectedCompanyId.set(zone.companyId);
      this.selectedCompanyCountryId.set(zone.companyCountryId);
      this.selectedRegionId.set(zone.companyRegionId);

      this.formGroup.patchValue({
        zoneCode: zone.zoneCode,
        zoneName: zone.zoneName,
        description: zone.description ?? '',
        displayOrder: zone.displayOrder ?? null,
        enabled: zone.enabled,
      });
    });

    // --- Create mode: pre-select company from query params ---
    effect(() => {
      const companyId = this.initialCompanyId();
      const zone = this.zoneToEdit();

      if (zone || !companyId) return;
      this.selectedCompanyId.set(companyId);
    });

    // --- Create mode: pre-select country when countries load ---
    effect(() => {
      const countryId = this.initialCountryId();
      const zone = this.zoneToEdit();
      const countries = this.companyCountries();

      if (zone || !countryId || countries.length === 0) return;

      const cc = countries.find((c) => c.id === countryId);
      if (cc) {
        this.selectedCompanyCountryId.set(cc.id);
      }
    });

    // --- Create mode: pre-select region when regions load ---
    effect(() => {
      const regionId = this.initialRegionId();
      const zone = this.zoneToEdit();
      const regionList = this.regions();

      if (zone || !regionId || regionList.length === 0) return;

      const region = regionList.find((r) => r.id === regionId);
      if (region) {
        this.selectedRegionId.set(region.id);
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
          console.warn(`[ZonesForm] No control found for server error key: "${key}"`);
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
    this.selectedRegionId.set(null);
    this.selectedCompanyChange.emit(companyId);
  }

  /** compareWith for mat-select: option value is CompanyCountry, selected value is string ID */
  protected compareCompanyCountryById = (option: CompanyCountry | null, selectedId: string | null): boolean => {
    return option?.id === selectedId;
  };

  /** compareWith for mat-select: option value is CompanyRegion, selected value is string ID */
  protected compareRegionById = (option: CompanyRegion | null, selectedId: string | null): boolean => {
    return option?.id === selectedId;
  };

  onCountryChange(cc: CompanyCountry): void {
    this.selectedCompanyCountryId.set(cc.id);
    this.selectedRegionId.set(null);
    this.selectedCountryChange.emit(cc);
  }

  onRegionChange(region: CompanyRegion): void {
    this.selectedRegionId.set(region.id);
    this.selectedRegionChange.emit(region);
  }

  onSave(): void {
    this.formGroup.markAllAsTouched();

    if (this.formGroup.invalid) return;

    const companyId = this.selectedCompanyId();
    const countryId = this.selectedCompanyCountryId();
    const regionId = this.selectedRegionId();

    if (!companyId || !countryId || !regionId) return;

    const { zoneCode, zoneName, description, displayOrder, enabled } = this.formGroup.getRawValue();

    this.save.emit({
      companyId,
      countryId,
      regionId,
      request: {
        zoneCode: zoneCode.trim(),
        zoneName: zoneName.trim(),
        ...(description?.trim() ? { description: description.trim() } : {}),
        ...(displayOrder != null ? { displayOrder } : {}),
      },
    });
  }

  onCancel(): void {
    this.cancel.emit();
  }
}
