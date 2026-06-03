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
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatIconModule } from '@angular/material/icon';
import { Company } from '../../../companies/models/company.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyZone } from '../../../zones/models/zone.models';
import {
  CompanyStore,
  StoreControl,
  StoreSaveEvent,
} from '../../models/store.models';
import { CompanyZoneService } from '../../../zones/data/company-zone.service';
import { CountryService } from '../../../../countries/data/country.service';
import { Country } from '../../../countries/models/country.models';

@Component({
  selector: 'app-stores-form',
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
  templateUrl: './stores-form.html',
  styleUrl: './stores-form.scss',
})
export class StoresForm {
  private companyZoneService = inject(CompanyZoneService);
  private countryService = inject(CountryService);

  // ─── Inputs ─────────────────────────────────────────────────
  companies = input.required<Company[]>();
  companyCountries = input<CompanyCountry[]>([]);
  regions = input<CompanyRegion[]>([]);
  storeToEdit = input<CompanyStore | null>(null);
  serverErrors = input<Record<string, string>>({});
  initialCompanyId = input<string | null>(null);
  initialCountryId = input<string | null>(null);
  initialRegionId = input<string | null>(null);
  initialZoneId = input<string | null>(null);

  // ─── Outputs ────────────────────────────────────────────────
  save = output<StoreSaveEvent>();
  cancel = output<void>();
  selectedCompanyChange = output<string>();
  selectedCountryChange = output<CompanyCountry>();
  selectedRegionChange = output<CompanyRegion>();
  selectedZoneChange = output<string>();

  // ─── Internal selector state ────────────────────────────────
  selectedCompanyId = signal<string | null>(null);
  selectedCompanyCountryId = signal<string | null>(null);
  selectedRegionId = signal<string | null>(null);
  selectedZoneId = signal<string | null>(null);

  // ─── Zone options from CompanyZoneService ───────────────────
  private _zones = signal<CompanyZone[]>([]);
  readonly zones = this._zones.asReadonly();

  // ─── Self-contained FormGroup ───────────────────────────────
  formGroup = new FormGroup<StoreControl>({
    storeName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(100)],
    }),
    email: new FormControl<string | null>(null, {
      nonNullable: false,
      validators: [Validators.email],
    }),
    phoneNumber: new FormControl<string | null>(null, {
      nonNullable: false,
    }),
    street: new FormControl<string | null>(null, { nonNullable: false }),
    streetNumber: new FormControl<string | null>(null, { nonNullable: false }),
    internalNumber: new FormControl<string | null>(null, { nonNullable: false }),
    neighborhood: new FormControl<string | null>(null, { nonNullable: false }),
    zipCode: new FormControl<string | null>(null, { nonNullable: false }),
    city: new FormControl<string | null>(null, { nonNullable: false }),
    state: new FormControl<string | null>(null, { nonNullable: false }),
    countryId: new FormControl<string | null>(null, { nonNullable: false }),
    enabled: new FormControl(true, { nonNullable: true }),
  });

  // ─── Error messages ─────────────────────────────────────────
  private readonly defaultErrorMessages: Record<string, (error: any) => string> = {
    required: () => 'Este campo es obligatorio.',
    maxlength: (err) =>
      `No puede superar los ${err.requiredLength} caracteres.`,
    email: () => 'Ingrese un correo electrónico válido.',
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
  readonly isEditMode = computed(() => !!this.storeToEdit());

  // ─── Load zones when region changes ─────────────────────────
  private loadZonesForRegion(regionId: string): void {
    const companyId = this.selectedCompanyId();
    const countryId = this.selectedCompanyCountryId();
    if (!companyId || !countryId) return;

    this.companyZoneService.getZones(companyId, countryId, regionId).subscribe({
      next: (zones) => this._zones.set(zones),
      error: () => this._zones.set([]),
    });
  }

  // ─── Country catalog for address country selector ────────────
  private _countriesCatalog = signal<Country[]>([]);
  readonly countriesCatalog = this._countriesCatalog.asReadonly();

  // ─── Helpers for mat-select compareWith ──────────────────────
  protected compareCompanyCountryById = (option: CompanyCountry | null, selectedId: string | null): boolean => {
    return option?.id === selectedId;
  };

  protected compareRegionById = (option: CompanyRegion | null, selectedId: string | null): boolean => {
    return option?.id === selectedId;
  };

  protected compareZoneById = (option: CompanyZone | null, selectedId: string | null): boolean => {
    return option?.id === selectedId;
  };

  protected compareCountryById = (optionId: string | null, selectedId: string | null): boolean => {
    return optionId === selectedId;
  };

  // ─── Constructor ────────────────────────────────────────────
  constructor() {
    // --- Load country catalog for address country selector ---
    this.countryService.getCountries().subscribe({
      next: (countries) => this._countriesCatalog.set(countries),
      error: () => this._countriesCatalog.set([]),
    });

    // --- Edit mode: patch form and pre-select hierarchy ---
    effect(() => {
      const store = this.storeToEdit();
      if (!store) return;

      this.selectedCompanyId.set(store.companyId);
      this.selectedCompanyCountryId.set(store.companyCountryId);
      this.selectedRegionId.set(store.regionId);
      this.selectedZoneId.set(store.zoneId);

      this.formGroup.patchValue({
        storeName: store.storeName,
        email: store.email ?? null,
        phoneNumber: store.phoneNumber ?? null,
        street: store.street ?? null,
        streetNumber: store.streetNumber ?? null,
        internalNumber: store.internalNumber ?? null,
        neighborhood: store.neighborhood ?? null,
        zipCode: store.zipCode ?? null,
        city: store.city ?? null,
        state: store.state ?? null,
        countryId: store.countryId ?? null,
        enabled: store.enabled,
      });
    });

    // --- Create mode: pre-select company from query params ---
    effect(() => {
      const companyId = this.initialCompanyId();
      const store = this.storeToEdit();
      if (store || !companyId) return;
      this.selectedCompanyId.set(companyId);
    });

    // --- Create mode: pre-select country when countries load ---
    effect(() => {
      const countryId = this.initialCountryId();
      const store = this.storeToEdit();
      const countries = this.companyCountries();
      if (store || !countryId || countries.length === 0) return;
      const cc = countries.find((c) => c.id === countryId);
      if (cc) {
        this.selectedCompanyCountryId.set(cc.id);
      }
    });

    // --- Create mode: pre-select region when regions load ---
    effect(() => {
      const regionId = this.initialRegionId();
      const store = this.storeToEdit();
      const regionList = this.regions();
      if (store || !regionId || regionList.length === 0) return;
      const region = regionList.find((r) => r.id === regionId);
      if (region) {
        this.selectedRegionId.set(region.id);
        this.loadZonesForRegion(region.id);
      }
    });

    // --- Create mode: pre-select zone when zone id provided ---
    effect(() => {
      const zoneId = this.initialZoneId();
      const store = this.storeToEdit();
      if (store || !zoneId) return;
      this.selectedZoneId.set(zoneId);
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
          console.warn(`[StoresForm] No control found for server error key: "${key}"`);
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
    this.selectedZoneId.set(null);
    this._zones.set([]);
    this.selectedCompanyChange.emit(companyId);
  }

  onCountryChange(cc: CompanyCountry): void {
    this.selectedCompanyCountryId.set(cc.id);
    this.selectedRegionId.set(null);
    this.selectedZoneId.set(null);
    this._zones.set([]);
    this.selectedCountryChange.emit(cc);
  }

  onRegionChange(region: CompanyRegion): void {
    this.selectedRegionId.set(region.id);
    this.selectedZoneId.set(null);
    this.selectedRegionChange.emit(region);
    this.loadZonesForRegion(region.id);
  }

  onZoneChange(zoneId: string): void {
    this.selectedZoneId.set(zoneId);
    this.selectedZoneChange.emit(zoneId);
  }

  onSave(): void {
    this.formGroup.markAllAsTouched();

    if (this.formGroup.invalid) return;

    const companyId = this.selectedCompanyId();
    const countryId = this.selectedCompanyCountryId();
    const regionId = this.selectedRegionId();
    const zoneId = this.selectedZoneId();

    if (!companyId || !countryId || !regionId || !zoneId) return;

    const raw = this.formGroup.getRawValue();

    this.save.emit({
      companyId,
      countryId,
      regionId,
      zoneId,
      request: {
        storeName: raw.storeName.trim(),
        ...(raw.email?.trim() ? { email: raw.email.trim() } : {}),
        ...(raw.phoneNumber?.trim() ? { phoneNumber: raw.phoneNumber.trim() } : {}),
        ...(raw.street?.trim() ? { street: raw.street.trim() } : {}),
        ...(raw.streetNumber?.trim() ? { streetNumber: raw.streetNumber.trim() } : {}),
        ...(raw.internalNumber?.trim() ? { internalNumber: raw.internalNumber.trim() } : {}),
        ...(raw.neighborhood?.trim() ? { neighborhood: raw.neighborhood.trim() } : {}),
        ...(raw.zipCode?.trim() ? { zipCode: raw.zipCode.trim() } : {}),
        ...(raw.city?.trim() ? { city: raw.city.trim() } : {}),
        ...(raw.state?.trim() ? { state: raw.state.trim() } : {}),
        ...(raw.countryId?.trim() ? { countryId: raw.countryId.trim() } : {}),
      },
      storeId: this.storeToEdit()?.id,
    });
  }

  onCancel(): void {
    this.cancel.emit();
  }
}
